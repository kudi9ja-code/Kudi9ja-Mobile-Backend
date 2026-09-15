package com.quadrilateral.kudi9ja.finance;

import com.quadrilateral.kudi9ja.common.util.Dates;
import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.domain.savings.AutoFrequency;
import com.quadrilateral.kudi9ja.domain.settings.PlatformSettings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Every money rule in one place, so no other class invents its own maths.
 *
 * <p>These are pure functions of a principal, a term and the settings in force.
 * Nothing here reads a database, and nothing here mutates. That is what makes
 * the rate card testable against the two invariants the business commits to,
 * and what lets a quote and the loan it becomes be priced by the same code.
 *
 * <p><b>Interest is never compounded anywhere in this product.</b> Savings and
 * loan interest are both flat, computed once.
 */
public final class Finance {

    private Finance() {
    }

    // ── Fixed Savings, priced by the day ───────────────────────────────────

    /**
     * The return on a Fixed Savings principal locked for {@code days}, paid
     * into the wallet upfront.
     *
     * <p>The annual rate is spread evenly across the year, so a lock of any
     * length is priced exactly: 365 days pays the full 17%, 30 days pays
     * 1.397%, 171 days pays 7.964%. Nothing rounds to whole months.
     */
    public static BigDecimal savingsInterest(PlatformSettings s, BigDecimal principal, int days) {
        BigDecimal fraction = Money.calc(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(s.getDaysPerYear()), Money.CALC_SCALE, RoundingMode.HALF_UP);
        return Money.of(Money.calc(principal).multiply(s.getSavingsAnnualRate()).multiply(fraction));
    }

    public static BigDecimal savingsTotal(PlatformSettings s, BigDecimal principal, int days) {
        return Money.add(principal, savingsInterest(s, principal, days));
    }

    /** Effective yield over the whole lock period, as a percentage. */
    public static BigDecimal effectiveYieldPct(PlatformSettings s, int days) {
        return s.getSavingsAnnualRate()
                .multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(s.getDaysPerYear()), Money.CALC_SCALE, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(3, RoundingMode.HALF_UP);
    }

    // ── Target Savings, a goal funded over time ────────────────────────────

    /** Days in a Target Savings term. A month counts as thirty days. */
    public static int targetDays(PlatformSettings s, int months) {
        return months * s.getDaysPerSavingsMonth();
    }

    /** How many deposits a schedule makes over {@code months}. */
    public static int targetRuns(PlatformSettings s, AutoFrequency frequency, int months) {
        int days = targetDays(s, months);
        return switch (frequency) {
            case DAILY -> days;
            case WEEKLY -> days / 7;
            case MONTHLY -> months;
        };
    }

    /**
     * The amount that must go in each time to reach {@code goal} over the term.
     * Saving 100,000 over six months daily works out at 555.56 a day.
     */
    public static BigDecimal targetPerDeposit(
            PlatformSettings s, BigDecimal goal, AutoFrequency frequency, int months) {
        int runs = targetRuns(s, frequency, months);
        if (runs <= 0) {
            return Money.of(goal);
        }
        return Money.of(Money.calc(goal).divide(BigDecimal.valueOf(runs), Money.CALC_SCALE, RoundingMode.HALF_UP));
    }

    /**
     * The lump sum a Target Savings plan pays on its final day, on the total
     * actually saved rather than the total intended.
     */
    public static BigDecimal targetBonus(BigDecimal totalSaved, BigDecimal bonusRate) {
        return Money.of(Money.calc(totalSaved).multiply(bonusRate == null ? BigDecimal.ZERO : bonusRate));
    }

    // ── Lending, flat interest priced by tenure ────────────────────────────

    /** Flat interest on the amount borrowed, at the rate published for the tenure. */
    public static BigDecimal loanInterest(PlatformSettings s, BigDecimal principal, int months) {
        return Money.of(Money.calc(principal).multiply(s.loanRateFor(months)));
    }

    /** Flat interest at a rate already frozen onto a loan. */
    public static BigDecimal loanInterestAt(BigDecimal principal, BigDecimal flatRate) {
        return Money.of(Money.calc(principal).multiply(flatRate));
    }

    public static BigDecimal loanTotal(PlatformSettings s, BigDecimal principal, int months) {
        return Money.add(principal, loanInterest(s, principal, months));
    }

    /** One equal instalment. The tenure is at least one month by validation. */
    public static BigDecimal loanMonthly(PlatformSettings s, BigDecimal principal, int months) {
        if (months <= 0) {
            return Money.zero();
        }
        return Money.of(Money.calc(loanTotal(s, principal, months))
                .divide(BigDecimal.valueOf(months), Money.CALC_SCALE, RoundingMode.HALF_UP));
    }

    /**
     * The management fee, also called the processing fee.
     *
     * <p>A flat fee on any loan up to <b>and including</b> the threshold. One
     * naira above it, the fee becomes a percentage of the <b>whole</b>
     * principal, never of the excess — ₦500,001 is already on the percentage.
     * The two rules meet exactly at the threshold (1% of ₦500,000 is ₦5,000),
     * so the fee curve is continuous and never jumps.
     *
     * <p>It is always deducted from the disbursement, never added to the debt.
     */
    public static BigDecimal processingFee(PlatformSettings s, BigDecimal principal) {
        if (Money.lte(principal, s.getProcessingFeeThreshold())) {
            return Money.of(s.getFlatProcessingFee());
        }
        return Money.of(Money.calc(principal).multiply(s.getLoanProcessingFeeRate()));
    }

    /** What actually reaches the wallet once the fee is taken off. */
    public static BigDecimal netDisbursed(PlatformSettings s, BigDecimal principal) {
        return Money.subtract(principal, processingFee(s, principal));
    }

    /** How the fee on this loan was worked out, in words, for a receipt. */
    public static String processingFeeBasis(PlatformSettings s, BigDecimal principal) {
        if (Money.lte(principal, s.getProcessingFeeThreshold())) {
            return "Flat fee up to " + Money.naira(s.getProcessingFeeThreshold());
        }
        BigDecimal pct = s.getLoanProcessingFeeRate().multiply(BigDecimal.valueOf(100))
                .stripTrailingZeros();
        return pct.toPlainString() + "% of the whole amount";
    }

    /**
     * Settling a loan early earns back a share of the interest on the months
     * that have not yet started.
     *
     * <pre>
     *   elapsed   = whole months since disbursement
     *   remaining = tenureMonths - elapsed - 1
     *   rebate    = totalInterest x (remaining / tenureMonths) x share
     * </pre>
     *
     * <p>Capped at the outstanding balance, so a rebate can never turn a
     * settlement into a payout. There is no early-settlement charge.
     */
    public static BigDecimal earlyPayoffRebate(
            PlatformSettings s,
            BigDecimal totalInterest,
            BigDecimal outstanding,
            int tenureMonths,
            Instant disbursedAt,
            Instant now) {
        if (tenureMonths <= 0 || Money.isZeroOrLess(outstanding)) {
            return Money.zero();
        }
        int elapsed = Dates.monthsBetween(disbursedAt, now);
        int remaining = tenureMonths - elapsed - 1;
        if (remaining <= 0) {
            return Money.zero();
        }
        BigDecimal share = Money.calc(BigDecimal.valueOf(remaining))
                .divide(BigDecimal.valueOf(tenureMonths), Money.CALC_SCALE, RoundingMode.HALF_UP);
        BigDecimal rebate = Money.of(Money.calc(totalInterest)
                .multiply(share)
                .multiply(s.getEarlyPayoffRebateShare()));
        return Money.min(rebate, outstanding);
    }
}
