package com.quadrilateral.kudi9ja.domain.admin;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.util.Dates;
import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.group.LoanGroup;
import com.quadrilateral.kudi9ja.domain.group.LoanGroupMember;
import com.quadrilateral.kudi9ja.domain.group.LoanGroupMemberRepository;
import com.quadrilateral.kudi9ja.domain.group.LoanGroupRepository;
import com.quadrilateral.kudi9ja.domain.loan.Loan;
import com.quadrilateral.kudi9ja.domain.loan.LoanRepository;
import com.quadrilateral.kudi9ja.domain.loan.RecordedRepayment;
import com.quadrilateral.kudi9ja.domain.loan.RecordedRepaymentRepository;
import com.quadrilateral.kudi9ja.domain.loanapplication.LoanApplication;
import com.quadrilateral.kudi9ja.domain.loanapplication.LoanApplicationRepository;
import com.quadrilateral.kudi9ja.domain.officer.LoanOfficerAssignment;
import com.quadrilateral.kudi9ja.domain.officer.LoanOfficerAssignmentRepository;
import com.quadrilateral.kudi9ja.domain.payin.PaystackPayment;
import com.quadrilateral.kudi9ja.domain.payin.PaystackPaymentRepository;
import com.quadrilateral.kudi9ja.domain.payout.WithdrawalRequest;
import com.quadrilateral.kudi9ja.domain.payout.WithdrawalRequestRepository;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.domain.wallet.TxKind;
import com.quadrilateral.kudi9ja.domain.wallet.Wallet;
import com.quadrilateral.kudi9ja.domain.wallet.WalletRepository;
import com.quadrilateral.kudi9ja.domain.wallet.WalletTransaction;
import com.quadrilateral.kudi9ja.domain.wallet.WalletTransactionRepository;
import com.quadrilateral.kudi9ja.domain.wallet.LedgerService;
import com.quadrilateral.kudi9ja.config.Kudi9jaProperties;
import com.quadrilateral.kudi9ja.web.dto.ExportDtos;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reports an administrator can download: customers, loans, repayments,
 * the wallet ledger, Paystack payments, withdrawals and applications.
 *
 * <p>The server gathers the rows; the panel turns them into Excel or PDF. Every
 * download is a bulk copy of customers' personal data leaving the system, so
 * it is limited to owners and administrators and written to the audit log with
 * who took it, which report, the period and how many rows.
 */
@Service
public class ExportService {

    /** Which report. The path segment is the lower-case name: {@code /exports/repayments}. */
    public enum Report {
        CUSTOMERS("Customers"),
        LOANS("Loans"),
        REPAYMENTS("Loan repayments"),
        TRANSACTIONS("Wallet transactions"),
        PAYSTACK("Paystack payments"),
        WITHDRAWALS("Withdrawals"),
        APPLICATIONS("Loan applications");

        private final String title;

        Report(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }

        public static Report of(String name) {
            return Arrays.stream(values())
                    .filter(r -> r.name().equalsIgnoreCase(name))
                    .findFirst()
                    .orElseThrow(() -> ApiException.notFound("That report"));
        }
    }

    private final AdminAccessService access;
    private final AuditService audit;
    private final UserRepository users;
    private final WalletRepository wallets;
    private final LoanRepository loans;
    private final WalletTransactionRepository ledger;
    private final RecordedRepaymentRepository recorded;
    private final PaystackPaymentRepository paystack;
    private final WithdrawalRequestRepository withdrawals;
    private final LoanApplicationRepository applications;
    private final LoanGroupMemberRepository groupMembers;
    private final LoanGroupRepository groups;
    private final LoanOfficerAssignmentRepository officers;

    public ExportService(
            AdminAccessService access,
            AuditService audit,
            UserRepository users,
            WalletRepository wallets,
            LoanRepository loans,
            WalletTransactionRepository ledger,
            RecordedRepaymentRepository recorded,
            PaystackPaymentRepository paystack,
            WithdrawalRequestRepository withdrawals,
            LoanApplicationRepository applications,
            LoanGroupMemberRepository groupMembers,
            LoanGroupRepository groups,
            LoanOfficerAssignmentRepository officers,
            Kudi9jaProperties properties) {
        this.access = access;
        this.audit = audit;
        this.users = users;
        this.wallets = wallets;
        this.loans = loans;
        this.ledger = ledger;
        this.recorded = recorded;
        this.paystack = paystack;
        this.withdrawals = withdrawals;
        this.applications = applications;
        this.groupMembers = groupMembers;
        this.groups = groups;
        this.officers = officers;
        this.properties = properties;
    }

    private final Kudi9jaProperties properties;

