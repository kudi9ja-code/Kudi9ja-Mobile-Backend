package com.quadrilateral.kudi9ja.domain.admin;

import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditEntry;
import com.quadrilateral.kudi9ja.domain.audit.AuditRepository;
import com.quadrilateral.kudi9ja.domain.loan.ImportedLoan;
import com.quadrilateral.kudi9ja.domain.loan.ImportedLoanRepository;
import com.quadrilateral.kudi9ja.domain.loan.Loan;
import com.quadrilateral.kudi9ja.domain.loan.LoanRepository;
import com.quadrilateral.kudi9ja.domain.loan.LoanStatus;
import com.quadrilateral.kudi9ja.domain.payin.PayInService;
import com.quadrilateral.kudi9ja.domain.payout.WithdrawalService;
import com.quadrilateral.kudi9ja.domain.savings.SavingsPlanRepository;
import com.quadrilateral.kudi9ja.domain.savings.SavingsStatus;
import com.quadrilateral.kudi9ja.domain.user.AccountStatus;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.domain.wallet.WalletRepository;
import com.quadrilateral.kudi9ja.web.dto.AdminDtos;
import com.quadrilateral.kudi9ja.web.dto.RepaymentDtos;
import java.time.Duration;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The panel's front page: what is waiting, what the book looks like, and who
 * is on it.
 *
 * <p>Two things about this screen are deliberate.
 *
 * <p>First, <b>customer funds held leads</b>. It is the total of every wallet
 * balance, which is what the collection account should be holding on the
 * customers' behalf. A divergence between that figure and the bank statement
 * is either a pay-in nobody confirmed or a defect in the ledger, and both need
 * finding the same day.
 *
 * <p>Second, the queues report <b>how many have outrun the promise</b>
 * separately from how many are waiting. The documents commit to reviewing a
 * pay-in and a withdrawal within one working day; a queue of forty is fine if
 * they all arrived this morning and is a problem if four of them are a week
 * old, and one number cannot say which.
 */
@Service
public class AdminOverviewService {

    /** The review promise the documents make, in the form this screen needs it. */
    private static final Duration REVIEW_SLA = Duration.ofHours(24);

    private static final Pageable RECENT_ACTIVITY = PageRequest.of(0, 12);

    private final AdminAccessService access;
    private final WalletRepository wallets;
    private final UserRepository users;
    private final AdminUserRepository admins;
    private final SavingsPlanRepository plans;
    private final LoanRepository loans;
    private final PayInService payIns;
    private final WithdrawalService withdrawals;
    private final AuditRepository auditEntries;
    private final ImportedLoanRepository imports;
    private final RepaymentHistoryService repayments;

    public AdminOverviewService(
            AdminAccessService access,
            WalletRepository wallets,
            UserRepository users,
            AdminUserRepository admins,
            SavingsPlanRepository plans,
            LoanRepository loans,
            PayInService payIns,
            WithdrawalService withdrawals,
            AuditRepository auditEntries,
            ImportedLoanRepository imports,
            RepaymentHistoryService repayments) {
        this.access = access;
        this.wallets = wallets;
        this.users = users;
        this.admins = admins;
        this.plans = plans;
        this.loans = loans;
        this.payIns = payIns;
        this.withdrawals = withdrawals;
        this.auditEntries = auditEntries;
        this.imports = imports;
        this.repayments = repayments;
    }

    @Transactional(readOnly = true)
    public AdminDtos.OverviewResponse overview() {
        access.requireCanView();

        Instant now = Instant.now();
        Instant slaCutoff = now.minus(REVIEW_SLA);
        Instant weekAgo = now.minus(Duration.ofDays(7));

        return new AdminDtos.OverviewResponse(
                Money.of(wallets.totalHeld()),
                queues(slaCutoff),
                book(),
                people(weekAgo),
                recentActivity(),
                now);
    }

    private AdminDtos.Queues queues(Instant slaCutoff) {
        return new AdminDtos.Queues(
                payIns.pendingCount(),
                Money.of(payIns.pendingValue()),
                withdrawals.pendingCount(),
                Money.of(withdrawals.pendingValue()),
                payIns.unmatchedHeldCount(),
                payIns.pendingSince(slaCutoff).size(),
                withdrawals.pendingSince(slaCutoff).size());
    }

