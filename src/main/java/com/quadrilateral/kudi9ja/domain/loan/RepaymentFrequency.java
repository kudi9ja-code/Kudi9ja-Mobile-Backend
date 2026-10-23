package com.quadrilateral.kudi9ja.domain.loan;

import com.quadrilateral.kudi9ja.common.util.Dates;
import java.time.Instant;

/**
 * How often a borrower pays back: once a month, or once a week.
 *
 * <p>The choice splits the same total differently and changes nothing else.
 * The tenure is still counted in months and priced from the same rate card, so
 * a weekly loan costs exactly what the monthly one would.
 */
public enum RepaymentFrequency {

    /** One instalment a calendar month after disbursement, and so on. */
    MONTHLY("Monthly", "month"),

    /**
     * One instalment every seven days. A tenure of {@code n} months has as
     * many weeks as {@code n} months hold — 4 in one, 13 in three, 26 in six,
     * 52 in twelve.
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

    /** How many instalments a tenure of {@code months} is split into. */
    public int installments(int months) {
        if (months <= 0) {
            return 0;
        }
        return switch (this) {
            case MONTHLY -> months;
            case WEEKLY -> (int) Math.round(months * 52 / 12.0);
        };
    }

    /** When the {@code n}th instalment (counting from 1) falls due. */
    public Instant due(Instant disbursedAt, int n) {
        return switch (this) {
            case MONTHLY -> Dates.addMonths(disbursedAt, n);
            case WEEKLY -> Dates.addDays(disbursedAt, 7 * n);
        };
    }

    /** When the last instalment falls due: the loan's due date. */
    public Instant finalDue(Instant disbursedAt, int months) {
        return due(disbursedAt, installments(months));
    }

    /** Monthly unless somebody chose otherwise. */
    public static RepaymentFrequency orMonthly(RepaymentFrequency frequency) {
        return frequency == null ? MONTHLY : frequency;
    }
}
