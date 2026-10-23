package com.quadrilateral.kudi9ja.finance;

import com.quadrilateral.kudi9ja.common.util.Dates;
import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.domain.savings.AutoFrequency;
import com.quadrilateral.kudi9ja.domain.settings.LendingTerms;
import com.quadrilateral.kudi9ja.domain.settings.PlatformSettings;
import com.quadrilateral.kudi9ja.domain.loan.RepaymentFrequency;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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

    /** One instalment: its share of the loan and interest, and the transaction fee on that share. */
    public record InstalmentPart(BigDecimal base, BigDecimal fee) {

        public BigDecimal total() {
            return Money.add(base, fee);
        }
    }

    /**
     * Splits what a loan costs into {@code count} instalments and puts the
     * transaction fee on each.
     *
     * <p>In whole naira, as loans are now priced: each share is the total
     * divided equally and <b>rounded up</b> to the naira, the last share takes
     * whatever is left so the shares sum to the total exactly, and the fee on
     * each is {@code feeRate} of its share, also rounded up. ₦125,000 over 12
     * weeks at 2% is ₦10,417 + ₦209 = ₦10,626 a week, the last ₦10,413 + ₦209.
     *
     * <p>Loans priced before that were split to the kobo with no fee, and are
     * split here exactly as they always were.
     */
    public static List<InstalmentPart> instalmentPlan(
            BigDecimal loanTotal, int count, BigDecimal feeRate, boolean wholeNaira) {
        List<InstalmentPart> plan = new ArrayList<>(Math.max(count, 0));
        if (count <= 0) {
            return plan;
        }
        BigDecimal rate = feeRate == null ? BigDecimal.ZERO : feeRate;
        BigDecimal exact = Money.calc(loanTotal)
                .divide(BigDecimal.valueOf(count), Money.CALC_SCALE, RoundingMode.HALF_UP);
        BigDecimal each = wholeNaira ? Money.of(exact.setScale(0, RoundingMode.CEILING)) : Money.of(exact);
        BigDecimal last = Money.subtract(loanTotal, Money.multiply(each, BigDecimal.valueOf(count - 1L)));
        if (wholeNaira && last.signum() <= 0) {
            // Only a tiny loan over very many weeks: rounding every share up
            // would leave nothing for the last, so round them down instead.
            each = Money.of(exact.setScale(0, RoundingMode.FLOOR));
            last = Money.subtract(loanTotal, Money.multiply(each, BigDecimal.valueOf(count - 1L)));
        }
        for (int i = 0; i < count; i++) {
            BigDecimal base = i == count - 1 ? last : each;
            plan.add(new InstalmentPart(base, instalmentFee(base, rate, wholeNaira)));
        }
        return plan;
    }

    /** The transaction fee on one share: {@code rate} of it, rounded up to the naira when priced in naira. */
    public static BigDecimal instalmentFee(BigDecimal base, BigDecimal rate, boolean wholeNaira) {
        BigDecimal fee = Money.calc(base).multiply(rate == null ? BigDecimal.ZERO : rate);
        return wholeNaira ? Money.of(fee.setScale(0, RoundingMode.CEILING)) : Money.of(fee);
    }

    /** The transaction fee rate on every repayment, from the settings in force. */
    public static BigDecimal repaymentFeeRate(PlatformSettings s) {
        return repaymentFeeRate(s.lending());
    }

    public static BigDecimal repaymentFeeRate(LendingTerms t) {
        return t.repaymentFeeRate() == null ? BigDecimal.ZERO : t.repaymentFeeRate();
    }

    /**
     * The management fee, also called the processing fee.
     *
     * <p>A flat fee on any loan up to <b>and including</b> the threshold. One
     * naira above it, the fee becomes a percentage of the <b>whole</b>
     * principal, never of the excess — ₦250,001 is already on the percentage.
     * The two rules meet exactly at the threshold (2% of ₦250,000 is ₦5,000),
     * so the fee curve is continuous and never jumps.
     *
     * <p>It is always deducted from the disbursement, never added to the debt.
     */
    public static BigDecimal processingFee(PlatformSettings s, BigDecimal principal) {
        return processingFee(s.lending(), principal);
    }

    /** The management fee on the standard or the loan-group terms. */
    public static BigDecimal processingFee(LendingTerms t, BigDecimal principal) {
        if (Money.lte(principal, t.processingFeeThreshold())) {
            return Money.of(t.flatProcessingFee());
        }
        return Money.of(Money.calc(principal).multiply(t.processingFeeRate()));
    }

    /**
     * What actually reaches the wallet: the loan less the management fee.
     * Nothing else comes off — the transaction fee is paid with each
     * repayment instead.
     */
    public static BigDecimal netDisbursed(PlatformSettings s, BigDecimal principal) {
        return netDisbursed(s.lending(), principal);
    }

    public static BigDecimal netDisbursed(LendingTerms t, BigDecimal principal) {
        return Money.subtract(principal, processingFee(t, principal));
    }

    /** How the fee on this loan was worked out, in words, for a receipt. */
    public static String processingFeeBasis(PlatformSettings s, BigDecimal principal) {
        return processingFeeBasis(s.lending(), principal);
    }

    public static String processingFeeBasis(LendingTerms t, BigDecimal principal) {
        if (Money.lte(principal, t.processingFeeThreshold())) {
            return "Flat fee up to " + Money.naira(t.processingFeeThreshold());
        }
        BigDecimal pct = t.processingFeeRate().multiply(BigDecimal.valueOf(100))
                .stripTrailingZeros();
        return pct.toPlainString() + "% of the whole amount";
    }

}
