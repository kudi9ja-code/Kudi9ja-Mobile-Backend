package com.quadrilateral.kudi9ja.domain.admin;

import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditEntry;
import com.quadrilateral.kudi9ja.domain.audit.AuditRepository;
import com.quadrilateral.kudi9ja.domain.loan.ImportedLoan;
import com.quadrilateral.kudi9ja.domain.loan.ImportedLoanRepository;
import com.quadrilateral.kudi9ja.domain.loan.Installment;
import com.quadrilateral.kudi9ja.domain.loan.InstallmentStatus;
import com.quadrilateral.kudi9ja.domain.loan.Loan;
import com.quadrilateral.kudi9ja.domain.loan.LoanRepository;
import com.quadrilateral.kudi9ja.domain.loan.LoanStatus;
import com.quadrilateral.kudi9ja.domain.loan.RepaymentFrequency;
import com.quadrilateral.kudi9ja.domain.officer.LoanOfficerService;
import com.quadrilateral.kudi9ja.domain.payin.PayInService;
import com.quadrilateral.kudi9ja.domain.payout.WithdrawalService;
import com.quadrilateral.kudi9ja.domain.savings.SavingsPlanRepository;
import com.quadrilateral.kudi9ja.domain.savings.SavingsStatus;
import com.quadrilateral.kudi9ja.domain.user.AccountStatus;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.domain.wallet.Wallet;
import com.quadrilateral.kudi9ja.domain.wallet.WalletRepository;
import com.quadrilateral.kudi9ja.web.dto.AdminDtos;
import com.quadrilateral.kudi9ja.web.dto.RepaymentDtos;
import java.time.Duration;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    private final LoanOfficerService officers;

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
            RepaymentHistoryService repayments,
            LoanOfficerService officers) {
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
        this.officers = officers;
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

    /** Loans that were paid out: the ones the book counts. */
    private static final Set<LoanStatus> BOOKED =
            EnumSet.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE, LoanStatus.REPAID, LoanStatus.WRITTEN_OFF);

    /**
     * The book's figures, added up from the same rows the tiles open, so a
     * tile and its list can never disagree. Loans entered by hand whose
     * borrower has not signed up yet count the same as loans on accounts;
     * once claimed, a row becomes a loan on the account and is counted there
     * instead, never twice.
     */
    private AdminDtos.Book book() {
        List<AdminDtos.BookLoanRow> rows = bookRows(Instant.now());
        RepaymentDtos.Summary repaid = repayments.allTime();
        List<AdminDtos.BookLoanRow> open = rows.stream().filter(AdminDtos.BookLoanRow::open).toList();
        List<AdminDtos.BookLoanRow> behind = open.stream().filter(r -> Money.isPositive(r.pastDue())).toList();
        return new AdminDtos.Book(
                Money.of(plans.totalSavedAcrossBook()),
                Money.of(plans.totalInterestPaidAcrossBook()),
                sum(rows, AdminDtos.BookLoanRow::principal),
                sum(rows, AdminDtos.BookLoanRow::interest),
                sum(rows, AdminDtos.BookLoanRow::fees),
                // Every missed instalment, not only on loans past their last day.
                sum(behind, AdminDtos.BookLoanRow::pastDue),
                behind.size(),
                sum(open, AdminDtos.BookLoanRow::outstanding),
                plans.countByStatus(SavingsStatus.ACTIVE),
                plans.countByStatus(SavingsStatus.MATURED),
                rows.stream().filter(r -> r.status().equals(LoanStatus.ACTIVE.name())).count(),
                rows.stream().filter(r -> r.status().equals(LoanStatus.OVERDUE.name())).count(),
                rows.stream().filter(r -> r.status().equals(LoanStatus.REPAID.name())).count(),
                repaid.total(),
                repaid.count(),
                repaid.thisMonth());
    }

    private static BigDecimal sum(List<AdminDtos.BookLoanRow> rows, Function<AdminDtos.BookLoanRow, BigDecimal> field) {
        return rows.stream().map(field).reduce(Money.zero(), Money::add);
    }

    /** The rows behind the Overview tiles: every loan paid out, and every wallet holding money. */
    @Transactional(readOnly = true)
    public AdminDtos.BookDetail bookDetail() {
        access.requireCanView();
        Instant now = Instant.now();
        List<Wallet> held = wallets.findAll().stream()
                .filter(w -> Money.isPositive(w.getBalance()))
                .toList();
        Map<UUID, User> holders = byId(held.stream().map(Wallet::getUserId).collect(Collectors.toSet()));
        List<AdminDtos.WalletHolderRow> walletRows = held.stream()
                .map(w -> {
                    User u = holders.get(w.getUserId());
                    return new AdminDtos.WalletHolderRow(
                            w.getUserId(),
                            u == null ? "Unknown" : u.getFullName(),
                            u == null ? "" : u.getCustomerRef(),
                            u == null ? null : u.getPhone(),
                            u == null || u.getAccountStatus() == null ? null : u.getAccountStatus().name(),
                            Money.of(w.getBalance()),
                            w.getUpdatedAt());
                })
                .sorted(Comparator.comparing(AdminDtos.WalletHolderRow::balance).reversed())
                .toList();
        return new AdminDtos.BookDetail(bookRows(now), walletRows, now);
    }

    private Map<UUID, User> byId(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private List<AdminDtos.BookLoanRow> bookRows(Instant now) {
        List<Loan> onAccounts = loans.findAllByOrderByRequestedAtDesc().stream()
                .filter(l -> BOOKED.contains(l.getStatus()))
                .toList();
        List<ImportedLoan> onPaper = imports.findByClaimedAtIsNullOrderByImportedAtDesc();

        Set<UUID> customerIds = onAccounts.stream().map(Loan::getUserId).collect(Collectors.toSet());
        Map<UUID, User> borrowers = byId(customerIds);
        Map<UUID, List<String>> accountOfficers = officers.namesByCustomer(customerIds);
        Map<UUID, List<String>> paperOfficers =
                officers.namesByImport(onPaper.stream().map(ImportedLoan::getId).collect(Collectors.toSet()));

        List<AdminDtos.BookLoanRow> rows = new ArrayList<>(onAccounts.size() + onPaper.size());
        for (Loan loan : onAccounts) {
            User u = borrowers.get(loan.getUserId());
            rows.add(row(
                    "ACCOUNT", loan.getId(), loan.getUserId(),
                    u == null ? "Unknown" : u.getFullName(),
                    u == null ? "" : u.getCustomerRef(),
                    u == null ? null : u.getPhone(),
                    accountOfficers.getOrDefault(loan.getUserId(), List.of()),
                    loan.getPrincipal(), loan.getFlatRate(), Money.of(loan.getProcessingFee()),
                    loan.transactionFee(), loan.repaymentFees(),
                    loan.totalRepayable(), loan.getAmountRepaid(),
                    loan.getStatus().isOpen() ? loan.outstanding() : Money.zero(),
                    loan.schedule(now), loan.getStatus(), loan.frequency(), loan.getTenureMonths(),
                    loan.getPurpose(), loan.getDisbursedAt(), loan.getDueDate(), now));
        }
        for (ImportedLoan paper : onPaper) {
            BigDecimal outstanding = paper.outstanding();
            Instant due = paper.dueDate();
            LoanStatus status = Money.isZeroOrLess(outstanding)
                    ? LoanStatus.REPAID
                    : due != null && now.isAfter(due) ? LoanStatus.OVERDUE : LoanStatus.ACTIVE;
            rows.add(row(
                    "OFFLINE", paper.getId(), null,
                    paper.getFullName(), "", paper.getPhone(),
                    paperOfficers.getOrDefault(paper.getId(), List.of()),
                    paper.getPrincipal(), paper.getFlatRate(), Money.of(paper.getProcessingFee()),
                    paper.transactionFee(), paper.repaymentFees(),
                    paper.totalRepayable(), paper.getAmountRepaid(), outstanding,
                    paper.schedule(now), status, RepaymentFrequency.orMonthly(paper.getRepaymentFrequency()),
                    paper.getTenureMonths(), paper.getPurpose(), paper.getDisbursedAt(), due, now));
        }
        return rows;
    }

    private static AdminDtos.BookLoanRow row(
            String source, UUID id, UUID customerId, String name, String ref, String phone, List<String> officerNames,
            BigDecimal principal, BigDecimal flatRate, BigDecimal management, BigDecimal flat, BigDecimal perRepayment,
            BigDecimal totalRepayable, BigDecimal repaid, BigDecimal outstanding, List<Installment> schedule,
            LoanStatus status, RepaymentFrequency frequency, int tenureMonths,
            String purpose, Instant disbursedAt, Instant dueDate, Instant now) {
        boolean open = status.isOpen();
        List<Installment> missed = open
                ? schedule.stream().filter(i -> i.status() == InstallmentStatus.OVERDUE).toList()
                : List.of();
        BigDecimal pastDue = missed.stream().map(Installment::outstanding).reduce(Money.zero(), Money::add);
        Instant oldest = missed.isEmpty() ? null : missed.get(0).dueDate();
        Installment next = open
                ? schedule.stream().filter(i -> i.status() != InstallmentStatus.PAID).findFirst().orElse(null)
                : null;
        BigDecimal rate = flatRate == null ? BigDecimal.ZERO : flatRate;
        BigDecimal fees = Money.add(Money.add(management, flat), perRepayment);
        return new AdminDtos.BookLoanRow(
                source, id, customerId, name, ref, phone, officerNames,
                Money.of(principal), rate,
                Money.of(Money.calc(principal).multiply(rate)),
                management, flat, perRepayment, fees,
                Money.of(totalRepayable), Money.of(repaid == null ? Money.zero() : repaid), outstanding,
                pastDue, missed.size(), oldest,
                oldest == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(oldest, now)),
                schedule.size(),
                (int) schedule.stream().filter(i -> i.status() == InstallmentStatus.PAID).count(),
                next == null ? null : next.dueDate(),
                next == null ? null : next.outstanding(),
                open, status.name(), status.label(), frequency, tenureMonths, purpose, disbursedAt, dueDate);
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
