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
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.web.dto.LoanImportDtos;
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

    public LoanImportService(
            ImportedLoanRepository imports,
            LoanRepository loans,
            UserRepository users,
            AdminAccessService access,
            AuditService audit,
            NotificationService notifications) {
        this.imports = imports;
        this.loans = loans;
        this.users = users;
        this.access = access;
        this.audit = audit;
        this.notifications = notifications;
    }

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
        AdminUser actor = access.requireCanActOnLoans();
        Instant now = Instant.now();

        BigDecimal principal = Money.of(request.principal());
        BigDecimal fee = request.processingFee() == null ? Money.zero() : Money.of(request.processingFee());
        BigDecimal repaid = request.amountRepaid() == null ? Money.zero() : Money.of(request.amountRepaid());

        if (request.disbursedAt().isAfter(now)) {
            throw ApiException.validation("The disbursement date is in the future.");
        }
        if (Money.gte(fee, principal)) {
            throw ApiException.validation("The processing fee cannot be as much as the loan itself.");
        }

        ImportedLoan row = new ImportedLoan();
        row.setId(UUID.randomUUID());
        row.setBvn(request.bvn().trim());
        row.setFullName(request.fullName().trim());
        row.setEmail(request.email() == null || request.email().isBlank()
                ? null : request.email().trim().toLowerCase(Locale.ROOT));
        row.setPhone(request.phone() == null || request.phone().isBlank() ? null : request.phone().trim());
        row.setPrincipal(principal);
        row.setTenureMonths(request.tenureMonths());
        row.setFlatRate(request.flatRate());
        row.setProcessingFee(fee);
        row.setPurpose(request.purpose().trim());
        row.setDisbursedAt(request.disbursedAt());
        row.setAmountRepaid(repaid);
        row.setImportedBy(actor.getName());
        row.setImportedAt(now);

        if (Money.gt(repaid, row.totalRepayable())) {
            throw ApiException.validation("The amount repaid, " + Money.naira(repaid)
                    + ", is more than the " + Money.naira(row.totalRepayable())
                    + " that was ever owed on this loan.");
        }

        ImportedLoan saved = imports.save(row);

        audit.record(
                actorOf(actor),
                AuditCategory.LOAN,
                "Paper loan entered",
                actor.getName() + " entered a " + Money.naira(saved.getPrincipal()) + " loan over "
                        + saved.getTenureMonths() + " months for " + saved.getFullName()
                        + " (BVN ending " + saved.getBvn().substring(7) + "), disbursed "
                        + DAY.format(saved.getDisbursedAt()) + ", with "
                        + Money.naira(saved.getAmountRepaid()) + " already repaid.",
                saved.getId(),
                "Imported loan " + saved.getId());

        // The customer may have beaten the admin to it. Attach at once rather
        // than leaving a row waiting for a signup that has already happened.
        users.findByBvn(saved.getBvn()).ifPresent(user -> claim(List.of(saved), user, now));

        return saved;
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

        if (row.isClaimed()) {
            throw new ApiException(
                    ErrorCode.CONFLICT,
                    "That loan is already on the customer's account. Close it there by repayment "
                            + "or a write-off; it cannot be un-entered.");
        }

        imports.delete(row);

        audit.record(
                actorOf(actor),
                AuditCategory.LOAN,
                "Paper loan removed before claim",
                actor.getName() + " removed the unclaimed " + Money.naira(row.getPrincipal())
                        + " loan entered for " + row.getFullName() + " (BVN ending "
                        + row.getBvn().substring(7) + ").",
                row.getId(),
                "Imported loan " + row.getId());
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

    // ── Internals ──────────────────────────────────────────────────────────

    private void claim(List<ImportedLoan> rows, User user, Instant now) {
        BigDecimal owed = Money.zero();
        for (ImportedLoan row : rows) {
            Loan loan = loans.save(row.toLoan(user.getId(), now));
            row.claimedBy(user.getId(), loan.getId(), now);
            imports.save(row);
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
