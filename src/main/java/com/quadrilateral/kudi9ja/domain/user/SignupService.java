package com.quadrilateral.kudi9ja.domain.user;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.config.Kudi9jaProperties;
import com.quadrilateral.kudi9ja.domain.admin.AdminBootstrapService;
import com.quadrilateral.kudi9ja.domain.kyc.KycService;
import com.quadrilateral.kudi9ja.domain.kyc.OtpPurpose;
import com.quadrilateral.kudi9ja.domain.kyc.OtpService;
import com.quadrilateral.kudi9ja.domain.legal.LegalDocumentKind;
import com.quadrilateral.kudi9ja.domain.legal.LegalService;
import com.quadrilateral.kudi9ja.domain.loan.LoanImportService;
import com.quadrilateral.kudi9ja.domain.notification.NotificationService;
import com.quadrilateral.kudi9ja.domain.notification.NotifyKind;
import com.quadrilateral.kudi9ja.domain.wallet.LedgerService;
import com.quadrilateral.kudi9ja.security.crypto.PasswordStrength;
import com.quadrilateral.kudi9ja.security.crypto.SecretHasher;
import com.quadrilateral.kudi9ja.web.dto.SignupDtos;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The eight gated steps of opening an account.
 *
 * <p>Every gate is enforced here rather than in the wizard. The client runs the
 * same sequence, but a client can submit step seven without having passed step
 * three, and a client that can lie about having verified an email will.
 *
 * <p>What a new account gets: a wallet at <b>zero</b>, an empty ledger, and
 * nothing else. There is no sign-up bonus and no free credit. Every naira in a
 * Kudi9ja wallet was paid in, earned on a savings plan, or borrowed.
 */
@Service
public class SignupService {

    private static final Logger log = LoggerFactory.getLogger(SignupService.class);
    private static final Duration DRAFT_TTL = Duration.ofHours(24);
    private static final int MINIMUM_AGE = 18;

    private final SignupDraftRepository drafts;
    private final UserRepository users;
    private final SecretHasher hasher;
    private final OtpService otp;
    private final KycService kyc;
    private final LegalService legal;
    private final LedgerService ledger;
    private final NotificationService notifications;
    private final AdminBootstrapService adminBootstrap;
    private final LoanImportService loanImports;
    private final Kudi9jaProperties properties;

    public SignupService(
            SignupDraftRepository drafts,
            UserRepository users,
            SecretHasher hasher,
            OtpService otp,
            KycService kyc,
            LegalService legal,
            LedgerService ledger,
            NotificationService notifications,
            AdminBootstrapService adminBootstrap,
            LoanImportService loanImports,
            Kudi9jaProperties properties) {
        this.drafts = drafts;
        this.users = users;
        this.hasher = hasher;
        this.otp = otp;
        this.kyc = kyc;
        this.legal = legal;
        this.ledger = ledger;
        this.notifications = notifications;
        this.adminBootstrap = adminBootstrap;
        this.loanImports = loanImports;
        this.properties = properties;
    }

    // ── Step 1: personal details ───────────────────────────────────────────

    /**
     * Starts or restarts a signup.
     *
     * <p>Re-submitting with the same email resumes the existing draft rather
     * than starting a second one, so a customer who backs out of step four and
     * comes in again does not lose their verified email.
     */
    @Transactional
    public SignupDraft submitPersonal(SignupDtos.PersonalRequest request, String device, String ipAddress) {
        String email = normaliseEmail(request.email());
        String phone = request.phone().trim();

        int age = Period.between(request.dateOfBirth(), LocalDate.now()).getYears();
        if (age < MINIMUM_AGE) {
            throw new ApiException(
                    ErrorCode.UNDERAGE,
                    "You have to be " + MINIMUM_AGE + " or over to open a Kudi9ja account.");
        }
        if (request.dateOfBirth().isBefore(LocalDate.now().minusYears(120))) {
            throw ApiException.validation("Check that date of birth.");
        }

        if (users.existsByEmailIgnoreCase(email)) {
            throw new ApiException(
                    ErrorCode.EMAIL_TAKEN,
                    "There is already an account with that email address. Sign in instead.");
        }
        if (users.existsByPhone(phone)) {
            throw new ApiException(
                    ErrorCode.PHONE_TAKEN,
                    "There is already an account with that phone number.");
        }

        SignupDraft draft = drafts.findFirstByEmailAndCompletedAtIsNullOrderByCreatedAtDesc(email)
                .filter(existing -> existing.isUsable(Instant.now()))
                .orElseGet(() -> SignupDraft.start(email, Instant.now().plus(DRAFT_TTL)));

        draft.setFullName(request.fullName().trim());
        draft.setPhone(phone);
        draft.setDateOfBirth(request.dateOfBirth());
        draft.setGender(request.gender().trim());
        draft.setDevice(device);
        draft.setIpAddress(ipAddress);
        draft.advanceTo(SignupStep.PERSONAL);

        SignupDraft saved = drafts.save(draft);

        // Step two follows immediately, so the code goes out now rather than
        // making the client ask for it.
        otp.issue(email, OtpPurpose.SIGNUP_EMAIL, draft.getFullName(), null);
        return saved;
    }

