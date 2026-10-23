package com.quadrilateral.kudi9ja.domain.loan;

import com.quadrilateral.kudi9ja.common.util.Dates;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * How often a borrower pays back: once a month, or once a week.
 *
 * <p>The tenure is still counted in months and priced from the same rate card,
 * so the choice changes how the total is split, not what the loan costs.
 */
public enum RepaymentFrequency {

    /** One instalment a calendar month after disbursement, and so on. */
    MONTHLY("Monthly", "month"),

    /**
     * One instalment every seven days, for as many whole weeks as the
     * calendar holds between disbursement and the same date {@code n} months
     * later. Three months from 23 October (92 days) is 13 weeks; three months
     * from 30 November, ending 28 February (90 days), is 12.
     */
    WEEKLY("Weekly", "week");

    private final String label;
    private final String unit;

    RepaymentFrequency(String label, String unit) {
        this.label = label;
        this.unit = unit;
    }

    public String label() {
        return label;
    }

    /** "month" or "week", for "₦25,000 a week". */
    public String unit() {
        return unit;
    }

    /**
     * How many instalments a tenure of {@code months} starting at
     * {@code start} is split into. Weekly counts follow the calendar, so the
     * date matters; a loan fixes its count on the day it is paid out.
     */
    public int installments(Instant start, int months) {
        if (months <= 0) {
            return 0;
        }
        return switch (this) {
            case MONTHLY -> months;
            case WEEKLY -> {
                long days = ChronoUnit.DAYS.between(start, Dates.addMonths(start, months));
                yield (int) Math.max(1, days / 7);
            }
        };
    }

    /** When the {@code n}th instalment (counting from 1) falls due. */
    public Instant due(Instant disbursedAt, int n) {
        return switch (this) {
            case MONTHLY -> Dates.addMonths(disbursedAt, n);
            case WEEKLY -> Dates.addDays(disbursedAt, 7 * n);
        };
    }

    /** Monthly unless somebody chose otherwise. */
    public static RepaymentFrequency orMonthly(RepaymentFrequency frequency) {
        return frequency == null ? MONTHLY : frequency;
    }
}
