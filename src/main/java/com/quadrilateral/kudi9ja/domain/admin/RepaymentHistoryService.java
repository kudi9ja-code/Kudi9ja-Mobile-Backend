package com.quadrilateral.kudi9ja.domain.admin;

import com.quadrilateral.kudi9ja.common.api.PageResponse;
import com.quadrilateral.kudi9ja.common.util.Dates;
import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.domain.loan.ImportedLoan;
import com.quadrilateral.kudi9ja.domain.loan.ImportedLoanRepository;
import com.quadrilateral.kudi9ja.domain.loan.Loan;
import com.quadrilateral.kudi9ja.domain.loan.LoanRepository;
import com.quadrilateral.kudi9ja.domain.loan.RecordedRepayment;
import com.quadrilateral.kudi9ja.domain.loan.RecordedRepaymentRepository;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.domain.wallet.LedgerService;
import com.quadrilateral.kudi9ja.domain.wallet.TxKind;
import com.quadrilateral.kudi9ja.domain.wallet.TxStatus;
import com.quadrilateral.kudi9ja.domain.wallet.WalletTransaction;
import com.quadrilateral.kudi9ja.domain.wallet.WalletTransactionRepository;
import com.quadrilateral.kudi9ja.web.dto.RepaymentDtos;
import com.quadrilateral.kudi9ja.web.dto.RepaymentDtos.Row;
import com.quadrilateral.kudi9ja.web.dto.RepaymentDtos.Source;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every repayment customers have made, whichever way it came.
 *
 * <p>Two records hold them. Money that passed through a wallet — paid from the
 * balance, through Paystack, by a confirmed bank transfer, or an early
 * settlement — is a loan-repayment row in the ledger. Cash and transfers the
 * team took outside the app never touch a wallet, so they are recorded
 * repayments instead. This joins the two, the same way the repayments report
 * does, so the panel and the download always agree.
 */
@Service
public class RepaymentHistoryService {

    private final AdminAccessService access;
    private final WalletTransactionRepository ledger;
    private final RecordedRepaymentRepository recorded;
    private final LoanRepository loans;
    private final ImportedLoanRepository imports;
    private final UserRepository users;

    public RepaymentHistoryService(
            AdminAccessService access,
            WalletTransactionRepository ledger,
            RecordedRepaymentRepository recorded,
            LoanRepository loans,
            ImportedLoanRepository imports,
            UserRepository users) {
        this.access = access;
        this.ledger = ledger;
        this.recorded = recorded;
        this.loans = loans;
        this.imports = imports;
        this.users = users;
    }

    /**
     * One page of repayments, newest first, with totals for everything the
     * filters match.
     *
     * @param from first day included, Lagos time; null for no lower bound
     * @param to   last day included, Lagos time; null for no upper bound
     */
    @Transactional(readOnly = true)
    public RepaymentDtos.History history(
            String query, Source source, LocalDate from, LocalDate to, int page, int size) {

        access.requireCanView();
        String q = query == null || query.isBlank() ? null : query.trim().toLowerCase(Locale.ROOT);

        List<Row> matching = all().stream()
                .filter(r -> source == null || r.source() == source)
                .filter(r -> from == null || !Dates.lagosDate(r.paidAt()).isBefore(from))
                .filter(r -> to == null || !Dates.lagosDate(r.paidAt()).isAfter(to))
                .filter(r -> q == null || matches(r, q))
                .toList();

        int pageSize = Math.max(1, Math.min(size, 200));
        int totalPages = Math.max(1, (matching.size() + pageSize - 1) / pageSize);
        int pageNo = Math.max(0, page);
        int start = Math.min(pageNo * pageSize, matching.size());
        List<Row> items = matching.subList(start, Math.min(start + pageSize, matching.size()));

        return new RepaymentDtos.History(
                new PageResponse<>(items, pageNo, pageSize, matching.size(), totalPages, pageNo + 1 < totalPages),
                summarise(matching));
    }

    /** Totals across every repayment, for the overview. */
    @Transactional(readOnly = true)
    public RepaymentDtos.Summary allTime() {
        return summarise(all());
    }

