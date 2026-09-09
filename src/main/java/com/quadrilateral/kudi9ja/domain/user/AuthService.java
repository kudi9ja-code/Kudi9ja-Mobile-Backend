package com.quadrilateral.kudi9ja.domain.user;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.config.Kudi9jaProperties;
import com.quadrilateral.kudi9ja.domain.admin.AdminRole;
import com.quadrilateral.kudi9ja.domain.admin.AdminUser;
import com.quadrilateral.kudi9ja.domain.admin.AdminUserRepository;
import com.quadrilateral.kudi9ja.domain.kyc.OtpPurpose;
import com.quadrilateral.kudi9ja.domain.kyc.OtpService;
import com.quadrilateral.kudi9ja.domain.notification.NotificationService;
import com.quadrilateral.kudi9ja.domain.notification.NotifyKind;
import com.quadrilateral.kudi9ja.domain.settings.SettingsService;
import com.quadrilateral.kudi9ja.security.auth.UserSession;
import com.quadrilateral.kudi9ja.security.auth.UserSessionRepository;
import com.quadrilateral.kudi9ja.security.crypto.PasswordStrength;
import com.quadrilateral.kudi9ja.security.crypto.SecretHasher;
import com.quadrilateral.kudi9ja.security.token.TokenService;
import com.quadrilateral.kudi9ja.security.token.TokenType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Signing in, staying signed in, and proving it is still you.
 *
 * <p>Three separate things, deliberately not conflated:
 *
 * <ul>
 *   <li><b>Sign-in</b> — email and password. Establishes the session.
 *   <li><b>Passcode</b> — six digits, on every reopen. Confirms the phone is in
 *       the right hands. It is not an authentication factor on its own and buys
 *       nothing without a live session.
 *   <li><b>PIN</b> — four digits, before every money movement. Verified here,
 *       never taken on the client's word that it was entered.
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final UserSessionRepository sessions;
    private final AdminUserRepository admins;
    private final SecretHasher hasher;
    private final TokenService tokens;
    private final OtpService otp;
    private final SettingsService settings;
    private final NotificationService notifications;
    private final Kudi9jaProperties properties;

    public AuthService(
            UserRepository users,
            UserSessionRepository sessions,
            AdminUserRepository admins,
            SecretHasher hasher,
            TokenService tokens,
            OtpService otp,
            SettingsService settings,
            NotificationService notifications,
            Kudi9jaProperties properties) {
        this.users = users;
        this.sessions = sessions;
        this.admins = admins;
        this.hasher = hasher;
        this.tokens = tokens;
        this.otp = otp;
        this.settings = settings;
        this.notifications = notifications;
        this.properties = properties;
    }

    // ── Sign in ────────────────────────────────────────────────────────────

    /**
     * Email and password.
     *
     * <p>A wrong email and a wrong password produce the same refusal, and both
     * do the same amount of work: telling an attacker which addresses hold
     * accounts is a gift, and answering faster for an unknown address tells
     * them anyway.
     */
    @Transactional
    public Signed signIn(String email, String password, String device, String ipAddress) {
        String normalised = SignupService.normaliseEmail(email);
        Instant now = Instant.now();

        User user = users.findByEmailIgnoreCase(normalised).orElse(null);
        if (user == null) {
            // Spend the same time as a real verification would, so the absence
            // of an account cannot be read off the response time.
            hasher.matches(password, DUMMY_HASH);
            throw new ApiException(ErrorCode.BAD_CREDENTIALS, "That email or password is not right.");
        }

        if (!user.getAccountStatus().canSignIn()) {
            throw new ApiException(
                    ErrorCode.ACCOUNT_CLOSED,
                    "That account has been closed. Contact " + properties.company().supportEmail() + ".");
        }
        if (user.isLockedOut(now)) {
            throw lockedOut(user, now);
        }

        if (!hasher.matches(password, user.getPasswordHash())) {
            registerFailure(user, now, "password");
            throw new ApiException(ErrorCode.BAD_CREDENTIALS, "That email or password is not right.");
        }

        user.clearLockout();
        user.touch(now);

        // A raised argon2 cost reaches existing accounts at their next sign-in
        // rather than through a mass re-enrolment.
        if (hasher.needsRehash(user.getPasswordHash())) {
            user.setPasswordHash(hasher.hash(password));
        }
        users.save(user);

        return openSession(user, device, ipAddress);
    }

    /** Opens a session and issues the pair of tokens for it. */
    @Transactional
    public Signed openSession(User user, String device, String ipAddress) {
        UserSession session = UserSession.open(user.getId(), device, ipAddress);
        // One lookup, read twice: whether the panel is drawn, and what it may
        // show. The app is told the role here because it draws the entrance the
        // moment it signs in, and the flag alone left an owner labelled
        // "Viewer" until they had opened the panel once.
        AdminUser grant = admins.findByEmailIgnoreCaseAndActiveTrue(user.getEmail()).orElse(null);
        boolean isAdmin = grant != null;

        TokenService.Issued refresh = tokens.issueRefresh(user.getId(), session.getId());
        TokenService.Parsed refreshClaims = tokens.parse(refresh.token(), TokenType.REFRESH);
        session.setRefreshTokenId(refreshClaims.tokenId());
        session.setRefreshExpiresAt(refresh.expiresAt());
        sessions.save(session);

        TokenService.Issued access = tokens.issueAccess(
                user.getId(), user.getEmail(), user.getKycTier().name(), session.getId(), isAdmin);

        if (grant != null) {
            grant.setLastActiveAt(Instant.now());
            admins.save(grant);
        }

        return new Signed(user, session, access, refresh, roleOf(grant));
    }

    /**
     * Exchanges a refresh token for a new pair, rotating it.
     *
     * <p>Presenting a refresh token that has already been rotated away is
     * treated as theft rather than as a mistake: the whole session is revoked,
     * because at that point the legitimate holder and whoever else has the
     * token are indistinguishable and only one of them should keep access.
     */
    @Transactional
    public Signed refresh(String refreshToken) {
        TokenService.Parsed parsed = tokens.parse(refreshToken, TokenType.REFRESH);
        Instant now = Instant.now();

        UserSession session = sessions.findById(parsed.sessionId())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.TOKEN_INVALID, "That session no longer exists. Sign in again."));

        if (!session.isLive(now)) {
            throw new ApiException(ErrorCode.TOKEN_EXPIRED, "That session has ended. Sign in again.");
        }

        if (!SecretHasher.constantTimeEquals(parsed.tokenId(), session.getRefreshTokenId())) {
            sessions.revokeAllForUser(session.getUserId(), "A refresh token was replayed", now);
            log.warn("Refresh token replay on session {} — every session for that account was revoked",
                    session.getId());
            notifications.push(
                    session.getUserId(),
                    NotifyKind.SECURITY,
                    "You were signed out everywhere",
                    "We saw a sign-in token being reused, which can mean it was copied. "
                            + "We ended every session on your account. Sign in again, and change your "
                            + "password if you did not expect this.");
            throw new ApiException(
                    ErrorCode.TOKEN_INVALID,
                    "That session token has already been used. For your safety we ended every session "
                            + "on this account. Sign in again.");
        }

        User user = users.findById(session.getUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.TOKEN_INVALID, "Sign in again."));
        if (!user.getAccountStatus().canSignIn()) {
            throw new ApiException(ErrorCode.ACCOUNT_CLOSED, "That account has been closed.");
        }

        AdminUser grant = admins.findByEmailIgnoreCaseAndActiveTrue(user.getEmail()).orElse(null);
        boolean isAdmin = grant != null;

        TokenService.Issued nextRefresh = tokens.issueRefresh(user.getId(), session.getId());
        TokenService.Parsed nextClaims = tokens.parse(nextRefresh.token(), TokenType.REFRESH);
        session.setRefreshTokenId(nextClaims.tokenId());
        session.setRefreshExpiresAt(nextRefresh.expiresAt());
        session.setLastSeenAt(now);
        sessions.save(session);

        TokenService.Issued access = tokens.issueAccess(
                user.getId(), user.getEmail(), user.getKycTier().name(), session.getId(), isAdmin);

        return new Signed(user, session, access, nextRefresh, roleOf(grant));
    }

    @Transactional
    public void signOut(UUID sessionId) {
        sessions.findById(sessionId).ifPresent(session -> {
            session.revoke("Signed out", Instant.now());
            sessions.save(session);
        });
    }

    @Transactional
    public int signOutEverywhere(UUID userId, String reason) {
        return sessions.revokeAllForUser(userId, reason, Instant.now());
    }

    @Transactional(readOnly = true)
    public List<UserSession> liveSessions(UUID userId) {
        return sessions.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(userId);
    }

    // ── The passcode gate ──────────────────────────────────────────────────

    /**
     * The six-digit gate the app shows on every reopen.
     *
     * <p>Attempts are counted <b>here</b>, not on the device. The client counts
     * too, for the message it shows, but a client-side counter is reset by
     * reinstalling the app.
     */
    @Transactional
    public int verifyPasscode(UUID userId, String passcode) {
        User user = requireUser(userId);
        Instant now = Instant.now();

        if (user.isLockedOut(now)) {
            throw lockedOut(user, now);
        }

        if (!hasher.matches(passcode, user.getPasscodeHash())) {
            int attemptsLeft = registerFailure(user, now, "passcode");
            throw new ApiException(
                    ErrorCode.PASSCODE_INVALID,
                    attemptsLeft > 0
                            ? "That passcode is not right. " + attemptsLeft + " "
                                    + (attemptsLeft == 1 ? "try" : "tries") + " left."
                            : "That passcode is not right.",
                    Map.of("attemptsLeft", Math.max(attemptsLeft, 0)));
        }

        user.clearLockout();
        user.touch(now);
        users.save(user);
        return settings.currentReadOnly().getMaxPasscodeAttempts();
    }

    /**
     * The four-digit gate in front of every money movement.
     *
     * <p>Never take the client's word that a PIN was entered. Every service
     * that moves money calls this first, with the PIN itself.
     */
    @Transactional
    public void verifyPin(UUID userId, String pin) {
        User user = requireUser(userId);
        Instant now = Instant.now();

        if (user.isLockedOut(now)) {
            throw lockedOut(user, now);
        }
        if (pin == null || pin.isBlank()) {
            throw new ApiException(ErrorCode.PIN_REQUIRED, "Enter your transaction PIN to continue.");
        }
        if (!hasher.matches(pin, user.getPinHash())) {
            int attemptsLeft = registerFailure(user, now, "PIN");
            throw new ApiException(
                    ErrorCode.PIN_INVALID,
                    attemptsLeft > 0
                            ? "That PIN is not right. " + attemptsLeft + " "
                                    + (attemptsLeft == 1 ? "try" : "tries") + " left."
                            : "That PIN is not right.",
                    Map.of("attemptsLeft", Math.max(attemptsLeft, 0)));
        }
        user.clearLockout();
        users.save(user);
    }

    // ── Changing a credential ──────────────────────────────────────────────

    @Transactional
    public void changePasscode(UUID userId, String current, String next) {
        User user = requireUser(userId);
        if (!hasher.matches(current, user.getPasscodeHash())) {
            throw new ApiException(ErrorCode.PASSCODE_INVALID, "Your current passcode is not right.");
        }
        PasswordStrength.requireValidPasscode(next);
        user.setPasscodeHash(hasher.hash(next));
        user.clearLockout();
        users.save(user);
        notifySecurityChange(user, "Your sign-in passcode was changed",
                "If this was not you, contact us straight away.");
    }

    @Transactional
    public void changePin(UUID userId, String current, String next) {
        User user = requireUser(userId);
        if (!hasher.matches(current, user.getPinHash())) {
            throw new ApiException(ErrorCode.PIN_INVALID, "Your current PIN is not right.");
        }
        PasswordStrength.requireValidPin(next);
        user.setPinHash(hasher.hash(next));
        user.clearLockout();
        users.save(user);
        notifySecurityChange(user, "Your transaction PIN was changed",
                "If this was not you, contact us straight away.");
    }

    /**
     * Changing a password ends every other session.
     *
     * <p>The usual reason someone changes a password is that they think someone
     * else has it. Leaving that someone signed in would defeat the change.
     */
    @Transactional
    public void changePassword(UUID userId, String current, String next, UUID keepSessionId) {
        User user = requireUser(userId);
        if (!hasher.matches(current, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.BAD_CREDENTIALS, "Your current password is not right.");
        }
        PasswordStrength.requireStrongPassword(next);
        user.setPasswordHash(hasher.hash(next));
        user.clearLockout();
        users.save(user);

        sessions.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(userId).stream()
                .filter(session -> !session.getId().equals(keepSessionId))
                .forEach(session -> {
                    session.revoke("Password changed", Instant.now());
                    sessions.save(session);
                });

        notifySecurityChange(user, "Your password was changed",
                "Every other device was signed out. If this was not you, contact us straight away.");
    }

    // ── Forgotten password ─────────────────────────────────────────────────

    /**
     * Starts a reset.
     *
     * <p>Answers the same way whether or not the address has an account, so the
     * endpoint cannot be used to find out who banks with us.
     */
    @Transactional
    public void startPasswordReset(String email) {
        String normalised = SignupService.normaliseEmail(email);
        users.findByEmailIgnoreCase(normalised).ifPresent(user ->
                otp.issue(normalised, OtpPurpose.PASSWORD_RESET, user.getFullName(), user.getId()));
    }

    @Transactional
    public void completePasswordReset(String email, String code, String newPassword) {
        String normalised = SignupService.normaliseEmail(email);
        otp.verify(normalised, OtpPurpose.PASSWORD_RESET, code);

        User user = users.findByEmailIgnoreCase(normalised)
                .orElseThrow(() -> new ApiException(ErrorCode.OTP_INVALID, "That code is not valid."));

        PasswordStrength.requireStrongPassword(newPassword);
        user.setPasswordHash(hasher.hash(newPassword));
        user.clearLockout();
        users.save(user);

        sessions.revokeAllForUser(user.getId(), "Password reset", Instant.now());
        notifySecurityChange(user, "Your password was reset",
                "Every device was signed out. If this was not you, contact us straight away.");
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private User requireUser(UUID userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("That account"));
    }

    /**
     * Counts a wrong credential and locks the account when the allowance runs
     * out.
     *
     * @return how many tries are left, which may be zero
     */
    private int registerFailure(User user, Instant now, String what) {
        int allowed = settings.currentReadOnly().getMaxPasscodeAttempts();
        user.setFailedPasscodeAttempts(user.getFailedPasscodeAttempts() + 1);

        if (user.getFailedPasscodeAttempts() >= allowed) {
            user.setLockedUntil(now.plus(properties.security().lockoutDuration()));
            users.save(user);
            log.warn("Locked account {} after {} wrong {} attempts",
                    user.getCustomerRef(), user.getFailedPasscodeAttempts(), what);
            notifySecurityChange(user, "Your account is locked for a while",
                    "There were too many wrong attempts. Try again shortly, or reset your password.");
            throw lockedOut(user, now);
        }

        users.save(user);
        return allowed - user.getFailedPasscodeAttempts();
    }

    private ApiException lockedOut(User user, Instant now) {
        long minutes = user.getLockedUntil() == null
                ? 0
                : Math.max(1, java.time.Duration.between(now, user.getLockedUntil()).toMinutes() + 1);
        return new ApiException(
                ErrorCode.ACCOUNT_LOCKED,
                "Too many wrong attempts. Try again in " + minutes
                        + " " + (minutes == 1 ? "minute" : "minutes") + ".",
                Map.of("lockedUntil", user.getLockedUntil(), "minutesRemaining", minutes));
    }

    private void notifySecurityChange(User user, String title, String body) {
        notifications.push(user.getId(), NotifyKind.SECURITY, title, body);
    }

    /**
     * A well-formed argon2 hash of a value nobody holds, so an unknown email
     * costs the same work as a known one. Verifying against it always fails.
     */
    private static final String DUMMY_HASH =
            "$argon2id$v=19$m=16384,t=3,p=1$c29tZXNhbHR2YWx1ZXM$xUUyMEOEHF7HFvpNEIQxUKM8zLKGSEP5UWNIZC9CBmA";

    private static AdminRole roleOf(AdminUser grant) {
        return grant == null ? null : grant.getRole();
    }

    /** What a successful sign-in produced. */
    public record Signed(
            User user,
            UserSession session,
            TokenService.Issued access,
            TokenService.Issued refresh,
            AdminRole adminRole) {

        /** Whether the panel is drawn at all. */
        public boolean admin() {
            return adminRole != null;
        }
    }
}
