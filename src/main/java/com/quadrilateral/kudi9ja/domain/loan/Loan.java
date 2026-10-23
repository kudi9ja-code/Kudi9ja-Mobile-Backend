package com.quadrilateral.kudi9ja.domain.loan;

import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.finance.Finance;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A loan, priced once and never repriced.
 *
 * <p>{@link #flatRate} is written at disbursement and is the only rate this
 * loan will ever be charged at. A change to the platform rate card cannot reach
 * a running loan because a running loan never reads the rate card again.
 *
 * <p><b>The amount owed never grows.</b> There is no late fee and no penalty
 * interest anywhere in this product, and the Lending Agreement commits to it in
 * writing: <i>"Even in default, the amount you owe does not increase."</i> An
 * overdue loan is a status, not a surcharge. Nothing here may add to
 * {@link #totalRepayable()}.
 */
@Entity
@Table(
        name = "loan",
        indexes = {
                @Index(name = "ix_loan_user", columnList = "user_id, disbursed_at"),
                @Index(name = "ix_loan_status", columnList = "status"),
                @Index(name = "ix_loan_due", columnList = "status, due_date")
        })
@Getter
@Setter
@NoArgsConstructor
public class Loan {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "principal", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal principal;

    @Column(name = "tenure_months", nullable = false, updatable = false)
    private int tenureMonths;

    /** Monthly or weekly instalments. Chosen when applying; never changes. */
    @Enumerated(EnumType.STRING)
    @Column(name = "repayment_frequency", nullable = false, length = 16, updatable = false)
    private RepaymentFrequency repaymentFrequency = RepaymentFrequency.MONTHLY;

    /**
     * The flat rate charged on the principal, fixed at the rate published for
     * this tenure on the day it was disbursed.
     */
    @Column(name = "flat_rate", nullable = false, precision = 12, scale = 6, updatable = false)
    private BigDecimal flatRate;

    /** Deducted from the disbursement, never added to the debt. */
    @Column(name = "processing_fee", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal processingFee;

    /** The flat transaction fee, also deducted from the disbursement. Zero on loans priced before it existed. */
    @Column(name = "transaction_fee", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal transactionFee = BigDecimal.ZERO;

    /**
     * How many instalments. Fixed on the day the loan is paid out, because a
     * weekly count follows the calendar from that day.
     */
    @Column(name = "installment_count", nullable = false, updatable = false)
    private Integer installmentCount;

    /**
     * The transaction fee on each repayment, as a fraction of the instalment:
     * 0.02 for 2%. Frozen at disbursement. Zero on loans priced before it.
     */
    @Column(name = "repayment_fee_rate", nullable = false, precision = 12, scale = 6, updatable = false)
    private BigDecimal repaymentFeeRate = BigDecimal.ZERO;

    /** What the transaction fees on all the instalments add up to. Part of what is repaid. */
    @Column(name = "repayment_fees", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal repaymentFees = BigDecimal.ZERO;

    /** Priced in whole naira, every amount rounded up. True of every loan since the repayment fee. */
    @Column(name = "whole_naira", nullable = false, updatable = false)
    private boolean wholeNaira;

    @Column(name = "purpose", nullable = false, length = 200)
    private String purpose;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "disbursed_at")
    private Instant disbursedAt;

    @Column(name = "due_date")
    private Instant dueDate;

    @Column(name = "amount_repaid", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountRepaid = Money.zero();

    /** Interest handed back on an early settlement. Reduces what is owed. */
    @Column(name = "rebate_granted", nullable = false, precision = 19, scale = 2)
    private BigDecimal rebateGranted = Money.zero();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private LoanStatus status = LoanStatus.PENDING;

    @Column(name = "settled_at")
    private Instant settledAt;

    /**
     * Why a request was declined or reduced.
     *
     * <p>An automated decision that goes against a customer has to record its
     * reasons and support a human review, so this is written whenever a request
     * is refused rather than left to be reconstructed afterwards.
     */
    @Column(name = "decision_reasons", length = 2000)
    private String decisionReasons;

    @Column(name = "decided_by", length = 200)
    private String decidedBy;

    @Column(name = "write_off_note", length = 1000)
    private String writeOffNote;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    // Derived ----------------------------------------------------------------

    /** Flat interest on the principal; in whole naira, rounded up, on loans priced in naira. */
    public BigDecimal totalInterest() {
        if (!wholeNaira) {
            return Money.multiply(principal, flatRate);
        }
        BigDecimal loanTotal = Money.calc(principal).add(Money.calc(principal).multiply(flatRate))
                .setScale(0, RoundingMode.CEILING);
        return Money.subtract(loanTotal, principal);
    }

    /** The loan and its interest, before any transaction fee. */
    public BigDecimal loanAndInterest() {
        return Money.add(principal, totalInterest());
    }

    /** Everything the borrower repays: the loan, the interest, and the transaction fee on every instalment. */
    public BigDecimal totalRepayable() {
        return Money.add(loanAndInterest(), repaymentFees());
    }

    public BigDecimal repaymentFees() {
        return repaymentFees == null ? Money.zero() : Money.of(repaymentFees);
    }

    public BigDecimal repaymentFeeRate() {
        return repaymentFeeRate == null ? BigDecimal.ZERO : repaymentFeeRate;
    }

    public RepaymentFrequency frequency() {
        return RepaymentFrequency.orMonthly(repaymentFrequency);
    }

    /** How many instalments: one a month, or one a week, over the tenure. */
    public int installmentCount() {
        if (installmentCount != null) {
            return installmentCount;
        }
        return frequency().installments(disbursedAt != null ? disbursedAt : Instant.now(), tenureMonths);
    }

    /** Each instalment's share of the loan and interest, and the transaction fee on it. */
    public List<Finance.InstalmentPart> instalmentPlan() {
        return Finance.instalmentPlan(loanAndInterest(), installmentCount(), repaymentFeeRate(), wholeNaira);
    }

    /** One instalment, fee included. The last can be a little smaller: it takes the rounding. */
    public BigDecimal installmentAmount() {
        List<Finance.InstalmentPart> plan = instalmentPlan();
        return plan.isEmpty() ? Money.zero() : plan.get(0).total();
    }

    /**
     * Fixes how this loan is repaid, on the day it is paid out: how many
     * instalments from {@code start}, the transaction fee rate on each, and
     * what those fees come to. Every amount is in whole naira, rounded up.
     */
    public void fixRepaymentTerms(Instant start, BigDecimal feeRate) {
        this.wholeNaira = true;
        this.repaymentFeeRate = feeRate == null ? BigDecimal.ZERO : feeRate;
        this.installmentCount = frequency().installments(start, tenureMonths);
        this.repaymentFees = Money.zero();
        BigDecimal fees = Money.zero();
        for (Finance.InstalmentPart part : instalmentPlan()) {
            fees = Money.add(fees, part.fee());
        }
        this.repaymentFees = fees;
        this.dueDate = frequency().due(start, this.installmentCount);
    }

    /** What is still owed, after repayments and any rebate. Floored at zero. */
    public BigDecimal outstanding() {
        BigDecimal left = Money.subtract(
                totalRepayable(), Money.add(amountRepaid, rebateGranted));
        return left.compareTo(new BigDecimal("0.01")) < 0 ? Money.zero() : left;
    }

    public BigDecimal netDisbursed() {
        return Money.subtract(Money.subtract(principal, processingFee), transactionFee());
    }

    public BigDecimal transactionFee() {
        return transactionFee == null ? Money.zero() : transactionFee;
    }

    public double repaymentProgress() {
        BigDecimal total = totalRepayable();
        if (total.signum() <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(1,
                Money.divide(Money.add(amountRepaid, rebateGranted), total).doubleValue()));
    }

    public boolean isOpen() {
        return status.isOpen();
    }

    /**
     * The full schedule, derived from what has actually been repaid.
     *
     * <p>Equal instalments, the nth due n calendar months — or, on a weekly
     * loan, n weeks — after disbursement.
     * Each instalment's status comes from pouring what has been repaid into
     * them oldest-first — which is why a schedule is derived rather than
     * stored: it can never disagree with the ledger.
     */
    public List<Installment> schedule(Instant now) {
        int count = installmentCount();
        List<Installment> schedule = new ArrayList<>(Math.max(count, 0));
        if (disbursedAt == null || count <= 0) {
            return schedule;
        }

        // The last instalment absorbs whatever rounding left over, so the
        // schedule always sums to exactly the total repayable.
        List<Finance.InstalmentPart> plan = instalmentPlan();
        BigDecimal covered = Money.add(amountRepaid, rebateGranted);

        for (int i = 0; i < count; i++) {
            Instant due = frequency().due(disbursedAt, i + 1);
            Finance.InstalmentPart part = plan.get(i);
            BigDecimal amount = part.total();

            BigDecimal paidHere = Money.min(Money.floorAtZero(covered), amount);
            covered = Money.subtract(covered, paidHere);

            InstallmentStatus status;
            if (Money.gte(paidHere, amount)) {
                status = InstallmentStatus.PAID;
            } else if (now.isAfter(due)) {
                status = InstallmentStatus.OVERDUE;
            } else if (Money.isPositive(paidHere)) {
                status = InstallmentStatus.PARTIAL;
            } else {
                status = InstallmentStatus.UPCOMING;
            }

            schedule.add(new Installment(i + 1, due, amount, paidHere, status, part.fee()));
        }
        return schedule;
    }

    /** The next instalment owed, or empty once the loan is settled. */
    public java.util.Optional<Installment> nextInstallment(Instant now) {
        return schedule(now).stream()
                .filter(installment -> installment.status() != InstallmentStatus.PAID)
                .findFirst();
    }

    public int installmentsPaid(Instant now) {
        return (int) schedule(now).stream()
                .filter(installment -> installment.status() == InstallmentStatus.PAID)
                .count();
    }
}
