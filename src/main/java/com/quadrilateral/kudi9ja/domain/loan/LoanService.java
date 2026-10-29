package com.quadrilateral.kudi9ja.domain.loan;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.domain.notification.NotificationService;
import com.quadrilateral.kudi9ja.domain.notification.NotifyKind;
import com.quadrilateral.kudi9ja.domain.settings.LendingTerms;
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
    private final AuthService auth;

    public LoanService(
            LoanRepository loans,
            UserRepository users,
            LedgerService ledger,
            SettingsService settings,
            NotificationService notifications,
            AuthService auth,
            com.quadrilateral.kudi9ja.domain.group.LoanGroupMemberRepository groupMembers,
            com.quadrilateral.kudi9ja.domain.group.LoanGroupRepository groupRepository) {
        this.loans = loans;
        this.users = users;
        this.ledger = ledger;
        this.settings = settings;
        this.notifications = notifications;
        this.auth = auth;
        this.groupMembers = groupMembers;
        this.groupRepository = groupRepository;
    }

    private final com.quadrilateral.kudi9ja.domain.group.LoanGroupMemberRepository groupMembers;
    private final com.quadrilateral.kudi9ja.domain.group.LoanGroupRepository groupRepository;

    // ── Quote ──────────────────────────────────────────────────────────────

    /**
     * What a loan of this size and tenure would cost.
     *
     * <p>The client never prices a loan. It asks for this and displays it, and
     * the loan that follows is priced by the same code, so a customer can never
     * be shown one figure and charged another.
     */
    @Transactional(readOnly = true)
    public LoanDtos.QuoteResponse quote(UUID userId, BigDecimal amount, int months, RepaymentFrequency frequency) {
        PlatformSettings s = settings.currentReadOnly();
        // A member of a loan group is priced on the group terms.
        LendingTerms t = userId == null ? s.lending() : termsFor(s, userId);
        BigDecimal principal = Money.of(amount);
        frequency = RepaymentFrequency.orMonthly(frequency);
        requireOffered(t, frequency);

        if (months < 1 || months > t.maxTenureMonths()) {
            throw new ApiException(
                    ErrorCode.TENURE_UNPRICED,
                    "Loans run from 1 to " + t.maxTenureMonths() + " months.");
        }

        BigDecimal rate = t.rateFor(months);
        BigDecimal fee = Finance.processingFee(t, principal);
        BigDecimal feeRate = Finance.repaymentFeeRate(t);
        Instant now = Instant.now();

        boolean withinLimits = Money.gte(principal, t.minAmount())
                && Money.lte(principal, t.maxAmount());

        // A preview schedule, priced but not yet granted.
        Loan preview = new Loan();
        preview.setPrincipal(principal);
        preview.setTenureMonths(months);
        preview.setRepaymentFrequency(frequency);
        preview.setFlatRate(rate);
        preview.setProcessingFee(fee);
        preview.setTransactionFee(Money.zero());
        preview.setDisbursedAt(now);
        preview.setAmountRepaid(Money.zero());
        preview.setRebateGranted(Money.zero());
        preview.fixRepaymentTerms(now, feeRate);
        List<Finance.InstalmentPart> plan = preview.instalmentPlan();
        Finance.InstalmentPart first = plan.isEmpty()
                ? new Finance.InstalmentPart(Money.zero(), Money.zero())
                : plan.get(0);

        List<LoanDtos.ScheduleRow> schedule = preview.schedule(now).stream()
                .map(installment -> LoanDtos.ScheduleRow.from(installment, now))
                .toList();

        return new LoanDtos.QuoteResponse(
                principal,
                months,
                rate,
                ratePct(rate),
                costPerMonthPct(rate, months),
                preview.totalInterest(),
                preview.totalRepayable(),
                frequency,
                frequency.label(),
                preview.installmentCount(),
                first.total(),
                first.base(),
                first.fee(),
                feeRate,
                ratePct(feeRate),
                preview.repaymentFees(),
                preview.loanAndInterest(),
                fee,
                Finance.processingFeeBasis(t, principal),
                Money.zero(),
                Finance.netDisbursed(t, principal),
                schedule.isEmpty() ? null : schedule.get(0).dueDate(),
                preview.getDueDate(),
                schedule,
                withinLimits,
                withinLimits
                        ? "Only the management fee comes out of what we send you. Each repayment carries a transaction fee of "
                                + ratePct(feeRate) + " of the instalment, already included in every amount here. "
                                + "There is no late fee and no penalty interest: even in default, "
                                + "the amount you owe does not increase."
                        : "We lend from " + Money.naira(t.minAmount())
                                + " to " + Money.naira(t.maxAmount()) + ".");
    }

    // ── Eligibility ────────────────────────────────────────────────────────

    /**
     * Whether this customer may apply at all, and the range they may ask in.
     *
     * <p>No offer is computed and no score is consulted. Kudi9ja lends on a
     * person's reading of the application — the bank statement, the business,
     * the guarantor — and a customer asks for what they need, from the
     * smallest loan the company writes to the largest. What refuses here is
     * only what no application could change.
     */
    @Transactional(readOnly = true)
    public LoanDtos.EligibilityResponse eligibility(UUID userId) {
        PlatformSettings s = settings.currentReadOnly();
        LendingTerms t = termsFor(s, userId);
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("That account"));

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

        boolean eligible = reasons.isEmpty();
        return new LoanDtos.EligibilityResponse(
                eligible,
                t.minAmount(),
                t.maxAmount(),
                1,
                t.maxTenureMonths(),
                new java.util.TreeMap<>(t.rates()),
                eligible ? null : String.join(" ", reasons),
                t.group(),
                com.quadrilateral.kudi9ja.web.dto.SettingsDtos.Lending.of(t));
    }

    // ── Assess, then disburse ──────────────────────────────────────────────

    /**
     * The hard limits on a request, before a person looks at it.
     *
     * <p>Everything here is re-derived from the database; the client's own
     * view of any of it is not consulted. These refuse outright because no
     * amount of evidence makes an unverified customer verified, brings an
     * unpriced tenure into the rate card, or turns ₦20,000 into a loan the
     * company writes. Within them, anything goes forward: whether this
     * customer should have this amount is the admin's call, made on the
     * application, and nothing automated stands in front of it.
     */
    @Transactional(readOnly = true)
    public Assessed assess(UUID userId, BigDecimal amount, int months, RepaymentFrequency frequency) {
        return assess(userId, amount, months, frequency, false);
    }

    /**
     * As above. {@code ratePinned}: the rate was set by an admin on this loan,
     * so the tenure need not have its own entry on the rate card.
     */
    @Transactional(readOnly = true)
    public Assessed assess(
            UUID userId, BigDecimal amount, int months, RepaymentFrequency frequency, boolean ratePinned) {
        settings.requireNotInMaintenance();
        settings.requireLendingEnabled();

        PlatformSettings s = settings.currentReadOnly();
        LendingTerms t = termsFor(s, userId);
        requireOffered(t, frequency);
        User user = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("That account"));

        BigDecimal principal = Money.of(amount);

        if (months < 1 || months > t.maxTenureMonths()) {
            throw new ApiException(
                    ErrorCode.TENURE_UNPRICED,
                    "Loans run from 1 to " + t.maxTenureMonths() + " months.");
        }
        if (!ratePinned && !t.prices(months)) {
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
        if (Money.lt(principal, t.minAmount())) {
            throw new ApiException(
                    ErrorCode.AMOUNT_TOO_SMALL,
                    "The smallest loan we write is " + Money.naira(t.minAmount()) + ".");
        }
        if (Money.gt(principal, t.maxAmount())) {
            throw new ApiException(
                    ErrorCode.AMOUNT_TOO_LARGE,
                    "The largest loan we write is " + Money.naira(t.maxAmount()) + ".");
        }

        return new Assessed(user, principal, months, t);
    }

    /** What {@link #assess} found. Carried to whoever decides. */
    public record Assessed(User user, BigDecimal principal, int months, LendingTerms terms) {
    }

    /**
     * The three prices of a loan: flat interest for the whole loan and the
     * transaction fee on each repayment, both as fractions, and the management
     * fee in naira.
     */
    public record Pricing(BigDecimal flatRate, BigDecimal managementFee, BigDecimal repaymentFeeRate) {
    }

    /** What these terms charge a loan of this size and length. */
    public static Pricing standardPricing(LendingTerms t, BigDecimal principal, int months) {
        return new Pricing(t.rateFor(months), Finance.processingFee(t, Money.of(principal)), Finance.repaymentFeeRate(t));
    }

    /**
     * The terms this customer borrows on: the loan-group terms for a member
     * of an active loan group, the standard terms for everybody else.
     */
    public LendingTerms termsFor(UUID userId) {
        return termsFor(settings.currentReadOnly(), userId);
    }

    private LendingTerms termsFor(PlatformSettings s, UUID userId) {
        boolean member = groupMembers.findByUserId(userId).stream()
                .anyMatch(m -> groupRepository.findById(m.getGroupId())
                        .map(com.quadrilateral.kudi9ja.domain.group.LoanGroup::isActive)
                        .orElse(false));
        return s.lending(member);
    }

    private static void requireOffered(LendingTerms t, RepaymentFrequency frequency) {
        if (!t.allows(frequency)) {
            throw ApiException.validation(RepaymentFrequency.orMonthly(frequency).label()
                    + " repayment is not offered on " + (t.group() ? "group loans" : "these loans") + " right now.");
        }
    }

    /**
     * Creates the loan and puts the money in the wallet.
     *
     * <p>Reached only from an approved application: this is the moment the
     * money becomes the customer's, and nothing else in the system may call it.
     *
     * @param decidedBy the admin who approved it, by name — the loan records
     *                  who lent
     */
    @Transactional
    public Loan disburse(
            UUID userId, Assessed assessed, RepaymentFrequency frequency, String purpose, String decidedBy) {
        return disburse(userId, assessed, frequency, purpose, decidedBy, null);
    }

    /**
     * As above, priced on {@code agreed} — terms an admin changed and the
     * customer agreed to — instead of the settings in force. Null prices it on
     * the settings.
     */
    @Transactional
    public Loan disburse(
            UUID userId,
            Assessed assessed,
            RepaymentFrequency frequency,
            String purpose,
            String decidedBy,
            Pricing agreed) {
        frequency = RepaymentFrequency.orMonthly(frequency);
        PlatformSettings s = settings.currentReadOnly();
        BigDecimal principal = assessed.principal();
        int months = assessed.months();
        LendingTerms t = assessed.terms();

        Instant now = Instant.now();
        Pricing pricing = agreed != null ? agreed : standardPricing(t, principal, months);
        BigDecimal rate = pricing.flatRate();
        BigDecimal fee = Money.of(pricing.managementFee());

        Loan loan = new Loan();
        loan.setId(UUID.randomUUID());
        loan.setUserId(userId);
        loan.setPrincipal(principal);
        loan.setTenureMonths(months);
        loan.setRepaymentFrequency(frequency);
        // Frozen here, for the life of the loan.
        loan.setFlatRate(rate);
        loan.setProcessingFee(fee);
        loan.setTransactionFee(Money.zero());
        loan.setPurpose(purpose.trim());
        loan.setRequestedAt(now);
        loan.setDisbursedAt(now);
        // The count, the fee on each repayment and the due date.
        loan.fixRepaymentTerms(now, pricing.repaymentFeeRate());
        loan.setAmountRepaid(Money.zero());
        loan.setRebateGranted(Money.zero());
        loan.setStatus(LoanStatus.ACTIVE);
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
                        + Money.naira(saved.totalRepayable()) + " in " + saved.installmentCount() + " "
                        + saved.frequency().label().toLowerCase(java.util.Locale.ROOT) + " instalments of "
                        + Money.naira(saved.installmentAmount()) + ", each including a transaction fee of "
                        + ratePct(saved.repaymentFeeRate()) + ".",
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
     * Pays off everything still owed in one go.
     *
     * <p>Paying early is the same as paying on time, only sooner: the amount is
     * what is outstanding, interest included. There is no discount for it and
     * no charge either.
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
        BigDecimal due = loan.outstanding();

        if (Money.isPositive(due)) {
            ledger.debit(userId, due, TxKind.LOAN_REPAYMENT,
                    LedgerService.Entry.of("Early settlement — " + loan.getPurpose() + " loan")
                            .related(LedgerService.Related.LOAN, loan.getId()));
        }

        loan.setAmountRepaid(Money.add(loan.getAmountRepaid(), due));
        loan.setStatus(LoanStatus.REPAID);
        loan.setSettledAt(now);
        loans.save(loan);

        notifications.push(
                userId,
                NotifyKind.REPAYMENT_PAID,
                "Loan paid off",
                "You paid off your " + loan.getPurpose() + " loan in full with "
                        + Money.naira(due) + ". Nothing more is owed.",
                due);

        return new LoanDtos.RepaymentResponse(
                loan.getId(),
                due,
                Money.zero(),
                Money.zero(),
                loan.getStatus(),
                ledger.balanceOf(userId),
                "Paid off in full.");
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

    /** Builds the response, with the settlement figures worked out. */
    @Transactional(readOnly = true)
    public LoanDtos.LoanResponse toResponse(Loan loan) {
        Instant now = Instant.now();
        // Paying off now costs exactly what is outstanding: there is no rebate.
        return LoanDtos.LoanResponse.from(loan, now, Money.zero(), loan.outstanding());
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
