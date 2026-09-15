package com.quadrilateral.kudi9ja.domain.settings;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.common.util.Money;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a settings document must satisfy before it may be saved.
 *
 * <p>These are not style checks. A rate table with a hole in it leaves a loan
 * unpriced; a minimum above its maximum makes a product unreachable; a savings
 * rate of zero quietly stops paying customers what the app advertises. The
 * admin panel is the only way in, so this is the only place that can stop them.
 */
public final class SettingsValidator {

    private SettingsValidator() {
    }

    public static void validate(PlatformSettings s) {
        Map<String, Object> problems = new LinkedHashMap<>();

        // Savings ------------------------------------------------------------
        if (!Money.isPositive(s.getSavingsAnnualRate())) {
            problems.put("savingsAnnualRate", "The savings rate must be above zero.");
        }
        if (s.getMinLockDays() >= s.getMaxLockDays()) {
            problems.put("minLockDays", "The shortest lock must be shorter than the longest.");
        }
        if (s.getMinLockDays() < 1) {
            problems.put("minLockDays", "A lock must run for at least a day.");
        }
        if (s.getDaysPerYear() < 360 || s.getDaysPerYear() > 366) {
            problems.put("daysPerYear", "A year is 360 to 366 days.");
        }
        if (Money.gte(s.getMinSavingsAmount(), s.getMaxSavingsAmount())) {
            problems.put("minSavingsAmount", "The smallest plan must be smaller than the largest.");
        }
        if (s.getTargetTierMedium() >= s.getTargetTierLong()) {
            problems.put("targetTierMedium", "The medium tier must start before the long tier.");
        }
        if (s.getMinTargetMonths() < 1) {
            problems.put("minTargetMonths", "A target plan must run for at least a month.");
        }
        if (s.getDaysPerSavingsMonth() < 28 || s.getDaysPerSavingsMonth() > 31) {
            problems.put("daysPerSavingsMonth", "A savings month is 28 to 31 days.");
        }
        requireNonNegativeRate(problems, "targetRateShort", s.getTargetRateShort());
        requireNonNegativeRate(problems, "targetRateMedium", s.getTargetRateMedium());
        requireNonNegativeRate(problems, "targetRateLong", s.getTargetRateLong());

        // Lending ------------------------------------------------------------
        if (Money.gte(s.getMinLoanAmount(), s.getMaxLoanAmount())) {
            problems.put("minLoanAmount", "The smallest loan must be smaller than the largest.");
        }
        if (s.getMaxLoanTenureMonths() < 1) {
            problems.put("maxLoanTenureMonths", "Loans must run for at least a month.");
        }
        // A rate for every selectable tenure. A gap would silently fall back to
        // a shorter tenure's rate and underprice the loan.
        for (int months = 1; months <= s.getMaxLoanTenureMonths(); months++) {
            BigDecimal rate = s.getLoanRates().get(months);
            if (rate == null) {
                problems.put("loanRates." + months, "Month " + months + " has no rate.");
            } else if (rate.signum() < 0) {
                problems.put("loanRates." + months, "A rate cannot be negative.");
            }
        }
        BigDecimal share = s.getEarlyPayoffRebateShare();
        if (share == null || share.signum() < 0 || share.compareTo(BigDecimal.ONE) > 0) {
            problems.put("earlyPayoffRebateShare", "The rebate share runs from 0 to 1.");
        }
        if (s.getLoanCancellationHours() < 0) {
            problems.put("loanCancellationHours", "The change-of-mind window cannot be negative.");
        }

        // Management fee ------------------------------------------------------
        if (Money.isZeroOrLess(s.getProcessingFeeThreshold())) {
            problems.put("processingFeeThreshold", "The fee threshold must be above zero.");
        }
        if (s.getFlatProcessingFee().signum() < 0) {
            problems.put("flatProcessingFee", "The flat fee cannot be negative.");
        }
        requireNonNegativeRate(problems, "loanProcessingFeeRate", s.getLoanProcessingFeeRate());

        // Security -------------------------------------------------------------
        if (s.getMaxPasscodeAttempts() < 1) {
            problems.put("maxPasscodeAttempts", "At least one attempt must be allowed.");
        }
        if (s.getLockTimeoutMinutes() < 1) {
            problems.put("lockTimeoutMinutes", "The idle lock must be at least a minute.");
        }
        if (s.getOtpResendSeconds() < 1) {
            problems.put("otpResendSeconds", "The resend cooldown must be at least a second.");
        }

        // Wallet ---------------------------------------------------------------
        if (Money.isZeroOrLess(s.getMinDepositAmount())) {
            problems.put("minDepositAmount", "The smallest pay-in must be above zero.");
        }
        if (Money.isZeroOrLess(s.getMinWithdrawalAmount())) {
            problems.put("minWithdrawalAmount", "The smallest withdrawal must be above zero.");
        }

        // Thrift ---------------------------------------------------------------
        if (s.getMinCircleMembers() < 2) {
            problems.put("minCircleMembers", "A circle needs at least two people.");
        }
        if (s.getMinCircleMembers() >= s.getMaxCircleMembers()) {
            problems.put("minCircleMembers", "The smallest circle must be smaller than the largest.");
        }
        if (Money.isZeroOrLess(s.getMinCircleContribution())) {
            problems.put("minCircleContribution", "A contribution must be above zero.");
        }

        // Collection account ---------------------------------------------------
        requireText(problems, "companyAccountName", s.getCompanyAccountName());
        requireText(problems, "companyAccountNumber", s.getCompanyAccountNumber());
        requireText(problems, "companyBank", s.getCompanyBank());

        if (!problems.isEmpty()) {
            String first = problems.values().iterator().next().toString();
            throw new ApiException(ErrorCode.SETTINGS_INVALID, first, problems);
        }
    }

    private static void requireNonNegativeRate(Map<String, Object> problems, String key, BigDecimal rate) {
        if (rate == null || rate.signum() < 0) {
            problems.put(key, "A rate cannot be negative.");
        }
    }

    private static void requireText(Map<String, Object> problems, String key, String value) {
        if (value == null || value.isBlank()) {
            problems.put(key, "This cannot be left empty.");
        }
    }
}
