package com.quadrilateral.kudi9ja.domain.loan;

import com.quadrilateral.kudi9ja.common.util.Dates;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The late charge: 0.18% of the original amount borrowed, added every day from
 * the day after the loan's final due date until it is repaid in full.
 *
 * <p>Always on the original principal, never on the balance, so the daily
 * amount is the same every day. Rounded up to the whole naira, like every
 * other amount on a loan (₦100,000 → ₦180 a day).
 */
public final class LateCharges {

    /** 0.18% a day. */
    public static final BigDecimal DAILY_RATE = new BigDecimal("0.0018");

    public static final String RATE_LABEL = "0.18%";

    private LateCharges() {
    }

    /** What one overdue day adds. */
    public static BigDecimal daily(BigDecimal principal) {
        return principal.multiply(DAILY_RATE).setScale(0, RoundingMode.CEILING).setScale(2);
    }

    /**
     * The first day not yet charged. The day after the due day, but never
     * before late charges went live and never a day already charged.
     */
    public static LocalDate firstUnchargedDay(Instant dueDate, LocalDate chargedThrough, LocalDate startsOn) {
        LocalDate dayAfterDue = dueDate.atZone(Dates.LAGOS).toLocalDate().plusDays(1);
        LocalDate from = chargedThrough == null ? startsOn : chargedThrough.plusDays(1);
        return dayAfterDue.isAfter(from) ? dayAfterDue : from;
    }

    /** How many days, up to and including {@code today}, are still to be charged. */
    public static long daysToCharge(Instant dueDate, LocalDate chargedThrough, LocalDate startsOn, LocalDate today) {
        if (dueDate == null || startsOn == null) {
            return 0;
        }
        LocalDate from = firstUnchargedDay(dueDate, chargedThrough, startsOn);
        return today.isBefore(from) ? 0 : ChronoUnit.DAYS.between(from, today) + 1;
    }
}
