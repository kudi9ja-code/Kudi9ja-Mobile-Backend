package com.quadrilateral.kudi9ja.domain.settings;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * What moved between two versions of the settings document, in words.
 *
 * <p>The audit log records the result, so a rate change reads as
 * {@code "Savings annual rate: 17% → 15%"} rather than as a diff of two blobs.
 * A person reviewing the log a year later has to be able to see what was
 * decided without reconstructing it.
 */
public final class SettingsDiff {

    private SettingsDiff() {
    }

    public static List<String> between(PlatformSettings before, PlatformSettings after) {
        List<String> changes = new ArrayList<>();

        // Savings ------------------------------------------------------------
        pct(changes, "Savings annual rate", before.getSavingsAnnualRate(), after.getSavingsAnnualRate());
        num(changes, "Minimum lock", before.getMinLockDays(), after.getMinLockDays(), " days");
        num(changes, "Maximum lock", before.getMaxLockDays(), after.getMaxLockDays(), " days");
        num(changes, "Days per year", before.getDaysPerYear(), after.getDaysPerYear(), "");
        money(changes, "Minimum savings amount", before.getMinSavingsAmount(), after.getMinSavingsAmount());
        money(changes, "Maximum savings amount", before.getMaxSavingsAmount(), after.getMaxSavingsAmount());
        pct(changes, "Target bonus (short)", before.getTargetRateShort(), after.getTargetRateShort());
        pct(changes, "Target bonus (medium)", before.getTargetRateMedium(), after.getTargetRateMedium());
        pct(changes, "Target bonus (long)", before.getTargetRateLong(), after.getTargetRateLong());
        num(changes, "Medium target tier starts", before.getTargetTierMedium(), after.getTargetTierMedium(), " months");
        num(changes, "Long target tier starts", before.getTargetTierLong(), after.getTargetTierLong(), " months");
        num(changes, "Minimum target term", before.getMinTargetMonths(), after.getMinTargetMonths(), " months");
        num(changes, "Days per savings month", before.getDaysPerSavingsMonth(), after.getDaysPerSavingsMonth(), "");

        // Lending -------------------------------------------------------------
        loanRates(changes, before, after);
        num(changes, "Maximum loan tenure", before.getMaxLoanTenureMonths(), after.getMaxLoanTenureMonths(), " months");
        money(changes, "Minimum loan", before.getMinLoanAmount(), after.getMinLoanAmount());
        money(changes, "Maximum loan", before.getMaxLoanAmount(), after.getMaxLoanAmount());
        pct(changes, "Early payoff rebate share",
                before.getEarlyPayoffRebateShare(), after.getEarlyPayoffRebateShare());
        num(changes, "Loan cancellation window",
                before.getLoanCancellationHours(), after.getLoanCancellationHours(), " hours");

        // Management fee -------------------------------------------------------
        money(changes, "Flat management fee", before.getFlatProcessingFee(), after.getFlatProcessingFee());
        money(changes, "Management fee threshold",
                before.getProcessingFeeThreshold(), after.getProcessingFeeThreshold());
        pct(changes, "Management fee rate",
                before.getLoanProcessingFeeRate(), after.getLoanProcessingFeeRate());

        // Security -------------------------------------------------------------
        num(changes, "Passcode attempts allowed",
                before.getMaxPasscodeAttempts(), after.getMaxPasscodeAttempts(), "");
        num(changes, "Idle lock", before.getLockTimeoutMinutes(), after.getLockTimeoutMinutes(), " minutes");
        num(changes, "OTP resend cooldown", before.getOtpResendSeconds(), after.getOtpResendSeconds(), " seconds");

        // Wallet ---------------------------------------------------------------
        money(changes, "Minimum pay-in", before.getMinDepositAmount(), after.getMinDepositAmount());
        money(changes, "Minimum withdrawal", before.getMinWithdrawalAmount(), after.getMinWithdrawalAmount());

        // Thrift ---------------------------------------------------------------
        money(changes, "Minimum circle contribution",
                before.getMinCircleContribution(), after.getMinCircleContribution());
        num(changes, "Minimum circle members", before.getMinCircleMembers(), after.getMinCircleMembers(), "");
        num(changes, "Maximum circle members", before.getMaxCircleMembers(), after.getMaxCircleMembers(), "");

        // Collection account ----------------------------------------------------
        text(changes, "Collection account name",
                before.getCompanyAccountName(), after.getCompanyAccountName());
        text(changes, "Collection account number",
                before.getCompanyAccountNumber(), after.getCompanyAccountNumber());
        text(changes, "Collection bank", before.getCompanyBank(), after.getCompanyBank());

        // Switches ---------------------------------------------------------------
        flag(changes, "Savings", before.isSavingsEnabled(), after.isSavingsEnabled());
        flag(changes, "Lending", before.isLendingEnabled(), after.isLendingEnabled());
        flag(changes, "Thrift", before.isThriftEnabled(), after.isThriftEnabled());
        flag(changes, "Maintenance mode", before.isMaintenanceMode(), after.isMaintenanceMode());

        return changes;
    }

    private static void loanRates(List<String> changes, PlatformSettings before, PlatformSettings after) {
        var tenures = new TreeSet<Integer>();
        tenures.addAll(before.getLoanRates().keySet());
        tenures.addAll(after.getLoanRates().keySet());
        for (Integer months : tenures) {
            BigDecimal was = before.getLoanRates().get(months);
            BigDecimal now = after.getLoanRates().get(months);
            if (was == null && now != null) {
                changes.add("Loan rate at " + months + " months: added at " + percent(now));
            } else if (was != null && now == null) {
                changes.add("Loan rate at " + months + " months: removed (was " + percent(was) + ")");
            } else if (was != null && was.compareTo(now) != 0) {
                changes.add("Loan rate at " + months + " months: " + percent(was) + " → " + percent(now));
            }
        }
    }

    private static void pct(List<String> changes, String label, BigDecimal was, BigDecimal now) {
        if (was == null || now == null || was.compareTo(now) == 0) {
            return;
        }
        changes.add(label + ": " + percent(was) + " → " + percent(now));
    }

    private static void money(List<String> changes, String label, BigDecimal was, BigDecimal now) {
        if (was == null || now == null || was.compareTo(now) == 0) {
            return;
        }
        changes.add(label + ": ₦" + was.stripTrailingZeros().toPlainString()
                + " → ₦" + now.stripTrailingZeros().toPlainString());
    }

    private static void plain(List<String> changes, String label, BigDecimal was, BigDecimal now) {
        if (was == null || now == null || was.compareTo(now) == 0) {
            return;
        }
        changes.add(label + ": " + was.stripTrailingZeros().toPlainString()
                + " → " + now.stripTrailingZeros().toPlainString());
    }

    private static void num(List<String> changes, String label, Integer was, Integer now, String unit) {
        if (Objects.equals(was, now)) {
            return;
        }
        changes.add(label + ": " + was + unit + " → " + now + unit);
    }

    private static void text(List<String> changes, String label, String was, String now) {
        if (Objects.equals(was, now)) {
            return;
        }
        changes.add(label + ": " + was + " → " + now);
    }

    private static void flag(List<String> changes, String label, boolean was, boolean now) {
        if (was == now) {
            return;
        }
        changes.add(label + ": " + (was ? "on" : "off") + " → " + (now ? "on" : "off"));
    }

    /** "17%", "12.5%" — never a rate rounded to "13%". */
    static String percent(BigDecimal rate) {
        BigDecimal pct = rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros();
        return pct.toPlainString() + "%";
    }
}
