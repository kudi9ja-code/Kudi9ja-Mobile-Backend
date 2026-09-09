package com.quadrilateral.kudi9ja.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/** Signing in, keeping a session alive, and changing a credential. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record SignInRequest(
            @NotBlank(message = "Your email address is needed.")
            @Email(message = "That does not look like an email address.")
            String email,

            @NotBlank(message = "Your password is needed.")
            String password,

            /** What the customer is signing in on, for their security screen. */
            String device) {
    }

    /**
     * The passcode gate the app shows every time it is reopened.
     *
     * <p>Verified against the account the access token names, so a passcode
     * alone buys nothing: it confirms the phone is in the right hands, it does
     * not authenticate the account.
     */
    public record PasscodeRequest(
            @NotBlank @Pattern(regexp = "^\\d{6}$", message = "Your passcode is six digits.")
            String passcode) {
    }

    /** Gates every money movement. */
    public record PinRequest(
            @NotBlank @Pattern(regexp = "^\\d{4}$", message = "Your PIN is four digits.")
            String pin) {
    }

    public record RefreshRequest(
            @NotBlank(message = "A refresh token is needed.")
            String refreshToken) {
    }

    public record ChangePasscodeRequest(
            @NotBlank(message = "Your current passcode is needed.")
            String currentPasscode,

            @NotBlank @Pattern(regexp = "^\\d{6}$", message = "Your new passcode is six digits.")
            String newPasscode) {
    }

    public record ChangePinRequest(
            @NotBlank(message = "Your current PIN is needed.")
            String currentPin,

            @NotBlank @Pattern(regexp = "^\\d{4}$", message = "Your new PIN is four digits.")
            String newPin) {
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "Your current password is needed.")
            String currentPassword,

            @NotBlank @Size(min = 8, max = 128, message = "Your password is at least 8 characters.")
            String newPassword) {
    }

    public record ForgotPasswordRequest(
            @NotBlank @Email(message = "That does not look like an email address.")
            String email) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Email String email,
            @NotBlank String code,
            @NotBlank @Size(min = 8, max = 128, message = "Your password is at least 8 characters.") String newPassword) {
    }

    public record OtpSendRequest(
            @NotBlank @Email(message = "That does not look like an email address.")
            String email,

            @NotBlank(message = "Say what the code is for.")
            String purpose) {
    }

    public record OtpVerifyRequest(
            @NotBlank @Email String email,
            @NotBlank(message = "Say what the code is for.") String purpose,
            @NotBlank(message = "Enter the code we sent you.") String code) {
    }

    /**
     * What a successful sign-in hands back.
     *
     * <p>No hash, no security answer, and no one-time code appears here or
     * anywhere else.
     */
    public record SessionResponse(
            String accessToken,
            String refreshToken,
            long expiresInSeconds,
            Instant expiresAt,
            UUID sessionId,
            boolean admin,
            UserDtos.ProfileResponse profile) {

        /**
         * @param signed what the sign-in produced. The admin flag comes from
         *               the database, not from anything the client sent, and
         *               it is re-checked on every subsequent request — this
         *               copy only tells the app whether to draw the panel.
         */
        public static SessionResponse from(com.quadrilateral.kudi9ja.domain.user.AuthService.Signed signed) {
            return new SessionResponse(
                    signed.access().token(),
                    signed.refresh().token(),
                    signed.access().expiresInSeconds(),
                    signed.access().expiresAt(),
                    signed.session().getId(),
                    signed.admin(),
                    UserDtos.ProfileResponse.from(signed.user(), signed.adminRole()));
        }
    }

    /** What the OTP endpoints answer with. Never the code. */
    public record OtpResponse(
            Instant expiresAt,
            int resendAfterSeconds,
            int codeLength,
            String message) {
    }

    /** A live session, for the customer's own security screen. */
    public record SessionSummary(
            UUID id,
            String device,
            String ipAddress,
            Instant createdAt,
            Instant lastSeenAt,
            boolean current) {
    }
}