    /** Both records joined, newest first. */
    private List<Row> all() {
        List<WalletTransaction> fromLedger =
                ledger.findByKindAndStatusNotOrderByOccurredAtDesc(TxKind.LOAN_REPAYMENT, TxStatus.REVERSED);
        List<RecordedRepayment> byHand = recorded.findAll();

        Set<UUID> loanIds = new HashSet<>();
        Set<UUID> importIds = new HashSet<>();
        Set<UUID> userIds = new HashSet<>();
        for (WalletTransaction tx : fromLedger) {
            userIds.add(tx.getUserId());
            if (LedgerService.Related.LOAN.equals(tx.getRelatedType()) && tx.getRelatedId() != null) {
                loanIds.add(tx.getRelatedId());
            }
        }
        for (RecordedRepayment r : byHand) {
            if (r.getLoanId() != null) {
                loanIds.add(r.getLoanId());
            }
            if (r.getImportedLoanId() != null) {
                importIds.add(r.getImportedLoanId());
            }
        }
        Map<UUID, Loan> loansById = byId(loans.findAllById(loanIds), Loan::getId);
        loansById.values().forEach(l -> userIds.add(l.getUserId()));
        Map<UUID, ImportedLoan> importsById = byId(imports.findAllById(importIds), ImportedLoan::getId);
        importsById.values().stream().map(ImportedLoan::getUserId).filter(id -> id != null).forEach(userIds::add);
        Map<UUID, User> people = byId(users.findAllById(userIds), User::getId);

        List<Row> rows = new ArrayList<>(fromLedger.size() + byHand.size());
        for (WalletTransaction tx : fromLedger) {
            Loan loan = LedgerService.Related.LOAN.equals(tx.getRelatedType()) ? loansById.get(tx.getRelatedId()) : null;
            User u = people.get(tx.getUserId());
            Source source = sourceOf(tx.getDescription());
            rows.add(new Row(
                    tx.getId(),
                    tx.getOccurredAt(),
                    Money.of(tx.getAmount().abs()),
                    source,
                    source.label(),
                    tx.getUserId(),
                    u == null ? "—" : u.getFullName(),
                    u == null ? null : u.getCustomerRef(),
                    loan == null ? null : loan.getId(),
                    null,
                    loan == null ? null : loan.getPurpose(),
                    tx.getReference(),
                    tx.getDescription(),
                    null));
        }
        for (RecordedRepayment r : byHand) {
            Loan loan = r.getLoanId() == null ? null : loansById.get(r.getLoanId());
            ImportedLoan paper = r.getImportedLoanId() == null ? null : importsById.get(r.getImportedLoanId());
            UUID userId = loan != null ? loan.getUserId() : paper == null ? null : paper.getUserId();
            User u = userId == null ? null : people.get(userId);
            String name = u != null ? u.getFullName() : paper != null ? paper.getFullName() : "—";
            rows.add(new Row(
                    r.getId(),
                    r.getPaidAt(),
                    Money.of(r.getAmount()),
                    Source.RECORDED,
                    Source.RECORDED.label() + (r.getMethod() == null ? "" : " · " + humanise(r.getMethod())),
                    userId,
                    name,
                    u == null ? null : u.getCustomerRef(),
                    loan == null ? null : loan.getId(),
                    r.getImportedLoanId(),
                    loan != null ? loan.getPurpose() : paper == null ? null : paper.getPurpose(),
                    r.getReference(),
                    r.getNote(),
                    r.getRecordedBy()));
        }
        rows.sort(Comparator.comparing(Row::paidAt).reversed());
        return rows;
    }

    /**
     * Which way ledger money came, from the description the loan service
     * wrote: "Loan repayment through Paystack", "Loan repayment from confirmed
     * transfer", or a repayment or early settlement from the balance.
     */
    static Source sourceOf(String description) {
        String d = description == null ? "" : description.toLowerCase(Locale.ROOT);
        if (d.contains("paystack")) {
            return Source.PAYSTACK;
        }
        if (d.contains("confirmed transfer")) {
            return Source.BANK_TRANSFER;
        }
        return Source.WALLET;
    }

    private static RepaymentDtos.Summary summarise(List<Row> rows) {
        LocalDate today = Dates.lagosDate(Instant.now());
        BigDecimal total = Money.zero();
        BigDecimal todayTotal = Money.zero();
        BigDecimal month = Money.zero();
        BigDecimal paystack = Money.zero();
        BigDecimal wallet = Money.zero();
        BigDecimal transfer = Money.zero();
        BigDecimal byHand = Money.zero();
        long todayCount = 0;
        long monthCount = 0;
        for (Row r : rows) {
            total = Money.add(total, r.amount());
            LocalDate day = Dates.lagosDate(r.paidAt());
            if (day.equals(today)) {
                todayTotal = Money.add(todayTotal, r.amount());
                todayCount++;
            }
            if (day.getYear() == today.getYear() && day.getMonth() == today.getMonth()) {
                month = Money.add(month, r.amount());
                monthCount++;
            }
            switch (r.source()) {
                case PAYSTACK -> paystack = Money.add(paystack, r.amount());
                case WALLET -> wallet = Money.add(wallet, r.amount());
                case BANK_TRANSFER -> transfer = Money.add(transfer, r.amount());
                case RECORDED -> byHand = Money.add(byHand, r.amount());
            }
        }
        return new RepaymentDtos.Summary(
                total, rows.size(), todayTotal, todayCount, month, monthCount, paystack, wallet, transfer, byHand);
    }

    private static boolean matches(Row r, String q) {
        return contains(r.customerName(), q)
                || contains(r.customerRef(), q)
                || contains(r.reference(), q)
                || contains(r.loanPurpose(), q)
                || contains(r.recordedBy(), q);
    }

    private static boolean contains(String value, String q) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(q);
    }

    private static <T> Map<UUID, T> byId(Iterable<T> items, Function<T, UUID> id) {
        Map<UUID, T> map = new java.util.HashMap<>();
        items.forEach(item -> map.put(id.apply(item), item));
        return map;
    }

    private static String humanise(String method) {
        String s = method.replace('_', ' ').toLowerCase(Locale.ROOT);
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