    private AdminDtos.Book book() {
        Instant now = Instant.now();
        PaperBook paper = paperBook(now);
        List<BigDecimal> behind = loans.findAllOpen().stream()
                .map(l -> l.pastDue(now))
                .filter(Money::isPositive)
                .toList();
        RepaymentDtos.Summary repaid = repayments.allTime();
        return new AdminDtos.Book(
                Money.of(plans.totalSavedAcrossBook()),
                Money.of(plans.totalInterestPaidAcrossBook()),
                Money.add(Money.of(loans.totalLentAcrossBook()), paper.lent()),
                Money.add(Money.of(loans.totalInterestChargedAcrossBook()), paper.interest()),
                Money.add(Money.of(loans.totalFeesChargedAcrossBook()), paper.fees()),
                // Every missed instalment, not only on loans past their last day.
                Money.add(behind.stream().reduce(Money.zero(), Money::add), paper.overdue()),
                behind.size() + paper.behind(),
                Money.add(loans.findAllOpen().stream().map(Loan::outstanding).reduce(Money.zero(), Money::add), paper.outstanding()),
                plans.countByStatus(SavingsStatus.ACTIVE),
                plans.countByStatus(SavingsStatus.MATURED),
                loans.countByStatus(LoanStatus.ACTIVE) + paper.active(),
                loans.countByStatus(LoanStatus.OVERDUE) + paper.overdueCount(),
                loans.countByStatus(LoanStatus.REPAID) + paper.repaid(),
                repaid.total(),
                repaid.count(),
                repaid.thisMonth());
    }

    /**
     * Loans entered by hand whose borrower has not signed up yet. They are
     * real loans the company is owed, so the book counts them the same way it
     * counts loans on accounts: every one for lent, interest and fees; open
     * ones for missed instalments; cleared ones as repaid. Once the borrower signs up the row
     * becomes a loan on their account and is counted there instead, never twice.
     */
    private PaperBook paperBook(Instant now) {
        BigDecimal lent = Money.zero();
        BigDecimal interest = Money.zero();
        BigDecimal fees = Money.zero();
        BigDecimal overdue = Money.zero();
        BigDecimal outstanding = Money.zero();
        long behind = 0;
        long active = 0;
        long overdueCount = 0;
        long repaid = 0;
        for (ImportedLoan row : imports.findByClaimedAtIsNullOrderByImportedAtDesc()) {
            lent = Money.add(lent, row.getPrincipal());
            interest = Money.add(interest, Money.of(Money.calc(row.getPrincipal()).multiply(row.getFlatRate())));
            fees = Money.add(fees, Money.add(row.getProcessingFee(), row.transactionFee()));
            if (Money.isZeroOrLess(row.outstanding())) {
                repaid++;
                continue;
            }
            outstanding = Money.add(outstanding, row.outstanding());
            BigDecimal missed = row.pastDue(now);
            if (Money.isPositive(missed)) {
                behind++;
                overdue = Money.add(overdue, missed);
            }
            if (row.dueDate() != null && now.isAfter(row.dueDate())) {
                overdueCount++;
            } else {
                active++;
            }
        }
        return new PaperBook(lent, interest, fees, overdue, outstanding, behind, active, overdueCount, repaid);
    }

    private record PaperBook(
            BigDecimal lent, BigDecimal interest, BigDecimal fees, BigDecimal overdue, BigDecimal outstanding,
            long behind, long active, long overdueCount, long repaid) {
    }

    private AdminDtos.People people(Instant weekAgo) {
        return new AdminDtos.People(
                users.count(),
                users.countByAccountStatus(AccountStatus.ACTIVE),
                users.countByAccountStatus(AccountStatus.FROZEN),
                users.countByAccountStatus(AccountStatus.DORMANT),
                users.countByCreatedAtAfter(weekAgo),
                admins.findAllByOrderByRoleAscNameAsc().stream().filter(AdminUser::isActive).count());
    }

    /**
     * The last dozen entries, whoever made them.
     *
     * <p>Unfiltered on purpose. Every admin can read the audit log — being
     * watched is the point of keeping one, and a panel that hid part of the
     * record from part of the team would defeat it.
     */
    private List<AdminDtos.AuditRow> recentActivity() {
        return auditEntries
                .search((AuditCategory) null, null, null, null, null, RECENT_ACTIVITY)
                .getContent()
                .stream()
                .map(AdminDtos.AuditRow::from)
                .toList();
    }

    /** The audit log proper, filterable, for the panel's own screen. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<AuditEntry> audit(
            AuditCategory category,
            java.util.UUID subjectId,
            Instant from,
            Instant to,
            String query,
            Pageable pageable) {

        access.requireCanView();
        return auditEntries.search(
                category,
                subjectId,
                from,
                to,
                query == null || query.isBlank() ? null : query.trim(),
                pageable);
    }
}
