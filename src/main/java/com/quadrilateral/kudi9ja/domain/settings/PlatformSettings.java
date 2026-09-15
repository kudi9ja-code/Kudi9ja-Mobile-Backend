package com.quadrilateral.kudi9ja.domain.settings;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The economics of Kudi9ja, as an admin can tune them.
 *
 * <p>Settings are <b>versioned, never edited in place</b>. Saving a change
 * writes a new row with the next version; the current settings are the highest
 * version. That gives three things the product needs:
 *
 * <ul>
 *   <li>a rate change can be diffed against exactly what it replaced, which is
 *       what the audit log records;
 *   <li>a running plan or loan can be reasoned about against the settings in
 *       force when it was opened;
 *   <li>a bad change can be rolled forward by re-saving the previous values,
 *       leaving the mistake visible rather than erased.
 * </ul>
 *
 * <p>A running plan or loan never reads these anyway: every loan stores the
 * rate it was priced at and every savings plan stores its own interest, so a
 * change here can never rewrite history.
 *
 * <p>Passcode length (6) and PIN length (4) are deliberately absent. Every
 * stored code is a hash of a code of that length, so changing it would lock
 * out everyone who already has one.
 */
@Entity
@Table(name = "platform_settings")
@Getter
@Setter
@NoArgsConstructor
public class PlatformSettings {

    @Id
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** Who saved this version. "System" for the seeded first version. */
    @Column(name = "created_by", nullable = false, length = 160)
    private String createdBy = "System";

    // Savings ---------------------------------------------------------------

    /** The headline annual return on Fixed Savings, paid upfront. */
    @Column(name = "savings_annual_rate", nullable = false, precision = 12, scale = 6)
    private BigDecimal savingsAnnualRate;

    /** Fixed Savings locks in days, not months. */
    @Column(name = "min_lock_days", nullable = false)
    private Integer minLockDays;

    @Column(name = "max_lock_days", nullable = false)
    private Integer maxLockDays;

    /** The year the annual savings rate is spread over. */
    @Column(name = "days_per_year", nullable = false)
    private Integer daysPerYear;