    // ── Step 2: email verification ─────────────────────────────────────────

    /**
     * Marks the email verified.
     *
     * <p>Email is the only channel verified. There is no SMS step: the phone is
     * collected so support can reach the customer, not as a second factor.
     */
    @Transactional
    public SignupDraft verifyEmail(UUID draftId, String code) {
        SignupDraft draft = require(draftId);
        requireAtLeast(draft, SignupStep.PERSONAL);

        otp.verify(draft.getEmail(), OtpPurpose.SIGNUP_EMAIL, code);
        draft.setEmailVerifiedAt(Instant.now());
        draft.advanceTo(SignupStep.EMAIL_VERIFIED);
        return drafts.save(draft);
    }

    /** Sends the signup code again, subject to the resend cooldown. */
    @Transactional
    public OtpService.Issued resendEmailCode(UUID draftId) {
        SignupDraft draft = require(draftId);
        return otp.issue(draft.getEmail(), OtpPurpose.SIGNUP_EMAIL, draft.getFullName(), null);
    }

    // ── Step 3: identity ───────────────────────────────────────────────────

    /**
     * Checks the BVN and NIN against the issuing institutions.
     *
     * <p>The client fakes this with a delay that always passes. Here both are
     * checked for real, and the name and date of birth returned must match what
     * was typed — a BVN that belongs to somebody else is precisely what this
     * step exists to catch.
     */
    @Transactional
    public SignupDraft submitIdentity(UUID draftId, SignupDtos.IdentityRequest request) {
        SignupDraft draft = require(draftId);
        requireAtLeast(draft, SignupStep.EMAIL_VERIFIED);

        String bvn = request.bvn().trim();
        String nin = request.nin().trim();

        if (users.existsByBvn(bvn)) {
            throw new ApiException(
                    ErrorCode.BVN_TAKEN,
                    "There is already an account using that BVN. A person may hold one Kudi9ja account.");
        }
        if (users.existsByNin(nin)) {
            throw new ApiException(
                    ErrorCode.NIN_TAKEN,
                    "There is already an account using that NIN. A person may hold one Kudi9ja account.");
        }
        if (!NigerianStates.isKnown(request.state())) {
            throw ApiException.validation("Choose a state from the list.");
        }

        kyc.verifyBvn(bvn, draft.getFullName(), draft.getDateOfBirth());
        kyc.verifyNin(nin, draft.getFullName(), draft.getDateOfBirth());

        draft.setBvn(bvn);
        draft.setNin(nin);
        draft.setAddress(request.address().trim());
        draft.setState(request.state().trim());
        draft.setIdentityVerifiedAt(Instant.now());
        draft.advanceTo(SignupStep.IDENTITY);
        return drafts.save(draft);
    }

    // ── Step 4: payout account ─────────────────────────────────────────────

    /**
     * Resolves the nominated account and refuses one held in another name.
     *
     * <p>Signup requires a payout account today. That is a product decision
     * flagged for the business — an account could be opened without one and the
     * customer prompted before their first withdrawal — but until it changes,
     * the requirement is enforced here.
     */
    @Transactional
    public SignupDraft submitPayout(UUID draftId, SignupDtos.PayoutRequest request) {
        SignupDraft draft = require(draftId);
        requireAtLeast(draft, SignupStep.IDENTITY);

        String accountName = kyc.verifyPayoutAccount(
                request.bank(), request.accountNumber(), draft.getFullName());

        draft.setPayoutBank(request.bank().trim());
        draft.setPayoutAccountNumber(request.accountNumber().trim());
        draft.setPayoutAccountName(accountName);
        draft.advanceTo(SignupStep.PAYOUT);
        return drafts.save(draft);
    }

