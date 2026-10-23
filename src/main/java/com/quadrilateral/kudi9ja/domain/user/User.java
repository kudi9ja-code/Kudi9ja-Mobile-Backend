package com.quadrilateral.kudi9ja.domain.user;

import com.quadrilateral.kudi9ja.common.util.Reference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A Kudi9ja account.
 *
 * <p><b>No account number is issued.</b> Money leaves the wallet to a bank
 * account the customer already holds, in their own name, which is what
 * {@link #payoutBank} and {@link #payoutAccountNumber} hold. What the customer
 * has instead is a {@link #getCustomerRef() customer reference} for matching
 * payments — it is not payable into.
 *
 * <p>Nothing on this row is ever returned whole. The secret answer and the
 * three credential hashes never leave the server, and the BVN and NIN are
 * masked to their last four digits by the mapper that builds the response.
 */
@Entity
@Table(
        name = "app_user",
        indexes = {
                @Index(name = "ix_user_email", columnList = "email", unique = true),
                @Index(name = "ix_user_customer_ref", columnList = "customer_ref", unique = true),
                @Index(name = "ix_user_phone", columnList = "phone"),
                @Index(name = "ix_user_status", columnList = "account_status")
        })
@Getter
@Setter
@NoArgsConstructor
public class User {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * {@code K9-A1B2C3}: the first six hex characters of the id, uppercased.
     * Stored rather than derived on read so it can be indexed and matched
     * against a bank narration in one query.
     */
    @Column(name = "customer_ref", nullable = false, length = 16, updatable = false)
    private String customerRef;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    /** Lowercased on the way in. It is the identity the admin panel matches on. */
    /** Blank only on an account the team opened that the customer has not yet activated. */
    @Column(name = "email", length = 190)
    private String email;

    /**
     * Required at sign-up and enforced there, but nullable in the schema.
     *
     * <p>An erasure has to be able to clear it once the retention period ends,
     * and a not-null column would leave the choice between keeping a real phone
     * number for ever or writing a fake one. Both are worse than a null.
     */
    @Column(name = "phone", length = 20)
    private String phone;

    /**
     * Nullable for the same reason as the phone, and more strongly: no date is
     * a truthful stand-in for an erased one, and anything written here would be
     * read back as a fact.
     */
    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "gender", length = 32)
    private String gender;

    /** Never returned whole. The response mapper masks it to its last four. */
    @Column(name = "bvn", length = 11)
    private String bvn;

    @Column(name = "nin", length = 11)
    private String nin;

    @Column(name = "address", length = 400)
    private String address;

    /** One of the thirty-six states, or the FCT. */
    @Column(name = "state", length = 60)
    private String state;

    // Payout destination -----------------------------------------------------

    /**
     * The customer's own bank account. The name on it must match
     * {@link #fullName}: the Terms promise payouts only to the customer, so a
     * name enquiry runs before it is accepted and a mismatch is refused.
     */
    @Column(name = "payout_bank", length = 120)
    private String payoutBank;

    @Column(name = "payout_account_number", length = 10)
    private String payoutAccountNumber;

    /** The name the bank returned on that account, kept as the evidence. */
    @Column(name = "payout_account_name", length = 160)
    private String payoutAccountName;

    @Column(name = "payout_verified_at")
    private Instant payoutVerifiedAt;

    // Verification -----------------------------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "kyc_tier", nullable = false, length = 16)
    private KycTier kycTier = KycTier.TIER0;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    /**
     * Email is the only channel verified. The phone is collected so support can
     * reach the customer, not as a second factor, so this stays false until a
     * deliberate SMS step is added.
     */
    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified = false;

    @Column(name = "bvn_verified_at")
    private Instant bvnVerifiedAt;

    @Column(name = "nin_verified_at")
    private Instant ninVerifiedAt;

    // Credentials ------------------------------------------------------------

    /** Argon2id with a per-user salt and a pepper from secret config. */
    @Column(name = "password_hash", nullable = false, length = 300)
    private String passwordHash;

    /** The six-digit sign-in passcode. */
    @Column(name = "passcode_hash", length = 300)
    private String passcodeHash;

    /** The four-digit transaction PIN. Gates every money move. */
    @Column(name = "pin_hash", length = 300)
    private String pinHash;

    @Column(name = "security_question", length = 200)
    private String securityQuestion;

    @Column(name = "security_answer_hash", length = 300)
    private String securityAnswerHash;

    /**
     * Biometrics are performed by the device. The template never leaves it and
     * never reaches us: this flag only records that the customer switched the
     * local unlock on, and it is not an authentication factor here.
     */
    @Column(name = "biometrics_enabled", nullable = false)
    private boolean biometricsEnabled = false;

    @Column(name = "failed_passcode_attempts", nullable = false)
    private int failedPasscodeAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    // Preferences ------------------------------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "theme_mode", nullable = false, length = 16)
    private ThemeMode themeMode = ThemeMode.DARK;

    @Column(name = "hide_balance", nullable = false)
    private boolean hideBalance = false;

    /** Whether repayments may be pulled from the wallet automatically. */
    @Column(name = "auto_debit", nullable = false)
    private boolean autoDebit = false;

    // Lifecycle --------------------------------------------------------------

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false, length = 24)
    private AccountStatus accountStatus = AccountStatus.ACTIVE;

    /** Why an admin flagged or froze this account, for the customer record. */
    @Column(name = "status_note", length = 500)
    private String statusNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Drives the dormancy sweep: twelve months of silence freezes an account. */
    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    /**
     * Set when the account is closed. The row stays: AML retention requires
     * identity and transaction records for at least five years after the
     * relationship ends, and a deletion request cannot override that.
     */
    @Column(name = "closed_at")
    private Instant closedAt;

    /** The date the retained record may finally be erased. */
    @Column(name = "retain_until")
    private Instant retainUntil;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    // Behaviour --------------------------------------------------------------

    /** Mints the id and the customer reference together, so they cannot drift. */
    public static User createWithNewId() {
        User user = new User();
        user.id = UUID.randomUUID();
        user.customerRef = Reference.customerRef(user.id);
        return user;
    }

    /** Both payout fields present. Nothing may be withdrawn without them. */
    public boolean hasPayoutAccount() {
        return payoutBank != null && !payoutBank.isBlank()
                && payoutAccountNumber != null && !payoutAccountNumber.isBlank();
    }

    public boolean isFullyVerified() {
        return kycTier == KycTier.TIER2;
    }

    /** @return the age, or -1 once the date of birth has been erased. */
    public int age() {
        return dateOfBirth == null ? -1 : Period.between(dateOfBirth, LocalDate.now()).getYears();
    }

    /** Whether a lockout from failed passcode attempts is still in force. */
    public boolean isLockedOut(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public void clearLockout() {
        failedPasscodeAttempts = 0;
        lockedUntil = null;
    }

    public void touch(Instant now) {
        lastActiveAt = now;
    }
}
