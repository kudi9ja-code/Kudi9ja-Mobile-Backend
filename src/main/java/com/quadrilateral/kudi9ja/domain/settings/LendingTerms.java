package com.quadrilateral.kudi9ja.domain.settings;

import com.quadrilateral.kudi9ja.domain.loan.RepaymentFrequency;
import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

/**
 * Everything that prices a loan, as one set.
 *
 * <p>There are two: the terms for everyone, and the terms for members of a
 * loan group. Both live in {@link PlatformSettings} and are versioned with it;
 * this is the read-only view a quote, an application and a disbursement price
 * from, so none of them has to know which set it was handed.
 *
 * @param group                  true for the loan-group set
 * @param rates                  flat interest keyed by tenure in months
 * @param processingFeeRate      the management fee above the threshold, as a
 *                               fraction of the whole loan
 * @param repaymentFeeRate       the transaction fee on each repayment, as a
 *                               fraction of the instalment
 */
public record LendingTerms(
        boolean group,
        Map<Integer, BigDecimal> rates,
        int maxTenureMonths,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        BigDecimal flatProcessingFee,
        BigDecimal processingFeeThreshold,
        BigDecimal processingFeeRate,
        BigDecimal repaymentFeeRate,
        boolean weeklyAllowed,
        boolean monthlyAllowed) {

    public LendingTerms {
        rates = new TreeMap<>(rates == null ? Map.of() : rates);
    }

    /**
     * The flat rate for a loan of {@code months}. A tenure with no entry of
     * its own falls back to the nearest shorter one, so nothing is unpriced.
     */
    public BigDecimal rateFor(int months) {
        return rateFrom(rates, months);
    }

    /** Whether a rate is set for exactly this tenure. */
    public boolean prices(int months) {
        return rates.get(months) != null;
    }

    public boolean allows(RepaymentFrequency frequency) {
        return switch (RepaymentFrequency.orMonthly(frequency)) {
            case WEEKLY -> weeklyAllowed;
            case MONTHLY -> monthlyAllowed;
        };
    }

    /** "Loan group" or "Standard", for a message. */
    public String label() {
        return group ? "Loan group" : "Standard";
    }

    static BigDecimal rateFrom(Map<Integer, BigDecimal> rates, int months) {
        int wanted = Math.max(months, 1);
        BigDecimal exact = rates.get(wanted);
        if (exact != null) {
            return exact;
        }
        int bestMonths = 0;
        BigDecimal best = null;
        for (Map.Entry<Integer, BigDecimal> entry : rates.entrySet()) {
            if (entry.getKey() <= wanted && entry.getKey() > bestMonths) {
                bestMonths = entry.getKey();
                best = entry.getValue();
            }
        }
        if (best != null) {
            return best;
        }
        // Nothing at or below the tenure asked for: take the shortest we have.
        return new TreeMap<>(rates).firstEntry().getValue();
    }
}