    // ── Step 5: password ───────────────────────────────────────────────────

    @Transactional
    public SignupDraft submitPassword(UUID draftId, SignupDtos.PasswordRequest request) {
        SignupDraft draft = require(draftId);
        requireAtLeast(draft, SignupStep.PAYOUT);

        PasswordStrength.requireStrongPassword(request.password());

        draft.setPasswordHash(hasher.hash(request.password()));
        draft.setSecurityQuestion(request.securityQuestion().trim());
        // Answers are compared case- and space-insensitively, so they are
        // normalised before hashing rather than at every comparison.
        draft.setSecurityAnswerHash(hasher.hash(normaliseAnswer(request.securityAnswer())));
        draft.advanceTo(SignupStep.PASSWORD);
        return drafts.save(draft);
    }

    // ── Step 6: sign-in passcode ───────────────────────────────────────────

    @Transactional
    public SignupDraft submitPasscode(UUID draftId, SignupDtos.SetPasscodeRequest request) {
        SignupDraft draft = require(draftId);
        requireAtLeast(draft, SignupStep.PASSWORD);

        if (!request.passcode().equals(request.confirmPasscode())) {
            throw ApiException.validation("Those two passcodes are not the same.");
        }
        PasswordStrength.requireValidPasscode(request.passcode());

        draft.setPasscodeHash(hasher.hash(request.passcode()));
        draft.advanceTo(SignupStep.PASSCODE);
        return drafts.save(draft);
    }

    // ── Step 7: transaction PIN ────────────────────────────────────────────

    @Transactional
    public SignupDraft submitPin(UUID draftId, SignupDtos.SetPinRequest request) {
        SignupDraft draft = require(draftId);
        requireAtLeast(draft, SignupStep.PASSCODE);

        if (!request.pin().equals(request.confirmPin())) {
            throw ApiException.validation("Those two PINs are not the same.");
        }
        PasswordStrength.requireValidPin(request.pin());

        draft.setPinHash(hasher.hash(request.pin()));
        draft.advanceTo(SignupStep.PIN);
        return drafts.save(draft);
    }

    // ── Step 8: review and open the account ────────────────────────────────

