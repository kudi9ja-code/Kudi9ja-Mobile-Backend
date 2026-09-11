package com.quadrilateral.kudi9ja.domain.loan;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.common.util.Dates;
import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.domain.notification.NotificationService;
import com.quadrilateral.kudi9ja.domain.notification.NotifyKind;
import com.quadrilateral.kudi9ja.domain.settings.PlatformSettings;
import com.quadrilateral.kudi9ja.domain.settings.SettingsService;
import com.quadrilateral.kudi9ja.domain.user.AuthService;
import com.quadrilateral.kudi9ja.domain.user.KycTier;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.domain.wallet.LedgerService;
import com.quadrilateral.kudi9ja.domain.wallet.TxKind;
import com.quadrilateral.kudi9ja.finance.Finance;
import com.quadrilateral.kudi9ja.web.dto.LoanDtos;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pricing, granting and servicing loans.
 *
 * <p>Interest is <b>flat</b>: worked out once on the amount borrowed, never
 * compounding, never growing. The tenure moves it and nothing else does.
 *
 * <p>The management fee is <b>deducted from the disbursement, never added to
 * the debt</b>. Both legs are booked — the loan gross, then the fee — so the
 * ledger shows what was lent and what was charged rather than one blended
 * figure.
 *
 * <p>There is <b>no late fee and no penalty interest</b>. The Lending Agreement
 * commits to it: even in default, the amount owed does not increase. Nothing
 * here adds to what a borrower owes after disbursement.
 */
@Service
public class LoanService {

    private static final Logger log = LoggerFactory.getLogger(LoanService.class);

    private final LoanRepository loans;
    private final UserRepository users;
    private final LedgerService ledger;
    private final SettingsService settings;
    private final NotificationService notifications;
    private final CreditScoreService creditScore;
    private final AuthService auth;

    public LoanService(
            LoanRepository loans,
            UserRepository users,
            LedgerService ledger,
            SettingsService settings,
            NotificationService notifications,
            CreditScoreService creditScore,
            AuthService auth) {
        this.loans = loans;
        this.users = users;
        this.ledger = ledger;
        this.settings = settings;
        this.notifications = notifications;
        this.creditScore = creditScore;
        this.auth = auth;
    }

    // ── Quote ──────────────────────────────────────────────────────────────

    /**
     * What a loan of this size and tenure would cost.
     *
     * <p>The client never prices a loan. It asks for this and displays it, and
     * the loan that follows is priced by the same code, so a customer can never
     * be shown one figure and charged another.
     */
    @Transactional(readOnly = true)
    public LoanDtos.QuoteResponse quote(BigDecimal amount, int months) {
        PlatformSettings s = settings.currentReadOnly();
        BigDecimal principal = Money.of(amount);

        if (months < 1 || months > s.getMaxLoanTenureMonths()) {
            throw new ApiException(
                    ErrorCode.TENURE_UNPRICED,
                    "Loans run from 1 to " + s.getMaxLoanTenureMonths() + " months.");
        }

        BigDecimal rate = s.loanRateFor(months);
        BigDecimal interest = Finance.loanInterest(s, principal, months);
        BigDecimal total = Money.add(principal, interest);
        BigDecimal fee = Finance.processingFee(s, principal);
        Instant now = Instant.now();

        boolean withinLimits = Money.gte(principal, s.getMinLoanAmount())
                && Money.lte(principal, s.getMaxLoanAmount());

        // A preview schedule, priced but not yet granted.
        Loan preview = new Loan();
        preview.setPrincipal(principal);
        preview.setTenureMonths(months);
        preview.setFlatRate(rate);
        preview.setProcessingFee(fee);
        preview.setDisbursedAt(now);
        preview.setAmountRepaid(Money.zero());
        preview.setRebateGranted(Money.zero());

        List<LoanDtos.ScheduleRow> schedule = preview.schedule(now).stream()
                .map(installment -> LoanDtos.ScheduleRow.from(installment, now))
                .toList();

        return new LoanDtos.QuoteResponse(
                principal,
                months,
                rate,
                ratePct(rate),
                costPerMonthPct(rate, months),
                interest,
                total,
                Finance.loanMonthly(s, principal, months),
                fee,
                Finance.processingFeeBasis(s, principal),
                Finance.netDisbursed(s, principal),
                schedule.isEmpty() ? null : schedule.get(0).dueDate(),
                Dates.addMonths(now, months),
                schedule,
                withinLimits,
                withinLimits
                        ? "The fee comes out of what we send you, never added to what you owe. "
                                + "There is no late fee and no penalty interest: even in default, "
                                + "the amount you owe does not increase."
                        : "We lend from " + Money.naira(s.getMinLoanAmount())
                                + " to " + Money.naira(s.getMaxLoanAmount()) + ".");
    }