    /**
     * One report, for the period {@code from}–{@code to} (both inclusive, Lagos
     * dates; either may be left out). Customers are filtered by the day they
     * joined, everything else by the day it happened.
     */
    @Transactional
    public ExportDtos.Report export(String name, LocalDate from, LocalDate to) {
        AdminUser actor = access.requireCanExport();
        Report report = Report.of(name);
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.validation("The start date is after the end date.");
        }
        Period period = new Period(from, to);

        ExportDtos.Report out = switch (report) {
            case CUSTOMERS -> customers(period);
            case LOANS -> loans(period);
            case REPAYMENTS -> repayments(period);
            case TRANSACTIONS -> transactions(period);
            case PAYSTACK -> paystackPayments(period);
            case WITHDRAWALS -> withdrawals(period);
            case APPLICATIONS -> applications(period);
        };

        audit.record(
                new AuditService.Actor(actor.getUserId(), actor.getName(), actor.getEmail()),
                AuditCategory.DATA_ACCESS,
                "Report downloaded",
                actor.getName() + " downloaded " + report.title() + " (" + period.label() + "), "
                        + out.rows().size() + " rows.");
        return out.stamped(report.title(), period.label(), actor.getName(),
                properties.company().legalName() + " · " + properties.company().rcNumber());
    }

    // ── The reports ───────────────────────────────────────────────────────

    private ExportDtos.Report customers(Period period) {
        Map<UUID, BigDecimal> balances = wallets.findAll().stream()
                .collect(Collectors.toMap(Wallet::getUserId, Wallet::getBalance, (a, b) -> a));
        Map<UUID, String> groupLabels = groupLabels();
        Map<UUID, String> leads = officers.findAll().stream()
                .filter(a -> a.getUserId() != null && a.isLead())
                .collect(Collectors.toMap(LoanOfficerAssignment::getUserId, LoanOfficerAssignment::getOfficerName, (a, b) -> a));

        List<List<Object>> rows = users.findAll().stream()
                .filter(u -> period.contains(u.getCreatedAt()))
                .sorted(Comparator.comparing(User::getCreatedAt))
                .map(u -> row(
                        u.getFullName(),
                        u.getCustomerRef(),
                        u.getEmail(),
                        u.getPhone(),
                        u.getAccountStatus().label(),
                        u.getKycTier().label(),
                        u.getState(),
                        groupLabels.getOrDefault(u.getId(), ""),
                        leads.getOrDefault(u.getId(), ""),
                        balances.getOrDefault(u.getId(), Money.zero()),
                        u.getCreatedAt(),
                        u.getLastActiveAt()))
                .toList();

        return ExportDtos.Report.of(List.of(
                text("Name"), text("Account number"), text("Email"), text("Phone"), text("Status"),
                text("Verification"), text("State"), text("Loan group"), text("Lead loan officer"),
                money("Wallet balance"), date("Joined"), date("Last active")), rows);
    }

    private ExportDtos.Report loans(Period period) {
        Map<UUID, User> people = people();
        Map<UUID, String> groupLabels = groupLabels();
        List<List<Object>> rows = loans.findAll().stream()
                .filter(l -> l.getDisbursedAt() != null && period.contains(l.getDisbursedAt()))
                .sorted(Comparator.comparing(Loan::getDisbursedAt))
                .map(l -> {
                    User u = people.get(l.getUserId());
                    return row(
                            u == null ? "" : u.getFullName(),
                            u == null ? "" : u.getCustomerRef(),
                            groupLabels.getOrDefault(l.getUserId(), ""),
                            l.getPurpose(),
                            l.getPrincipal(),
                            l.getTenureMonths(),
                            l.frequency().label(),
                            l.installmentCount(),
                            percent(l.getFlatRate()),
                            l.getProcessingFee(),
                            l.netDisbursed(),
                            l.totalInterest(),
                            l.repaymentFees(),
                            l.totalRepayable(),
                            l.getAmountRepaid(),
                            l.outstanding(),
                            l.getStatus().label(),
                            l.getDisbursedAt(),
                            l.getDueDate(),
                            l.getDecidedBy());
                })
                .toList();

        return ExportDtos.Report.of(List.of(
                text("Borrower"), text("Account number"), text("Loan group"), text("Purpose"),
                money("Amount lent"), number("Months"), text("Repaid"), number("Instalments"),
                text("Flat rate"), money("Management fee"), money("Paid out"), money("Interest"),
                money("Transaction fees"), money("Total to repay"), money("Repaid so far"),
                money("Outstanding"), text("Status"), date("Disbursed"), date("Due"), text("Approved by")), rows);
    }

    /**
     * Every repayment, whichever way it came: from the wallet or through
     * Paystack (both recorded in the ledger as a loan repayment), and cash or
     * transfers the team recorded by hand (which never touch the wallet).
     */
    private ExportDtos.Report repayments(Period period) {
        Map<UUID, User> people = people();
        Map<UUID, Loan> loansById = loans.findAll().stream().collect(Collectors.toMap(Loan::getId, Function.identity()));

        List<Repayment> all = new ArrayList<>();
        for (WalletTransaction tx : ledger.findAll()) {
            if (tx.getKind() != TxKind.LOAN_REPAYMENT || !period.contains(tx.getOccurredAt())) {
                continue;
            }
            Loan loan = LedgerService.Related.LOAN.equals(tx.getRelatedType()) ? loansById.get(tx.getRelatedId()) : null;
            all.add(new Repayment(
                    tx.getOccurredAt(), tx.getUserId(), loan, tx.getAmount().abs(),
                    "Wallet or Paystack", tx.getReference(), tx.getDescription(), ""));
        }
        for (RecordedRepayment r : recorded.findAll()) {
            if (r.getLoanId() == null || !period.contains(r.getPaidAt())) {
                continue;
            }
            Loan loan = loansById.get(r.getLoanId());
            all.add(new Repayment(
                    r.getPaidAt(), loan == null ? null : loan.getUserId(), loan, r.getAmount(),
                    "Recorded by hand: " + humanise(r.getMethod()), r.getReference(), r.getNote(), r.getRecordedBy()));
        }

        List<List<Object>> rows = all.stream()
                .sorted(Comparator.comparing(Repayment::at))
                .map(r -> {
                    User u = r.userId() == null ? null : people.get(r.userId());
                    return row(
                            r.at(),
                            u == null ? "" : u.getFullName(),
                            u == null ? "" : u.getCustomerRef(),
                            r.loan() == null ? "" : r.loan().getPurpose(),
                            r.loan() == null ? null : r.loan().getPrincipal(),
                            r.amount(),
                            r.method(),
                            r.reference(),
                            r.note(),
                            r.recordedBy(),
                            r.loan() == null ? null : r.loan().outstanding());
                })
                .toList();

        return ExportDtos.Report.of(List.of(
                date("Paid on"), text("Borrower"), text("Account number"), text("Loan"), money("Loan amount"),
                money("Amount repaid"), text("How"), text("Reference"), text("Note"), text("Recorded by"),
                money("Loan outstanding now")), rows);
    }

    private ExportDtos.Report transactions(Period period) {
        Map<UUID, User> people = people();
        List<List<Object>> rows = ledger.findAll().stream()
                .filter(t -> period.contains(t.getOccurredAt()))
                .sorted(Comparator.comparing(WalletTransaction::getOccurredAt)
                        .thenComparing(WalletTransaction::getSequence))
                .map(t -> {
                    User u = people.get(t.getUserId());
                    boolean in = t.getKind().isCredit();
                    return row(
                            t.getOccurredAt(),
                            u == null ? "" : u.getFullName(),
                            u == null ? "" : u.getCustomerRef(),
                            t.getKind().label(),
                            t.getDescription(),
                            in ? t.getAmount().abs() : null,
                            in ? null : t.getAmount().abs(),
                            t.getBalanceAfter(),
                            t.getReference(),
                            t.getCounterparty());
                })
                .toList();

        return ExportDtos.Report.of(List.of(
                date("Date"), text("Customer"), text("Account number"), text("Type"), text("Description"),
                money("Money in"), money("Money out"), money("Balance after"), text("Reference"),
                text("Counterparty")), rows);
    }

    private ExportDtos.Report paystackPayments(Period period) {
        List<List<Object>> rows = paystack.findAll().stream()
                .filter(p -> p.getCreatedAt() != null && period.contains(p.getCreatedAt()))
                .sorted(Comparator.comparing(PaystackPayment::getCreatedAt))
                .map(p -> row(
                        p.getCreatedAt(),
                        p.getCustomerName(),
                        p.getCustomerRef(),
                        p.getPurpose().label(),
                        p.getAmount(),
                        p.getFees(),
                        p.getStatus().label(),
                        p.getChannel(),
                        p.getCardType() == null ? "" : p.getCardType() + " ••" + nullToEmpty(p.getCardLast4()),
                        p.getReference(),
                        p.getPaidAt(),
                        p.getCreditedAt()))
                .toList();

        return ExportDtos.Report.of(List.of(
                date("Started"), text("Customer"), text("Account number"), text("For"), money("Amount"),
                money("Paystack fee"), text("Status"), text("Channel"), text("Card"), text("Reference"),
                date("Paid"), date("Credited")), rows);
    }

    private ExportDtos.Report withdrawals(Period period) {
        List<List<Object>> rows = withdrawals.findAll().stream()
                .filter(w -> period.contains(w.getRequestedAt()))
                .sorted(Comparator.comparing(WithdrawalRequest::getRequestedAt))
                .map(w -> row(
                        w.getRequestedAt(),
                        w.getCustomerName(),
                        w.getCustomerRef(),
                        w.getAmount(),
                        w.getBank(),
                        w.getDestinationAccount(),
                        w.getDestinationName(),
                        w.getStatus().label(),
                        w.getReviewedBy(),
                        w.getReviewedAt(),
                        w.getPayoutProvider(),
                        w.getPayoutStatus(),
                        w.getPaidOutAt(),
                        w.getReference()))
                .toList();

        return ExportDtos.Report.of(List.of(
                date("Requested"), text("Customer"), text("Account number"), money("Amount"), text("Bank"),
                text("Bank account"), text("Account name"), text("Status"), text("Decided by"), date("Decided"),
                text("Sent by"), text("Transfer status"), date("Paid out"), text("Reference")), rows);
    }

    private ExportDtos.Report applications(Period period) {
        List<List<Object>> rows = applications.findAll().stream()
                .filter(a -> period.contains(a.getSubmittedAt()))
                .sorted(Comparator.comparing(LoanApplication::getSubmittedAt))
                .map(a -> row(
                        a.getSubmittedAt(),
                        a.getCustomerName(),
                        a.getCustomerRef(),
                        a.getAmount(),
                        a.getTenureMonths(),
                        a.getRepaymentFrequency() == null ? "" : a.getRepaymentFrequency().label(),
                        a.getPurpose(),
                        a.getBusinessName(),
                        a.getStatus().label(),
                        a.getReviewedBy(),
                        a.getReviewedAt(),
                        a.getRejectionReason()))
                .toList();

        return ExportDtos.Report.of(List.of(
                date("Submitted"), text("Customer"), text("Account number"), money("Amount asked"), number("Months"),
                text("Repaid"), text("Purpose"), text("Business"), text("Status"), text("Decided by"),
                date("Decided"), text("Reason given")), rows);
    }

    // ── Plumbing ──────────────────────────────────────────────────────────

    private record Repayment(
            Instant at, UUID userId, Loan loan, BigDecimal amount, String method,
            String reference, String note, String recordedBy) {
    }

    /** A Lagos-date window; an open end is unbounded. */
    private record Period(LocalDate from, LocalDate to) {

        boolean contains(Instant at) {
            if (at == null) {
                return from == null && to == null;
            }
            LocalDate day = Dates.lagosDate(at);
            return (from == null || !day.isBefore(from)) && (to == null || !day.isAfter(to));
        }

        String label() {
            if (from == null && to == null) {
                return "All time";
            }
            if (from == null) {
                return "Up to " + to;
            }
            if (to == null) {
                return "From " + from;
            }
            return from + " to " + to;
        }
    }

    private Map<UUID, User> people() {
        return users.findAll().stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    /** "Group 3 — Oja Oba market", by customer; several joined with a comma. */
    private Map<UUID, String> groupLabels() {
        Map<UUID, LoanGroup> byId = groups.findAll().stream().collect(Collectors.toMap(LoanGroup::getId, Function.identity()));
        Map<UUID, List<String>> labels = new HashMap<>();
        for (LoanGroupMember m : groupMembers.findAll()) {
            LoanGroup g = byId.get(m.getGroupId());
            if (m.getUserId() != null && g != null) {
                labels.computeIfAbsent(m.getUserId(), k -> new ArrayList<>()).add("Group " + g.getNumber() + " — " + g.getName());
            }
        }
        Map<UUID, String> out = new HashMap<>();
        labels.forEach((k, v) -> out.put(k, String.join(", ", v)));
        return out;
    }

    private static List<Object> row(Object... cells) {
        return new ArrayList<>(Arrays.asList(cells));
    }

    private static String percent(BigDecimal rate) {
        return rate == null ? "" : rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
    }

    private static String humanise(String method) {
        if (method == null || method.isBlank()) {
            return "";
        }
        String s = method.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static ExportDtos.Column text(String label) {
        return new ExportDtos.Column(label, ExportDtos.ColumnType.TEXT);
    }

    private static ExportDtos.Column money(String label) {
        return new ExportDtos.Column(label, ExportDtos.ColumnType.MONEY);
    }

    private static ExportDtos.Column number(String label) {
        return new ExportDtos.Column(label, ExportDtos.ColumnType.NUMBER);
    }

    private static ExportDtos.Column date(String label) {
        return new ExportDtos.Column(label, ExportDtos.ColumnType.DATE);
    }
}
