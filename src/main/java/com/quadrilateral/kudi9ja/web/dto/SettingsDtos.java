package com.quadrilateral.kudi9ja.web.dto;

import com.quadrilateral.kudi9ja.domain.settings.PlatformSettings;
import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

/**
 * Platform settings, in the two shapes they are read in.
 *
 * <p>The split matters. The <b>public</b> shape is what the app displays before
 * anyone has an account: the rates on the savings calculator, the loan rate
 * card, the limits, the collection account to transfer to. The <b>full</b>
 * shape adds the operational settings — the fee schedule, the security
 * limits, the switches — which are the panel's to read and change.
 *
 * <p>Neither shape is what prices anything. A running plan or loan keeps the
 * terms it was opened on, and these values only ever apply to the next one.
 */
public final class SettingsDtos {

    private SettingsDtos() {
    }

    /**
     * What the app shows before sign-in.
     *
     * <p>{@code maintenanceMode} and the three feature switches are here on
     * purpose: an app that can see the lending switch is off can say so, rather
     * than letting a customer fill in a loan request that was always going to
     * be refused.
     */
    public record PublicSettings(
            long version,
            Savings savings,
            Lending lending,
            Wallet wallet,
            Thrift thrift,
            CollectionAccount collectionAccount,
            Switches switches,
            Security security,
            Company company) {

        public static PublicSettings from(PlatformSettings s, Company company) {
            return new PublicSettings(
                    s.getVersion() == null ? 0L : s.getVersion(),
                    new Savings(
                            s.getSavingsAnnualRate(),
                            s.getMinLockDays(),
                            s.getMaxLockDays(),
                            s.getDaysPerYear(),
                            s.getMinSavingsAmount(),
                            s.getMaxSavingsAmount(),
                            s.getTargetRateShort(),
                            s.getTargetRateMedium(),
                            s.getTargetRateLong(),
                            s.getTargetTierMedium(),
                            s.getTargetTierLong(),
                            s.getMinTargetMonths(),
                            s.getDaysPerSavingsMonth()),
                    new Lending(
                            new TreeMap<>(s.getLoanRates()),
                            s.getMaxLoanTenureMonths(),
                            s.getMinLoanAmount(),
                            s.getMaxLoanAmount(),
                            s.getFlatProcessingFee(),
                            s.getProcessingFeeThreshold(),
                            s.getLoanProcessingFeeRate(),
                            s.getEarlyPayoffRebateShare(),
                            s.getLoanCancellationHours()),
                    new Wallet(
                            s.getMinDepositAmount(),
                            s.getMinWithdrawalAmount()),
                    new Thrift(
                            s.getMinCircleContribution(),
                            s.getMinCircleMembers(),
                            s.getMaxCircleMembers()),
                    new CollectionAccount(
                            s.getCompanyBank(),
                            s.getCompanyAccountNumber(),
                            s.getCompanyAccountName()),
                    new Switches(
                            s.isSavingsEnabled(),
                            s.isLendingEnabled(),
                            s.isThriftEnabled(),
                            s.isMaintenanceMode()),
                    new Security(
                            s.getMaxPasscodeAttempts(),
                            s.getLockTimeoutMinutes(),
                            s.getOtpResendSeconds(),
                            // Not settable, deliberately. Every stored code is a
                            // hash of a code this length; changing it would lock
                            // out everyone who already has one.
                            6,
                            4),
                    company);
        }
    }

    public record Savings(
            BigDecimal annualRate,
            int minLockDays,
            int maxLockDays,
            int daysPerYear,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            BigDecimal targetRateShort,
            BigDecimal targetRateMedium,
            BigDecimal targetRateLong,
            int targetTierMedium,
            int targetTierLong,
            int minTargetMonths,
            int daysPerSavingsMonth) {
    }

    /**
     * @param rates the whole card, tenure → flat rate. Published in full so the
     *              app never guesses a price and never has to interpolate one.
     */
    public record Lending(
            Map<Integer, BigDecimal> rates,
            int maxTenureMonths,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            BigDecimal flatProcessingFee,
            BigDecimal processingFeeThreshold,
            BigDecimal processingFeeRate,
            BigDecimal earlyPayoffRebateShare,
            int cancellationHours) {
    }

    public record Wallet(
            BigDecimal minDepositAmount,
            BigDecimal minWithdrawalAmount) {
    }

    public record Thrift(
            BigDecimal minContribution,
            int minMembers,
            int maxMembers) {
    }

    /**
     * Where customers transfer to.
     *
     * <p>This is the <b>company's</b> account, and the same one for everybody.
     * It is not the customer's account, because Kudi9ja issues no account
     * numbers — which is precisely why every payment carries its own reference.
     */
    public record CollectionAccount(String bank, String accountNumber, String accountName) {
    }

    public record Switches(
            boolean savingsEnabled,
            boolean lendingEnabled,
            boolean thriftEnabled,
            boolean maintenanceMode) {
    }

    public record Security(
            int maxPasscodeAttempts,
            int lockTimeoutMinutes,
            int otpResendSeconds,
            int passcodeLength,
            int pinLength) {
    }

    /**
     * The legal entity behind the product.
     *
     * <p>Named rather than the product, because every contract, receipt and
     * legal document names the company. The app's onboarding footer carries the
     * name and RC number in place of any regulatory claim — Kudi9ja asserts no
     * licence, no CBN compliance and no NDIC cover, and the documents deny all
     * three rather than claiming them.
     */
    public record Company(
            String legalName,
            String rcNumber,
            String productName,
            String supportEmail,
            String legalEmail,
            String privacyEmail,
            String supportPhone,
            java.util.List<String> whatsapp) {
    }

    /**
     * Saving the settings back.
     *
     * <p>The whole document is sent, not a patch. A settings change is diffed
     * against what is in force and the diff is what goes to the audit log, and
     * a diff against a partial document cannot tell a field that was set to
     * nothing from a field that was not sent.
     *
     * <p>{@code expectedVersion} is the version the panel loaded. Two admins
     * editing the rate card at the same time would otherwise silently overwrite
     * each other, and the second one would never know.
     *
     * <p>The version, timestamp and author on the incoming document are ignored
     * — they are stamped on save from the version in force and the caller's own
     * grant, so a client cannot backdate a change or attribute it to somebody
     * else.
     */
    public record SaveRequest(
            Long expectedVersion,
            PlatformSettings settings) {
    }
}