    // ── Eligibility ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public LoanDtos.EligibilityResponse eligibility(UUID userId) {
        PlatformSettings s = settings.currentReadOnly();
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("That account"));

        CreditScoreService.Assessment assessment = creditScore.assess(userId);
        BigDecimal headroom = Finance.headroom(s, assessment.openPrincipal());
        BigDecimal offer = Finance.loanOffer(
                s, assessment.totalSaved(), assessment.score(), headroom);

        List<String> reasons = new ArrayList<>();
        if (!s.isLendingEnabled()) {
            reasons.add("Lending is not open right now.");
        }
        if (user.getKycTier() != KycTier.TIER2) {
            reasons.add("Finish verifying your identity to borrow.");
        }
        if (!user.getAccountStatus().canTransact()) {
            reasons.add("Your account cannot borrow while it is "
                    + user.getAccountStatus().label().toLowerCase(java.util.Locale.ROOT) + ".");
        }
        if (Money.isZeroOrLess(headroom)) {
            reasons.add("You have reached the most we lend at one time. Repay what is open first.");
        } else if (Money.isZeroOrLess(offer)) {
            reasons.add("Save with us for a while and your offer will grow. "
                    + "The smallest loan we write is " + Money.naira(s.getMinLoanAmount()) + ".");
        }

        boolean eligible = reasons.isEmpty();
        return new LoanDtos.EligibilityResponse(
                eligible,
                eligible ? offer : Money.zero(),
                headroom,
                s.getMinLoanAmount(),
                s.getMaxLoanAmount(),
                1,
                s.getMaxLoanTenureMonths(),
                assessment.score(),
                assessment.band(),
                assessment.totalSaved(),
                assessment.openPrincipal(),
                creditScore.factors(s, assessment),
                s.sortedLoanRates(),
                eligible ? null : String.join(" ", reasons));
    }

    // -- Assess, then disburse ----------------------------------------------

    /**
     * What the arithmetic makes of a request, before a person looks at it.
     *
     * <p>Everything here is re-derived from the database: the offer, the
     * headroom, the score, the tier. The client's own view of any of them is
     * not consulted, and never was.
     *
     * <p>The hard limits refuse outright, because no amount of evidence makes
     * an unverified customer verified or brings an unpriced tenure into the
     * rate card. The <b>offer</b> does not refuse, and that is the change: it
     * is recorded on the application and shown to the admin as one input among
     * the bank statement, the business and the guarantors. A thin automated
     * offer is an argument against lending, not a veto over a person who has
     * read the file — and turning somebody away before they have shown it is
     * how a lender ends up declining its best customers.
     */
    @Transactional(readOnly = true)
    public Assessed assess(UUID userId, BigDecimal amount, int months) {
        settings.requireNotInMaintenance();
        settings.requireLendingEnabled();

        PlatformSettings s = settings.currentReadOnly();
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("That account"));

        BigDecimal principal = Money.of(amount);

        if (months < 1 || months > s.getMaxLoanTenureMonths()) {
            throw new ApiException(
                    ErrorCode.TENURE_UNPRICED,
                    "Loans run from 1 to " + s.getMaxLoanTenureMonths() + " months.");
        }
        if (s.getLoanRates().get(months) == null) {
            throw new ApiException(
                    ErrorCode.TENURE_UNPRICED, "That tenure is not priced right now.");
        }
        if (user.getKycTier() != KycTier.TIER2) {
            throw new ApiException(
                    ErrorCode.KYC_TIER_TOO_LOW, "Finish verifying your identity before borrowing.");
        }
        if (!user.getAccountStatus().canTransact()) {
            throw new ApiException(
                    ErrorCode.ACCOUNT_FROZEN, "Your account cannot borrow at the moment.");
        }
        if (Money.lt(principal, s.getMinLoanAmount())) {
            throw new ApiException(
                    ErrorCode.AMOUNT_TOO_SMALL,
                    "The smallest loan we write is " + Money.naira(s.getMinLoanAmount()) + ".");
        }
        if (Money.gt(principal, s.getMaxLoanAmount())) {
            throw new ApiException(
                    ErrorCode.AMOUNT_TOO_LARGE,
                    "The largest loan we write is " + Money.naira(s.getMaxLoanAmount()) + ".");
        }

        CreditScoreService.Assessment assessment = creditScore.assess(userId);
        BigDecimal headroom = Finance.headroom(s, assessment.openPrincipal());
        BigDecimal offer = Finance.loanOffer(s, assessment.totalSaved(), assessment.score(), headroom);

        return new Assessed(user, principal, months, assessment, offer);
    }

    /**
     * What {@link #assess} found. Carried to whoever decides.
     *
     * @param offer the most the automated view would lend unaided. Advice to
     *              the admin, not a ceiling on them.
     */
    public record Assessed(
            User user,
            BigDecimal principal,
            int months,
            CreditScoreService.Assessment assessment,
            BigDecimal offer) {
    }

    /**
     * Creates the loan and puts the money in the wallet.
     *
     * <p>Reached only from an approved application: this is the moment the
     * money becomes the customer's, and nothing else in the system may call it.
     *
     * @param decidedBy the admin who approved it, by name — the loan records
     *                  who lent, which "Automated assessment" no longer answers
     */
    @Transactional
    public Loan disburse(UUID userId, Assessed assessed, String purpose, String decidedBy) {
        PlatformSettings s = settings.currentReadOnly();
        BigDecimal principal = assessed.principal();
        int months = assessed.months();
        CreditScoreService.Assessment assessment = assessed.assessment();

        Instant now = Instant.now();
        BigDecimal rate = s.loanRateFor(months);
        BigDecimal fee = Finance.processingFee(s, principal);

        Loan loan = new Loan();
        loan.setId(UUID.randomUUID());
        loan.setUserId(userId);
        loan.setPrincipal(principal);
        loan.setTenureMonths(months);
        // Frozen here, for the life of the loan.
        loan.setFlatRate(rate);
        loan.setProcessingFee(fee);
        loan.setPurpose(purpose.trim());
        loan.setRequestedAt(now);
        loan.setDisbursedAt(now);
        loan.setDueDate(Dates.addMonths(now, months));
        loan.setAmountRepaid(Money.zero());
        loan.setRebateGranted(Money.zero());
        loan.setStatus(LoanStatus.ACTIVE);
        loan.setScoreAtDecision(assessment.score());
        loan.setDecidedBy(decidedBy);
        Loan saved = loans.save(loan);

        // Booked gross then netted, so the ledger shows both the loan and the
        // fee that came out of it rather than one blended figure.
        ledger.credit(userId, principal, TxKind.LOAN_DISBURSEMENT,
                LedgerService.Entry.of("Loan disbursed - " + saved.getPurpose(), "Kudi9ja Credit")
                        .related(LedgerService.Related.LOAN, saved.getId()));

        if (Money.isPositive(fee)) {
            ledger.debit(userId, fee, TxKind.FEE,
                    LedgerService.Entry.of("Management fee deducted from loan", "Kudi9ja Credit")
                            .related(LedgerService.Related.LOAN, saved.getId()));
        }

        notifications.push(
                userId,
                NotifyKind.GENERAL,
                "Loan disbursed",
                Money.naira(saved.netDisbursed()) + " reached your wallet after the "
                        + Money.naira(fee) + " management fee. You repay "
                        + Money.naira(saved.totalRepayable()) + " over " + months
                        + (months == 1 ? " month" : " months") + ". "
                        + "You have " + s.getLoanCancellationHours() + " hours to change your mind.",
                saved.netDisbursed());

        log.info("Disbursed loan {} of {} over {} months at {}",
                saved.getId(), principal, months, rate);
        return saved;
    }

    // ── Servicing ──────────────────────────────────────────────────────────

    /** A repayment from the wallet. Never takes more than is owed. */
    @Transactional
    public LoanDtos.RepaymentResponse repay(UUID userId, UUID loanId, BigDecimal amount, String pin) {
        settings.requireNotInMaintenance();
        auth.verifyPin(userId, pin);
        Loan loan = require(userId, loanId);
        return applyRepayment(loan, amount, "Loan repayment", true);
    }

    /**
     * Applies a repayment.
     *
     * @param fromWallet whether to debit the wallet. A repayment that arrived
     *                   as a confirmed bank transfer has already been credited
     *                   to the wallet, and the debit is the second leg of that.
     */
    @Transactional
    public LoanDtos.RepaymentResponse applyRepayment(
            Loan loan, BigDecimal amount, String description, boolean fromWallet) {

        if (!loan.isOpen()) {
            throw new ApiException(ErrorCode.LOAN_CLOSED, "That loan is already closed.");
        }
        BigDecimal requested = Money.of(amount);
        if (Money.isZeroOrLess(requested)) {
            throw ApiException.validation("An amount must be above zero.");
        }

        // Never take more than is owed, even if the customer offers it.
        BigDecimal paid = Money.min(requested, loan.outstanding());

        if (fromWallet) {
            ledger.debit(loan.getUserId(), paid, TxKind.LOAN_REPAYMENT,
                    LedgerService.Entry.of(description)
                            .related(LedgerService.Related.LOAN, loan.getId()));
        }

        loan.setAmountRepaid(Money.add(loan.getAmountRepaid(), paid));
        boolean settled = Money.isZeroOrLess(loan.outstanding());
        if (settled) {
            loan.setStatus(LoanStatus.REPAID);
            loan.setSettledAt(Instant.now());
        } else if (loan.getStatus() == LoanStatus.OVERDUE
                && loan.getDueDate() != null && Instant.now().isBefore(loan.getDueDate())) {
            loan.setStatus(LoanStatus.ACTIVE);
        }
        loans.save(loan);

        if (settled) {
            notifications.push(
                    loan.getUserId(),
                    NotifyKind.REPAYMENT_PAID,
                    "Loan cleared",
                    "Your " + loan.getPurpose() + " loan is fully repaid. Nothing more is owed.",
                    paid);
        } else {
            notifications.push(
                    loan.getUserId(),
                    NotifyKind.REPAYMENT_PAID,
                    "Repayment received",
                    Money.naira(paid) + " went to your " + loan.getPurpose() + " loan. "
                            + Money.naira(loan.outstanding()) + " left.",
                    paid);
        }

        return new LoanDtos.RepaymentResponse(
                loan.getId(),
                paid,
                Money.zero(),
                loan.outstanding(),
                loan.getStatus(),
                ledger.balanceOf(loan.getUserId()),
                settled ? "That loan is fully repaid." : Money.naira(loan.outstanding()) + " left to pay.");
    }

    /**
     * Settles a loan early, with the interest rebate.
     *
     * <p>Half the interest attributable to the months that never started comes
     * back, capped at what is owed. There is no early-settlement charge —
     * charging one would punish exactly the behaviour the rebate rewards.
     */
    @Transactional
    public LoanDtos.RepaymentResponse settleEarly(UUID userId, UUID loanId, String pin) {
        settings.requireNotInMaintenance();
        auth.verifyPin(userId, pin);

        PlatformSettings s = settings.currentReadOnly();
        Loan loan = require(userId, loanId);
        if (!loan.isOpen()) {
            throw new ApiException(ErrorCode.LOAN_CLOSED, "That loan is already closed.");
        }

        Instant now = Instant.now();
        BigDecimal rebate = rebateFor(s, loan, now);
        BigDecimal due = Money.floorAtZero(Money.subtract(loan.outstanding(), rebate));

        if (Money.isPositive(due)) {
            ledger.debit(userId, due, TxKind.LOAN_REPAYMENT,
                    LedgerService.Entry.of("Early settlement — " + loan.getPurpose() + " loan")
                            .related(LedgerService.Related.LOAN, loan.getId()));
        }

        loan.setRebateGranted(Money.add(loan.getRebateGranted(), rebate));
        loan.setAmountRepaid(Money.add(loan.getAmountRepaid(), due));
        loan.setStatus(LoanStatus.REPAID);
        loan.setSettledAt(now);
        loans.save(loan);

        notifications.push(
                userId,
                NotifyKind.REPAYMENT_PAID,
                "Loan settled early",
                "You cleared your " + loan.getPurpose() + " loan and saved "
                        + Money.naira(rebate) + " in interest.",
                rebate);

        return new LoanDtos.RepaymentResponse(
                loan.getId(),
                due,
                rebate,
                Money.zero(),
                loan.getStatus(),
                ledger.balanceOf(userId),
                "Settled. You saved " + Money.naira(rebate) + " in interest.");
    }

    /**
     * The change of mind the Lending Agreement grants.
     *
     * <p>Return what was received plus the fee inside the window, pay <b>no
     * interest</b>, and the loan leaves the record as cancelled. This clause is
     * written into the agreement and was not implemented in the client; it is
     * implemented here, because a clause the system cannot honour is worse than
     * no clause.
     */
    @Transactional
    public LoanDtos.RepaymentResponse cancel(UUID userId, UUID loanId, String pin) {
        auth.verifyPin(userId, pin);

        PlatformSettings s = settings.currentReadOnly();
        Loan loan = require(userId, loanId);
        Instant now = Instant.now();
        int window = s.getLoanCancellationHours();

        if (!loan.withinCancellationWindow(now, window)) {
            throw new ApiException(
                    ErrorCode.CANCELLATION_WINDOW_CLOSED,
                    loan.getStatus() != LoanStatus.ACTIVE
                            ? "That loan cannot be cancelled."
                            : "The " + window + "-hour change-of-mind window has closed. "
                                    + "You can still settle early and get part of the interest back.");
        }

        BigDecimal returnable = loan.cancellationAmount();

        // The whole disbursement comes back — what reached the wallet and the
        // fee that was taken from it — and no interest is charged.
        ledger.debit(userId, returnable, TxKind.LOAN_REPAYMENT,
                LedgerService.Entry.of("Loan cancelled within the change-of-mind window")
                        .related(LedgerService.Related.LOAN, loan.getId()));

        loan.setAmountRepaid(returnable);
        // The interest is waived in full, so the rebate carries whatever the
        // loan would otherwise have charged.
        loan.setRebateGranted(loan.totalInterest());
        loan.setStatus(LoanStatus.CANCELLED);
        loan.setSettledAt(now);
        loan.setDecisionReasons("Cancelled by the customer inside the "
                + window + "-hour change-of-mind window. No interest charged; fee refunded.");
        loans.save(loan);

        notifications.push(
                userId,
                NotifyKind.GENERAL,
                "Loan cancelled",
                "You returned " + Money.naira(returnable) + " inside the " + window
                        + "-hour window, so no interest was charged and the "
                        + Money.naira(loan.getProcessingFee()) + " fee came back to you.",
                returnable);

        return new LoanDtos.RepaymentResponse(
                loan.getId(),
                returnable,
                loan.totalInterest(),
                Money.zero(),
                loan.getStatus(),
                ledger.balanceOf(userId),
                "Cancelled. No interest was charged and the fee was refunded.");
    }

    // ── Sweeps ─────────────────────────────────────────────────────────────

    /**
     * Marks loans that have passed their due date with a balance outstanding.
     *
     * <p>The status changes and nothing else does. <b>There is no late fee and
     * no penalty interest anywhere in this product</b> — the Lending Agreement
     * commits to it in the words "even in default, the amount you owe does not
     * increase", so going overdue must not touch the balance. What it does
     * touch is the credit score, which carries a penalty while a loan is
     * overdue and lifts it the moment the loan is cleared.
     */
    @Transactional
    public int sweepOverdue(Instant now) {
        List<Loan> pastDue = loans.findPastDue(now);

        for (Loan loan : pastDue) {
            loan.setStatus(LoanStatus.OVERDUE);
            loans.save(loan);

            notifications.push(
                    loan.getUserId(),
                    NotifyKind.REPAYMENT_DUE,
                    "Your loan is past its due date",
                    Money.naira(loan.outstanding()) + " is still outstanding. "
                            + "Nothing has been added to what you owe and nothing will be — "
                            + "there is no late fee on a Kudi9ja loan. "
                            + "Repay from your wallet whenever you can, or talk to us.",
                    loan.outstanding());
        }

        if (!pastDue.isEmpty()) {
            log.info("Overdue sweep flipped {} loan(s) to overdue", pastDue.size());
        }
        return pastDue.size();
    }

    /**
     * Reminds borrowers of an instalment falling due inside {@code withinDays}.
     *
     * <p>Only the next unsettled instalment on each open loan, and only once
     * per day per loan by virtue of the job's cadence. A reminder that arrives
     * for every instalment at once is noise, and noise is how a customer learns
     * to ignore the one that matters.
     */
    @Transactional
    public int remindUpcomingRepayments(Instant now, int withinDays) {
        int sent = 0;

        for (Loan loan : loans.findAllOpen()) {
            java.util.Optional<Installment> next = loan.nextInstallment(now);
            if (next.isEmpty()) {
                continue;
            }
            Installment installment = next.get();
            long daysUntil = installment.daysUntilDue(now);
            if (daysUntil < 0 || daysUntil > withinDays) {
                continue;
            }

            notifications.push(
                    loan.getUserId(),
                    NotifyKind.REPAYMENT_DUE,
                    daysUntil == 0 ? "A repayment is due today" : "A repayment is due soon",
                    "Instalment " + installment.number() + " of "
                            + Money.naira(installment.outstanding()) + " is due "
                            + (daysUntil == 0 ? "today"
                                    : daysUntil == 1 ? "tomorrow" : "in " + daysUntil + " days")
                            + ". You can repay it from your wallet in the app.",
                    installment.outstanding());
            sent++;
        }

        if (sent > 0) {
            log.info("Sent {} upcoming-repayment reminder(s)", sent);
        }
        return sent;
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Loan> list(UUID userId) {
        return loans.findByUserIdOrderByRequestedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Loan get(UUID userId, UUID loanId) {
        return require(userId, loanId);
    }

    @Transactional(readOnly = true)
    public BigDecimal totalOwed(UUID userId) {
        return loans.findByUserIdAndStatusIn(userId, List.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE))
                .stream()
                .map(Loan::outstanding)
                .reduce(Money.zero(), Money::add);
    }

    /** The rebate this loan would earn if it were settled now. */
    @Transactional(readOnly = true)
    public BigDecimal rebateFor(PlatformSettings s, Loan loan, Instant now) {
        if (!loan.isOpen() || loan.getDisbursedAt() == null) {
            return Money.zero();
        }
        return Finance.earlyPayoffRebate(
                s,
                loan.totalInterest(),
                loan.outstanding(),
                loan.getTenureMonths(),
                loan.getDisbursedAt(),
                now);
    }

    /** Builds the response, with the settlement figures worked out. */
    @Transactional(readOnly = true)
    public LoanDtos.LoanResponse toResponse(Loan loan) {
        PlatformSettings s = settings.currentReadOnly();
        Instant now = Instant.now();
        BigDecimal rebate = rebateFor(s, loan, now);
        return LoanDtos.LoanResponse.from(
                loan,
                now,
                rebate,
                Money.floorAtZero(Money.subtract(loan.outstanding(), rebate)),
                s.getLoanCancellationHours());
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private Loan require(UUID userId, UUID loanId) {
        return loans.findByIdAndUserId(loanId, userId)
                .orElseThrow(() -> ApiException.notFound("That loan"));
    }

    static String ratePct(BigDecimal rate) {
        if (rate == null) {
            return "0%";
        }
        BigDecimal pct = rate.multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return pct.toPlainString() + "%";
    }

    /** The figure that matters when comparing tenures: cost per month. */
    static BigDecimal costPerMonthPct(BigDecimal rate, int months) {
        if (months <= 0) {
            return BigDecimal.ZERO;
        }
        return rate.multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
    }
}
