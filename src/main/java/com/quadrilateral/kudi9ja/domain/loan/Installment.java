package com.quadrilateral.kudi9ja.domain.loan;

import com.quadrilateral.kudi9ja.common.util.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * One row of a repayment schedule.
 *
 * <p>Derived, never stored. A stored schedule can drift away from the ledger;
 * a derived one cannot.
 *
 * @param amount the whole instalment: the share of the loan and interest, plus
 *               {@code fee}
 * @param fee    the transaction fee within {@code amount} — a percentage of
 *               the share of the loan and interest on loans priced since it was
 *               introduced, zero before
 */
public record Installment(
        int number,
        Instant dueDate,
        BigDecimal amount,
        BigDecimal amountPaid,
        InstallmentStatus status,
        BigDecimal fee) {

    public BigDecimal outstanding() {
        BigDecimal left = Money.subtract(amount, amountPaid);
        return left.compareTo(new BigDecimal("0.01")) < 0 ? Money.zero() : left;
    }

    /** The share of the loan and interest, before the transaction fee. */
    public BigDecimal base() {
        return Money.subtract(amount, fee);
    }

    /** Negative once the date has passed. */
    public long daysUntilDue(Instant now) {
        return ChronoUnit.DAYS.between(now, dueDate);
    }
}