    @Column(name = "min_savings_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal minSavingsAmount;

    @Column(name = "max_savings_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal maxSavingsAmount;

    @Column(name = "target_rate_short", nullable = false, precision = 12, scale = 6)
    private BigDecimal targetRateShort;

    @Column(name = "target_rate_medium", nullable = false, precision = 12, scale = 6)
    private BigDecimal targetRateMedium;

    @Column(name = "target_rate_long", nullable = false, precision = 12, scale = 6)
    private BigDecimal targetRateLong;

    /** The month boundaries the three Target bonus rates switch at. */
    @Column(name = "target_tier_medium", nullable = false)
    private Integer targetTierMedium;

    @Column(name = "target_tier_long", nullable = false)
    private Integer targetTierLong;

    @Column(name = "min_target_months", nullable = false)
    private Integer minTargetMonths;

    /** A Target Savings month, in days. Thirty at the shipped setting. */
    @Column(name = "days_per_savings_month", nullable = false)
    private Integer daysPerSavingsMonth;

    // Lending ---------------------------------------------------------------

    /**
     * Flat interest keyed by tenure in months. Every selectable tenure has an
     * entry, and the admin panel writes straight into this table.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "platform_loan_rate",
            joinColumns = @JoinColumn(name = "settings_version"))
    @MapKeyColumn(name = "tenure_months")
    @Column(name = "flat_rate", nullable = false, precision = 12, scale = 6)
    private Map<Integer, BigDecimal> loanRates = new LinkedHashMap<>();

    @Column(name = "max_loan_tenure_months", nullable = false)
    private Integer maxLoanTenureMonths;

    @Column(name = "min_loan_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal minLoanAmount;

    @Column(name = "max_loan_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal maxLoanAmount;

    /**
     * The share of the untouched months' interest handed back on an early
     * settlement. Half at the shipped setting.
     */
    @Column(name = "early_payoff_rebate_share", nullable = false, precision = 12, scale = 6)
    private BigDecimal earlyPayoffRebateShare;

    /**
     * The change-of-mind window the Lending Agreement grants: return what was
     * received plus the fee inside it and no interest is owed.
     */
    @Column(name = "loan_cancellation_hours", nullable = false)
    private Integer loanCancellationHours;

    // Management fee --------------------------------------------------------

    @Column(name = "flat_processing_fee", nullable = false, precision = 19, scale = 2)
    private BigDecimal flatProcessingFee;

    @Column(name = "processing_fee_threshold", nullable = false, precision = 19, scale = 2)
    private BigDecimal processingFeeThreshold;

    @Column(name = "loan_processing_fee_rate", nullable = false, precision = 12, scale = 6)
    private BigDecimal loanProcessingFeeRate;

    // Security --------------------------------------------------------------

    @Column(name = "max_passcode_attempts", nullable = false)
    private Integer maxPasscodeAttempts;

    /** Idle minutes before the app locks itself. A client concern, served here. */
    @Column(name = "lock_timeout_minutes", nullable = false)
    private Integer lockTimeoutMinutes;

    @Column(name = "otp_resend_seconds", nullable = false)
    private Integer otpResendSeconds;

    // Wallet ----------------------------------------------------------------

    @Column(name = "min_deposit_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal minDepositAmount;

    @Column(name = "min_withdrawal_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal minWithdrawalAmount;

    // Thrift ----------------------------------------------------------------

    @Column(name = "min_circle_contribution", nullable = false, precision = 19, scale = 2)
    private BigDecimal minCircleContribution;

    @Column(name = "min_circle_members", nullable = false)
    private Integer minCircleMembers;

    @Column(name = "max_circle_members", nullable = false)
    private Integer maxCircleMembers;

    // Collection account ----------------------------------------------------

    /**
     * Where customers pay in. Money only reaches a wallet once an admin has
     * matched the transfer against this account's statement.
     */
    @Column(name = "company_account_name", nullable = false, length = 160)
    private String companyAccountName;

    @Column(name = "company_account_number", nullable = false, length = 32)
    private String companyAccountNumber;

    @Column(name = "company_bank", nullable = false, length = 120)
    private String companyBank;

    // Switches --------------------------------------------------------------

    @Column(name = "savings_enabled", nullable = false)
    private boolean savingsEnabled = true;

    @Column(name = "lending_enabled", nullable = false)
    private boolean lendingEnabled = true;

    @Column(name = "thrift_enabled", nullable = false)
    private boolean thriftEnabled = true;

    @Column(name = "maintenance_mode", nullable = false)
    private boolean maintenanceMode = false;

    // Derived ---------------------------------------------------------------

    /**
     * The flat rate a loan of {@code months} is charged.
     *
     * <p>A tenure with no entry of its own falls back to the nearest shorter
     * tenure that does have one, so the table can never leave a loan unpriced.
     * Every tenure the app offers should still be set explicitly, and
     * {@link SettingsValidator} refuses a save that leaves one out.
     */
    public BigDecimal loanRateFor(int months) {
        int wanted = Math.max(months, 1);
        BigDecimal exact = loanRates.get(wanted);
        if (exact != null) {
            return exact;
        }

        int bestMonths = 0;
        BigDecimal best = null;
        for (Map.Entry<Integer, BigDecimal> entry : loanRates.entrySet()) {
            if (entry.getKey() <= wanted && entry.getKey() > bestMonths) {
                bestMonths = entry.getKey();
                best = entry.getValue();
            }
        }
        if (best != null) {
            return best;
        }

        // Nothing at or below the tenure asked for: take the shortest we have.
        return new TreeMap<>(loanRates).firstEntry().getValue();
    }

    /** The bonus rate a Target plan of {@code months} earns. */
    public BigDecimal targetRateFor(int months) {
        if (months >= targetTierLong) {
            return targetRateLong;
        }
        if (months >= targetTierMedium) {
            return targetRateMedium;
        }
        return targetRateShort;
    }

    /** The tenures a customer may choose, capped by the maximum. */
    public java.util.List<Integer> loanTenures() {
        return java.util.stream.IntStream.rangeClosed(1, maxLoanTenureMonths).boxed().toList();
    }

    /** A defensive copy, sorted by tenure, for anything that reads the table. */
    public Map<Integer, BigDecimal> sortedLoanRates() {
        return new TreeMap<>(loanRates);
    }
}