    /**
     * Creates the account.
     *
     * <p>The acceptance of all three documents is recorded against the version
     * the review screen showed, with the timestamp and the device: the Terms
     * rely on that record as evidence under the Evidence Act 2011.
     *
     * <p>The account opens at tier one. Tier two follows from the BVN and NIN
     * checks that already passed at step three, so it is granted here — but it
     * is granted because those checks passed, not because the client asked.
     */
    @Transactional
    public User complete(UUID draftId, SignupDtos.ReviewRequest request) {
        SignupDraft draft = require(draftId);
        requireAtLeast(draft, SignupStep.PIN);

        if (!Boolean.TRUE.equals(request.accepted())) {
            throw new ApiException(
                    ErrorCode.LEGAL_ACCEPTANCE_REQUIRED,
                    "You have to accept the Terms of Service, Privacy Policy and Lending Agreement "
                            + "to open an account.");
        }

        // Re-check the things that could have changed while the wizard ran.
        if (users.existsByEmailIgnoreCase(draft.getEmail())) {
            throw new ApiException(ErrorCode.EMAIL_TAKEN, "There is already an account with that email address.");
        }
        if (users.existsByPhone(draft.getPhone())) {
            throw new ApiException(ErrorCode.PHONE_TAKEN, "There is already an account with that phone number.");
        }
        if (users.existsByBvn(draft.getBvn()) || users.existsByNin(draft.getNin())) {
            throw new ApiException(ErrorCode.BVN_TAKEN, "There is already an account using that identity.");
        }

        User user = User.createWithNewId();
        user.setFullName(draft.getFullName());
        user.setEmail(draft.getEmail());
        user.setPhone(draft.getPhone());
        user.setDateOfBirth(draft.getDateOfBirth());
        user.setGender(draft.getGender());
        user.setBvn(draft.getBvn());
        user.setNin(draft.getNin());
        user.setAddress(draft.getAddress());
        user.setState(draft.getState());
        user.setPayoutBank(draft.getPayoutBank());
        user.setPayoutAccountNumber(draft.getPayoutAccountNumber());
        user.setPayoutAccountName(draft.getPayoutAccountName());
        user.setPayoutVerifiedAt(Instant.now());
        user.setPasswordHash(draft.getPasswordHash());
        user.setPasscodeHash(draft.getPasscodeHash());
        user.setPinHash(draft.getPinHash());
        user.setSecurityQuestion(draft.getSecurityQuestion());
        user.setSecurityAnswerHash(draft.getSecurityAnswerHash());
        user.setEmailVerified(true);
        user.setPhoneVerified(false);
        user.setBvnVerifiedAt(draft.getIdentityVerifiedAt());
        user.setNinVerifiedAt(draft.getIdentityVerifiedAt());
        user.setKycTier(KycTier.TIER2);
        user.setThemeMode(ThemeMode.DARK);
        user.setAccountStatus(AccountStatus.ACTIVE);
        user.setCreatedAt(Instant.now());
        user.setLastActiveAt(Instant.now());

        User saved = users.save(user);

        // A wallet at zero and an empty ledger. Nothing is given away.
        ledger.openWallet(saved.getId());

        // Anything lent to this person on paper before they had an account
        // lands on it now, in the same transaction: the first screen they see
        // must not say "nothing owed" to somebody who owes.
        loanImports.claimFor(saved);

        legal.acceptAll(
                saved.getId(),
                parseAcceptedVersions(request.acceptedVersions()),
                draft.getDevice(),
                draft.getIpAddress());

        draft.setCompletedAt(Instant.now());
        draft.setUserId(saved.getId());
        draft.advanceTo(SignupStep.COMPLETE);
        // The draft has served its purpose and holds identity documents; the
        // credentials on it are cleared now rather than at expiry.
        draft.setPasswordHash(null);
        draft.setPasscodeHash(null);
        draft.setPinHash(null);
        draft.setSecurityAnswerHash(null);
        draft.setBvn(null);
        draft.setNin(null);
        drafts.save(draft);

        adminBootstrap.grantSeededOwnerIfMatching(saved);

        notifications.push(
                saved.getId(),
                NotifyKind.GENERAL,
                "Welcome to Kudi9ja",
                "Your account is open. Your customer reference is " + saved.getCustomerRef()
                        + " — quote it when you pay in. It is not an account number and money cannot be "
                        + "sent to it directly.");

        log.info("Opened account {} ({})", saved.getCustomerRef(), saved.getId());
        return saved;
    }

    /** Housekeeping: an abandoned draft holds identity documents. */
    @Transactional
    public int purgeExpiredDrafts(Instant before) {
        return drafts.deleteExpiredBefore(before);
    }

    @Transactional(readOnly = true)
    public SignupDraft draft(UUID draftId) {
        return require(draftId);
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private SignupDraft require(UUID draftId) {
        SignupDraft draft = drafts.findById(draftId)
                .orElseThrow(() -> ApiException.notFound("That signup"));
        if (draft.getCompletedAt() != null) {
            throw new ApiException(
                    ErrorCode.CONFLICT, "That account has already been opened. Sign in instead.");
        }
        if (!draft.isUsable(Instant.now())) {
            throw new ApiException(
                    ErrorCode.CONFLICT, "That signup has expired. Start again — it only takes a few minutes.");
        }
        return draft;
    }

    private static void requireAtLeast(SignupDraft draft, SignupStep required) {
        if (!draft.getStep().reached(required)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Finish the earlier steps first.",
                    Map.of("currentStep", draft.getStep().name(), "requiredStep", required.name()));
        }
    }

    private Map<LegalDocumentKind, String> parseAcceptedVersions(Map<String, String> raw) {
        Map<LegalDocumentKind, String> parsed = new EnumMap<>(LegalDocumentKind.class);
        if (raw != null) {
            raw.forEach((id, version) -> {
                try {
                    parsed.put(LegalDocumentKind.fromId(id), version);
                } catch (ApiException ignored) {
                    // An unknown document id is simply not one of the three we
                    // require; the check below will catch anything missing.
                }
            });
        }
        return parsed;
    }

    static String normaliseEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** Security answers are compared without regard to case or spacing. */
    static String normaliseAnswer(String answer) {
        return answer.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
