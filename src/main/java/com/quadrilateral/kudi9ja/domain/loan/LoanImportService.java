package com.quadrilateral.kudi9ja.domain.loan;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.common.util.Dates;
import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.domain.admin.AdminAccessService;
import com.quadrilateral.kudi9ja.domain.admin.AdminUser;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.notification.NotificationService;
import com.quadrilateral.kudi9ja.domain.notification.NotifyKind;
import com.quadrilateral.kudi9ja.domain.group.LoanGroupService;
import com.quadrilateral.kudi9ja.domain.officer.LoanOfficerService;
import com.quadrilateral.kudi9ja.domain.user.AccountStatus;
import com.quadrilateral.kudi9ja.domain.user.KycTier;
import com.quadrilateral.kudi9ja.domain.user.ThemeMode;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.domain.wallet.LedgerService;
import com.quadrilateral.kudi9ja.web.dto.LoanImportDtos;
import com.quadrilateral.kudi9ja.web.dto.OfflineLoanDtos;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brings the loans written on paper into the loan book.
 *
 * <p>An admin enters each one against the customer's BVN. If the customer
 * already has an account the loan lands on it at once; otherwise the row waits,
 * and {@link #claimFor} attaches it the moment the BVN is verified at signup.
 * Either way the customer opens the app and finds the debt already recorded.
 *
 * <p>The ledger is never touched. The money moved by bank transfer outside
 * the app, so there is no disbursement to credit and no repayment to debit: the
 * wallet stays at zero and the loan alone records what is owed.
 *
 * <p>An unclaimed row may be deleted, because it is a typo waiting to be
 * corrected. A claimed one may not: it has become a loan on somebody's account,
 * and a loan is closed by repayment or by a write-off, never by deletion.
 */
@Service
public class LoanImportService {

    private static final Logger log = LoggerFactory.getLogger(LoanImportService.class);
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH).withZone(Dates.LAGOS);

    private final ImportedLoanRepository imports;
    private final LoanRepository loans;
    private final UserRepository users;
    private final AdminAccessService access;
    private final AuditService audit;
    private final NotificationService notifications;
    private final LoanOfficerService officers;
    private final LoanGroupService groups;
    private final LedgerService ledger;
    private final EnteredLoanRemoval removal;
    private final RecordedRepaymentRepository recorded;

    public LoanImportService(
            ImportedLoanRepository imports,
            LoanRepository loans,
            UserRepository users,
            AdminAccessService access,
            AuditService audit,
            NotificationService notifications,
            LoanOfficerService officers,
            LoanGroupService groups,
            LedgerService ledger,
            EnteredLoanRemoval removal,
            RecordedRepaymentRepository recorded,
            com.quadrilateral.kudi9ja.domain.settings.SettingsService settings) {
        this.imports = imports;
        this.loans = loans;
        this.users = users;
        this.access = access;
        this.audit = audit;
        this.notifications = notifications;
        this.officers = officers;
        this.groups = groups;
        this.ledger = ledger;
        this.removal = removal;
        this.recorded = recorded;
        this.settings = settings;
    }

    private final com.quadrilateral.kudi9ja.domain.settings.SettingsService settings;

    // ── Admin ──────────────────────────────────────────────────────────────

    /**
     * Enters one paper loan.
     *
     * <p>The figures are checked against each other, not just individually: a
     * fee larger than the loan, or more repaid than was ever owed, is a typo
     * however valid each number is on its own.
     */
    @Transactional
    public ImportedLoan importLoan(LoanImportDtos.ImportLoanRequest request) {
        // Entered into a group: whoever runs it may, whatever their role, and its
        // officer leads. Otherwise, anyone who can act on loans.
        AdminUser actor = request.groupId() == null
                ? access.requireCanActOnLoans()
                : groups.requireCanAddTo(request.groupId());
        Instant now = Instant.now();
        AdminUser groupOfficer = request.groupId() == null ? null : groups.officerOf(request.groupId());

        BigDecimal principal = Money.of(request.principal());
        BigDecimal fee = request.processingFee() == null ? Money.zero() : Money.of(request.processingFee());
        BigDecimal transactionFee = request.transactionFee() == null ? Money.zero() : Money.of(request.transactionFee());
        List<OfflineLoanDtos.OfflineRepaymentRequest> payments =
                request.repayments() == null ? List.of() : request.repayments();
        BigDecimal repaid = request.amountRepaid() == null ? Money.zero() : Money.of(request.amountRepaid());
        Instant lastPaid = null;
        for (OfflineLoanDtos.OfflineRepaymentRequest p : payments) {
            if (p.paidAt() == null) {
                throw ApiException.validation("Enter the date of each repayment.");
            }
            if (Dates.lagosDate(p.paidAt()).isAfter(Dates.lagosDate(now))) {
                throw ApiException.validation("A repayment is dated " + DAY.format(p.paidAt())
                        + ", which has not come yet.");
            }
            if (Dates.lagosDate(p.paidAt()).isBefore(Dates.lagosDate(request.disbursedAt()))) {
                throw ApiException.validation("A repayment is dated " + DAY.format(p.paidAt())
                        + ", before the loan was paid out on " + DAY.format(request.disbursedAt()) + ".");
            }
            repaid = Money.add(repaid, Money.of(p.amount()));
            lastPaid = lastPaid == null || p.paidAt().isAfter(lastPaid) ? p.paidAt() : lastPaid;
        }

        if (Dates.lagosDate(request.disbursedAt()).isAfter(Dates.lagosDate(now))) {
            throw ApiException.validation("The disbursement date is in the future.");
        }
        if (Money.gte(Money.add(fee, transactionFee), principal)) {
            throw ApiException.validation("The management and transaction fees together cannot be as much as the loan itself.");
        }

        // Another loan for somebody already on the system: who they are comes
        // from their account, not from what is typed.
        User existing = null;
        if (request.customerId() != null) {
            existing = users.findById(request.customerId())
                    .orElseThrow(() -> ApiException.notFound("That customer"));
            if (existing.getBvn() == null || existing.getBvn().isBlank()) {
                throw ApiException.validation("This customer has no BVN on their account, so a loan cannot be entered for them.");
            }
        } else {
            if (request.bvn() == null || request.bvn().isBlank()) {
                throw ApiException.validation("The customer's BVN is needed. It is how the loan finds them when they sign up.");
            }
            if (request.fullName() == null || request.fullName().isBlank()) {
                throw ApiException.validation("Enter the customer's name as it appears on their BVN.");
            }
            existing = users.findByBvn(request.bvn().trim()).orElse(null);
        }

        ImportedLoan row = new ImportedLoan();
        row.setId(UUID.randomUUID());
        row.setBvn(existing != null ? existing.getBvn() : request.bvn().trim());
        row.setFullName(existing != null ? existing.getFullName() : request.fullName().trim());
        row.setEmail(request.email() == null || request.email().isBlank()
                ? null : request.email().trim().toLowerCase(Locale.ROOT));
        row.setPhone(request.phone() == null || request.phone().isBlank() ? null : request.phone().trim());
        row.setPrincipal(principal);
        row.setTenureMonths(request.tenureMonths());
        row.setRepaymentFrequency(RepaymentFrequency.orMonthly(request.repaymentFrequency()));
        // The transaction fee on each repayment: what was agreed, or today's rate.
        row.setRepaymentFeeRate(request.repaymentFeeRate() != null
                ? request.repaymentFeeRate()
                : com.quadrilateral.kudi9ja.finance.Finance.repaymentFeeRate(
                        settings.currentReadOnly().lending(request.groupId() != null)));
        row.setFlatRate(request.flatRate());
        row.setProcessingFee(fee);
        row.setTransactionFee(transactionFee);
        row.setPurpose(request.purpose().trim());
        row.setDisbursedAt(request.disbursedAt());
        OfflineLoanService.applyFile(row, request);
        row.setAmountRepaid(repaid);
        row.setImportedBy(actor.getName());
        row.setImportedAt(now);

        if (Money.gt(repaid, row.totalRepayable())) {
            throw ApiException.validation("The amount repaid, " + Money.naira(repaid)
                    + ", is more than the " + Money.naira(row.totalRepayable())
                    + " that was ever owed on this loan.");
        }
        // One running loan at a time: every other loan the customer has had
        // must be closed before another one still being repaid goes on.
        if (Money.isPositive(row.outstanding()) && existing != null
                && !loans.findByUserIdAndStatusIn(existing.getId(), List.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE)).isEmpty()) {
            throw new ApiException(
                    ErrorCode.CONFLICT,
                    existing.getFullName() + " already has a running loan. A customer has one running loan at a time, "
                            + "so this one can only be entered as closed: add its repayments up to the full "
                            + Money.naira(row.totalRepayable()) + ".");
        }

        ImportedLoan saved = imports.save(row);
        // Every borrower has at least one loan officer from the day the loan exists.
        List<UUID> officerIds = new java.util.ArrayList<>();
        if (groupOfficer != null) {
            officerIds.add(groupOfficer.getId());
        }
        if (request.officerIds() != null) {
            officerIds.addAll(request.officerIds());
        }
        officers.startImport(saved, actor, officerIds);
        if (request.groupId() != null) {
            groups.addImported(request.groupId(), saved, actor);
        }

        audit.record(
                actorOf(actor),
                AuditCategory.LOAN,
                "Paper loan entered",
                actor.getName() + " entered a " + Money.naira(saved.getPrincipal()) + " loan over "
                        + saved.getTenureMonths() + " months for " + saved.getFullName()
                        + " (BVN ending " + saved.getBvn().substring(7) + "), disbursed "
                        + DAY.format(saved.getDisbursedAt()) + ", with "
                        + Money.naira(saved.getAmountRepaid()) + " already repaid. Management fee "
                        + Money.naira(saved.getProcessingFee()) + ", transaction fee "
                        + Money.naira(saved.transactionFee()) + ", paid out " + Money.naira(saved.netDisbursed()) + ".",
                saved.getId(),
                "Imported loan " + saved.getId());

        // The loan goes on a real account at once: the customer's own if they
        // already have one, otherwise one opened for them now, which they
        // activate by signing up with this BVN. Either way they are a customer
        // like any other from this moment — schedule, reminders, the loan book.
        User owner = existing != null ? existing : openForCustomer(saved, actor, now);
        claim(List.of(saved), owner, now);

        for (OfflineLoanDtos.OfflineRepaymentRequest p : payments) {
            RecordedRepayment r = new RecordedRepayment();
            r.setId(UUID.randomUUID());
            r.setImportedLoanId(saved.getId());
            r.setLoanId(saved.getLoanId());
            r.setAmount(Money.of(p.amount()));
            r.setPaidAt(p.paidAt());
            r.setMethod(p.method());
            r.setReference(p.reference() == null || p.reference().isBlank() ? null : p.reference().trim());
            r.setNote(p.note() == null || p.note().isBlank() ? null : p.note().trim());
            r.setRecordedBy(actor.getName());
            r.setRecordedAt(now);
            r.setEnteredWithLoan(true);
            recorded.save(r);
        }
        // A loan its repayments cleared closed on the day of the last one.
        if (lastPaid != null) {
            Instant closedOn = lastPaid;
            loans.findById(saved.getLoanId())
                    .filter(l -> l.getStatus() == LoanStatus.REPAID)
                    .ifPresent(l -> {
                        l.setSettledAt(closedOn);
                        loans.save(l);
                    });
        }

        return saved;
    }

    /**
     * Whether a BVN already belongs to a customer, so the panel can put
     * another loan on their account rather than open a second one.
     */
    @Transactional(readOnly = true)
    public LoanImportDtos.BvnMatch lookup(String bvn) {
        access.requireCanView();
        if (bvn == null || !bvn.trim().matches("\\d{11}")) {
            return LoanImportDtos.BvnMatch.none();
        }
        return users.findByBvn(bvn.trim())
                .map(u -> new LoanImportDtos.BvnMatch(
                        true,
                        u.getId(),
                        u.getFullName(),
                        u.getCustomerRef(),
                        u.getAccountStatus().label(),
                        !loans.findByUserIdAndStatusIn(u.getId(), List.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE)).isEmpty()))
                .orElse(LoanImportDtos.BvnMatch.none());
    }

    /** An entry as the panel shows it, with the live loan's figures once it is on an account. */
    @Transactional(readOnly = true)
    public LoanImportDtos.ImportedLoanResponse view(ImportedLoan row) {
        Loan live = row.getLoanId() == null ? null : loans.findById(row.getLoanId()).orElse(null);
        User owner = row.getUserId() == null ? null : users.findById(row.getUserId()).orElse(null);
        return LoanImportDtos.ImportedLoanResponse.from(row, live, owner);
    }

    @Transactional(readOnly = true)
    public List<ImportedLoan> list(Boolean claimed) {
        access.requireCanView();
        if (claimed == null) {
            return imports.findAllByOrderByImportedAtDesc();
        }
        return claimed
                ? imports.findByClaimedAtIsNotNullOrderByClaimedAtDesc()
                : imports.findByClaimedAtIsNullOrderByImportedAtDesc();
    }

    /** Removes an unclaimed row. A claimed one is a loan now, and stays. */
    @Transactional
    public void delete(UUID id) {
        AdminUser actor = access.requireCanActOnLoans();
        ImportedLoan row = imports.findById(id)
                .orElseThrow(() -> ApiException.notFound("That imported loan"));

        String who = row.getFullName();
        String amount = Money.naira(row.getPrincipal());
        boolean accountRemoved = false;
        if (row.isClaimed()) {
            // On an account the customer has not activated yet, and untouched
            // since: the whole entry can still be taken back.
            accountRemoved = removal.remove(row);
        } else {
            imports.delete(row);
        }

        audit.record(
                actorOf(actor),
                AuditCategory.LOAN,
                "Loan entry removed",
                actor.getName() + " removed the " + amount + " loan entered for " + who + " by mistake"
                        + (accountRemoved ? ", and the not-yet-activated account opened for it." : "."),
                id,
                "Imported loan " + id);
    }

    // ── Signup ─────────────────────────────────────────────────────────────

    /**
     * Attaches whatever was entered against this customer's BVN.
     *
     * <p>Called as the account opens, inside the same transaction: an account
     * that exists without its loans, even for a moment, is a screen that says
     * "nothing owed" to somebody who owes.
     *
     * @return how many loans landed on the account
     */
    @Transactional
    public int claimFor(User user) {
        if (user.getBvn() == null) {
            return 0;
        }
        List<ImportedLoan> waiting = imports.findByBvnAndClaimedAtIsNullOrderByDisbursedAtAsc(user.getBvn());
        if (!waiting.isEmpty()) {
            claim(waiting, user, Instant.now());
        }

        // A row that names this email under a different BVN is most likely a
        // BVN typed wrong by the admin. It will never match on its own, so say
        // so where an admin will look.
        List<ImportedLoan> strays = imports.findByEmailIgnoreCaseAndClaimedAtIsNull(user.getEmail());
        if (!strays.isEmpty()) {
            audit.recordSystem(
                    AuditCategory.LOAN,
                    "Paper loan did not match at signup",
                    strays.size() + " imported loan(s) name " + user.getEmail() + ", who has just "
                            + "signed up as " + user.getCustomerRef() + ", but were entered against a "
                            + "different BVN. Check the BVN on those rows.");
            log.warn("{} imported loan(s) name {} but carry a different BVN", strays.size(), user.getEmail());
        }
        return waiting.size();
    }

    /**
     * Loans entered by hand before accounts were opened for them: each is put
     * on its borrower's account, opening one where needed. Safe to run any
     * number of times; a row that cannot be placed is reported and left.
     *
     * @return how many were placed
     */
    @Transactional
    public int adoptWaiting() {
        int placed = 0;
        Instant now = Instant.now();
        for (ImportedLoan row : imports.findByClaimedAtIsNullOrderByImportedAtDesc()) {
            try {
                User owner = users.findByBvn(row.getBvn()).orElseGet(() -> openForCustomer(row, null, now));
                claim(List.of(row), owner, now);
                placed++;
            } catch (ApiException e) {
                log.warn("Could not open an account for imported loan {}: {}", row.getId(), e.getMessage());
                audit.recordSystem(AuditCategory.LOAN, "Loan customer not placed on an account",
                        "The loan entered for " + row.getFullName() + " could not be put on an account: "
                                + e.getMessage() + " Correct the details on the loan file.");
            }
        }
        return placed;
    }

    // ── Internals ──────────────────────────────────────────────────────────

    /**
     * Opens an account for a borrower the team entered: a real customer with a
     * customer number and a wallet, marked not yet activated. Nobody can sign in
     * to it; the borrower takes it over by signing up with the same BVN.
     */
    private User openForCustomer(ImportedLoan row, AdminUser actor, Instant now) {
        String email = row.getEmail() == null || row.getEmail().isBlank() ? null : row.getEmail().trim().toLowerCase(Locale.ROOT);
        if (email != null && users.existsByEmailIgnoreCase(email)) {
            throw new ApiException(ErrorCode.EMAIL_TAKEN,
                    "That email address already belongs to another customer. Leave it blank or correct it.");
        }
        String phone = row.getPhone() == null || row.getPhone().isBlank() ? null : row.getPhone().trim();
        if (phone != null && users.existsByPhone(phone)) {
            throw new ApiException(ErrorCode.PHONE_TAKEN,
                    "That phone number already belongs to another customer. Leave it blank or correct it.");
        }
        String nin = row.getNin() == null || row.getNin().isBlank() ? null : row.getNin().trim();
        if (nin != null && users.existsByNin(nin)) {
            throw new ApiException(ErrorCode.NIN_TAKEN, "That NIN already belongs to another customer. Check it.");
        }

        User user = User.createWithNewId();
        user.setFullName(row.getFullName());
        user.setEmail(email);
        user.setPhone(phone);
        user.setDateOfBirth(row.getDateOfBirth());
        user.setGender(row.getGender());
        user.setBvn(row.getBvn());
        user.setNin(nin);
        user.setAddress(row.getAddress());
        user.setState(row.getState());
        user.setPayoutBank(row.getPayoutBank());
        user.setPayoutAccountNumber(row.getPayoutAccountNumber());
        // Nobody can sign in until the customer signs up and sets their own.
        user.setPasswordHash(NOT_ACTIVATED);
        user.setKycTier(KycTier.TIER0);
        user.setThemeMode(ThemeMode.DARK);
        user.setAccountStatus(AccountStatus.PENDING_ACTIVATION);
        user.setStatusNote("Opened by " + (actor == null ? "the team" : actor.getName())
                + " for a loan given outside the app. Activated when the customer signs up with this BVN.");
        user.setCreatedAt(now);
        User saved = users.save(user);
        ledger.openWallet(saved.getId());

        audit.record(actor == null ? AuditService.Actor.system() : actorOf(actor),
                AuditCategory.CUSTOMER,
                "Account opened for a loan customer",
                "Account " + saved.getCustomerRef() + " opened for " + saved.getFullName()
                        + ". Not activated until they sign up with their BVN.",
                saved.getId(),
                saved.getCustomerRef());
        log.info("Opened not-yet-activated account {} for an imported loan", saved.getCustomerRef());
        return saved;
    }

    /** Not a hash of anything: no password can ever match it. */
    private static final String NOT_ACTIVATED = "!not-activated";

    private void claim(List<ImportedLoan> rows, User user, Instant now) {
        BigDecimal owed = Money.zero();
        for (ImportedLoan row : rows) {
            Loan loan = loans.save(row.toLoan(user.getId(), now));
            row.claimedBy(user.getId(), loan.getId(), now);
            imports.save(row);
            officers.moveToAccount(row.getId(), user.getId());
            groups.attachAccount(row.getId(), user.getId());
            owed = Money.add(owed, loan.outstanding());
            log.info("Imported loan {} became loan {} on {}", row.getId(), loan.getId(), user.getCustomerRef());
        }

        audit.recordSystem(
                AuditCategory.LOAN,
                "Paper loan(s) attached to account",
                rows.size() + " imported loan(s) with " + Money.naira(owed)
                        + " outstanding attached to " + user.getFullName()
                        + " (" + user.getCustomerRef() + ").");

        notifications.push(
                user.getId(),
                NotifyKind.GENERAL,
                rows.size() == 1 ? "Your loan is here" : "Your loans are here",
                (rows.size() == 1
                        ? "The loan you took with us before the app is on your account. "
                        : "The " + rows.size() + " loans you took with us before the app are on your account. ")
                        + (Money.isPositive(owed)
                                ? Money.naira(owed) + " is outstanding. You can see the schedule and repay "
                                        + "from your wallet in the app."
                                : "Nothing is owed on them."),
                owed);
    }

    private AuditService.Actor actorOf(AdminUser admin) {
        return new AuditService.Actor(admin.getUserId(), admin.getName(), admin.getEmail());
    }
}
