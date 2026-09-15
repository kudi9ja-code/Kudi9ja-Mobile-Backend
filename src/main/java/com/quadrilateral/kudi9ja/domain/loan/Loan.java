package com.quadrilateral.kudi9ja.domain.loan;

import com.quadrilateral.kudi9ja.common.util.Dates;
import com.quadrilateral.kudi9ja.common.util.Money;
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

    /**
     * The flat rate charged on the principal, fixed at the rate published for
     * this tenure on the day it was disbursed.
     */
    @Column(name = "flat_rate", nullable = false, precision = 12, scale = 6, updatable = false)
    private BigDecimal flatRate;

    /** Deducted from the disbursement, never added to the debt. */
    @Column(name = "processing_fee", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal processingFee;

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

    public BigDecimal totalInterest() {
        return Money.multiply(principal, flatRate);
    }

    public BigDecimal totalRepayable() {
        return Money.add(principal, totalInterest());
    }

    /** One equal instalment. */
    public BigDecimal monthlyRepayment() {
        if (tenureMonths <= 0) {
            return Money.zero();
        }
        return Money.of(Money.calc(totalRepayable())
                .divide(BigDecimal.valueOf(tenureMonths), Money.CALC_SCALE, RoundingMode.HALF_UP));
    }

    /** What is still owed, after repayments and any rebate. Floored at zero. */
    public BigDecimal outstanding() {
        BigDecimal left = Money.subtract(
                totalRepayable(), Money.add(amountRepaid, rebateGranted));
        return left.compareTo(new BigDecimal("0.01")) < 0 ? Money.zero() : left;
    }

    public BigDecimal netDisbursed() {
        return Money.subtract(principal, processingFee);
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
     * Whether the change-of-mind window is still open.
     *
     * <p>The Lending Agreement grants a period in which a borrower may return
     * what they received plus the fee, pay no interest, and have the loan leave
     * the record.
     */
    public boolean withinCancellationWindow(Instant now, int windowHours) {
        return disbursedAt != null
                && status == LoanStatus.ACTIVE
                && Money.isZeroOrLess(amountRepaid)
                && now.isBefore(disbursedAt.plusSeconds(windowHours * 3600L));
    }

    /** What it costs to cancel: the money received, plus the fee that was taken. */
    public BigDecimal cancellationAmount() {
        return Money.add(netDisbursed(), processingFee);
    }

    /**
     * The full schedule, derived from what has actually been repaid.
     *
     * <p>Equal instalments, the nth due a calendar month after disbursement.
     * Each instalment's status comes from pouring what has been repaid into
     * them oldest-first — which is why a schedule is derived rather than
     * stored: it can never disagree with the ledger.
     */
    public List<Installment> schedule(Instant now) {
        List<Installment> schedule = new ArrayList<>(tenureMonths);
        if (disbursedAt == null || tenureMonths <= 0) {
            return schedule;
        }

        BigDecimal each = monthlyRepayment();
        BigDecimal covered = Money.add(amountRepaid, rebateGranted);

        for (int i = 0; i < tenureMonths; i++) {
            Instant due = Dates.addMonths(disbursedAt, i + 1);

            // The last instalment absorbs whatever rounding left over, so the
            // schedule always sums to exactly the total repayable.
            BigDecimal amount = i == tenureMonths - 1
                    ? Money.subtract(totalRepayable(),
                            Money.multiply(each, BigDecimal.valueOf(tenureMonths - 1L)))
                    : each;

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

            schedule.add(new Installment(i + 1, due, amount, paidHere, status));
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
