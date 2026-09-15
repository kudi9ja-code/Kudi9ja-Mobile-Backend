package com.quadrilateral.kudi9ja.domain.settings;

import com.quadrilateral.kudi9ja.common.util.Money;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The shipped defaults: version one of the settings document, matching the
 * rate card the Flutter client compiles in.
 *
 * <p>These are only ever used to seed an empty database. Once an admin has
 * saved a change, the stored document is the source of truth and these
 * constants are history.
 */
public final class SettingsDefaults {

    private SettingsDefaults() {
    }

    /**
     * Flat interest by tenure, in months.
     *
     * <p>The card is built on two rules, and both hold at every step:
     *
     * <ul>
     *   <li><b>The total always rises with the tenure</b>, so nobody is ever
     *       better off borrowing for longer than they need.
     *   <li><b>The cost per month always falls</b> — 12.50%/mo at one month,
     *       8.33% at three, 6.50% at twelve, 5.58% at twenty-four — because the
     *       fixed cost of writing a loan spreads over more months.
     * </ul>
     *
     * <p>Months 1 to 3 are the published rate card. Months 4 to 24 continue its
     * curve and are provisional: shaped deliberately, but not yet priced by the
     * business. Confirm them from the admin panel before lending against them.
     */
    public static Map<Integer, BigDecimal> loanRates() {
        Map<Integer, BigDecimal> rates = new LinkedHashMap<>();
        rates.put(1, rate("0.125"));
        rates.put(2, rate("0.17"));
        rates.put(3, rate("0.25"));
        rates.put(4, rate("0.32"));
        rates.put(5, rate("0.38"));
        rates.put(6, rate("0.45"));
        rates.put(7, rate("0.51"));
        rates.put(8, rate("0.57"));
        rates.put(9, rate("0.63"));
        rates.put(10, rate("0.68"));
        rates.put(11, rate("0.73"));
        rates.put(12, rate("0.78"));
        rates.put(13, rate("0.83"));
        rates.put(14, rate("0.88"));
        rates.put(15, rate("0.93"));
        rates.put(16, rate("0.98"));
        rates.put(17, rate("1.02"));
        rates.put(18, rate("1.07"));
        rates.put(19, rate("1.11"));
        rates.put(20, rate("1.16"));
        rates.put(21, rate("1.21"));
        rates.put(22, rate("1.25"));
        rates.put(23, rate("1.30"));
        rates.put(24, rate("1.34"));
        return rates;
    }

    /** Version one, exactly as the client ships it. */
    public static PlatformSettings first() {
        PlatformSettings s = new PlatformSettings();
        s.setVersion(1L);
        s.setCreatedBy("System (shipped defaults)");

        // Savings: 17% a year, spread over a 365-day year, locks in days.
        s.setSavingsAnnualRate(rate("0.17"));
        s.setMinLockDays(30);
        s.setMaxLockDays(1825);
        s.setDaysPerYear(365);
        s.setMinSavingsAmount(Money.of(5_000));
        s.setMaxSavingsAmount(Money.of(50_000_000));
        s.setTargetRateShort(rate("0.025"));
        s.setTargetRateMedium(rate("0.05"));
        s.setTargetRateLong(rate("0.10"));
        s.setTargetTierMedium(6);
        s.setTargetTierLong(12);
        s.setMinTargetMonths(3);
        s.setDaysPerSavingsMonth(30);

        // Lending.
        s.setLoanRates(loanRates());
        s.setMaxLoanTenureMonths(24);
        s.setMinLoanAmount(Money.of(50_000));
        s.setMaxLoanAmount(Money.of(5_000_000));
        s.setEarlyPayoffRebateShare(rate("0.5"));
        s.setLoanCancellationHours(24);

        // Management fee: flat to the threshold, 1% of the whole above it.
        s.setFlatProcessingFee(Money.of(5_000));
        s.setProcessingFeeThreshold(Money.of(500_000));
        s.setLoanProcessingFeeRate(rate("0.01"));

        // Security.
        s.setMaxPasscodeAttempts(5);
        s.setLockTimeoutMinutes(2);
        s.setOtpResendSeconds(45);

        // Wallet.
        s.setMinDepositAmount(Money.of(100));
        s.setMinWithdrawalAmount(Money.of(500));

        // Thrift.
        s.setMinCircleContribution(Money.of(1_000));
        s.setMinCircleMembers(2);
        s.setMaxCircleMembers(12);

        // The collection account customers pay into.
        s.setCompanyAccountName("Quadrilateral Technologies Ltd");
        s.setCompanyAccountNumber("1018548852");
        s.setCompanyBank("Zenith Bank");

        s.setSavingsEnabled(true);
        s.setLendingEnabled(true);
        s.setThriftEnabled(true);
        s.setMaintenanceMode(false);
        return s;
    }

    private static BigDecimal rate(String value) {
        return new BigDecimal(value).setScale(6, java.math.RoundingMode.HALF_UP);
    }
}
