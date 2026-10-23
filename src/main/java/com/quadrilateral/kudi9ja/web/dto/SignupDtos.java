package com.quadrilateral.kudi9ja.web.dto;

import com.quadrilateral.kudi9ja.domain.user.SignupStep;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * What each step of signing up sends and receives.
 *
 * <p>The bean-validation annotations here catch the shape of a field. They are
 * the first gate, not the only one: whether a BVN belongs to this person, or
 * whether an email is already taken, is a question for the service.
 */
public final class SignupDtos {

    private SignupDtos() {
    }

    /** Step one. The date of birth is checked for eighteen years by the service. */
    public record PersonalRequest(
            @NotBlank(message = "Tell us your full name.")
            @Size(min = 2, max = 160, message = "Your full name is between 2 and 160 characters.")
            String fullName,

            @NotBlank(message = "An email address is needed.")
            @Email(message = "That does not look like an email address.")
            @Size(max = 190, message = "That email address is too long.")
            String email,

            @NotBlank(message = "A phone number is needed.")
            @Pattern(regexp = "^0[7-9][01]\\d{8}$",
                    message = "A Nigerian mobile number is eleven digits and starts 070, 080, 081, 090 or 091.")
            String phone,

            @NotNull(message = "Tell us your date of birth.")
            @Past(message = "A date of birth has to be in the past.")
            LocalDate dateOfBirth,

            @NotBlank(message = "Choose an option.")
            String gender) {
    }

    /** Step three. */
    public record IdentityRequest(
            @NotBlank(message = "Your BVN is needed.")
            @Pattern(regexp = "^\\d{11}$", message = "A BVN is eleven digits.")
            String bvn,

            @NotBlank(message = "Your NIN is needed.")
            @Pattern(regexp = "^\\d{11}$", message = "A NIN is eleven digits.")
            String nin,

            @NotBlank(message = "Your address is needed.")
            @Size(min = 5, max = 400, message = "Give your street, area and city.")
            String address,

            @NotBlank(message = "Choose your state.")
            String state) {
    }

    /** Step four. The account must be in the customer's own name. */
    public record PayoutRequest(
            @NotBlank(message = "Choose your bank.")
            String bank,

            @NotBlank(message = "Your account number is needed.")
            @Pattern(regexp = "^\\d{10}$", message = "An account number is ten digits.")
            String accountNumber) {
    }

    /** Step five. */
    public record PasswordRequest(
            @NotBlank(message = "Choose a password.")
            @Size(min = 8, max = 128, message = "Your password is at least 8 characters.")
            String password,

            @NotBlank(message = "Choose a security question.")
            @Size(max = 200, message = "That security question is too long.")
            String securityQuestion,

            @NotBlank(message = "Answer your security question.")
            @Size(min = 2, max = 200, message = "Give a fuller answer.")
            String securityAnswer) {
    }

    /** Step six. Set then confirmed, so both are sent and compared here. */
    public record SetPasscodeRequest(
            @NotBlank @Pattern(regexp = "^\\d{6}$", message = "Your passcode is six digits.")
            String passcode,

            @NotBlank @Pattern(regexp = "^\\d{6}$", message = "Your passcode is six digits.")
            String confirmPasscode) {
    }

    /** Step seven. */
    public record SetPinRequest(
            @NotBlank @Pattern(regexp = "^\\d{4}$", message = "Your PIN is four digits.")
            String pin,

            @NotBlank @Pattern(regexp = "^\\d{4}$", message = "Your PIN is four digits.")
            String confirmPin) {
    }

    /**
     * Step eight.
     *
     * @param acceptedVersions the version of each document the review screen
     *                         actually showed. Accepting a version that has
     *                         since been superseded is refused: the customer
     *                         must agree to what binds them.
     */
    public record ReviewRequest(
            @NotNull(message = "You have to accept all three documents to open an account.")
            Map<String, String> acceptedVersions,

            @NotNull(message = "You have to accept all three documents to open an account.")
            Boolean accepted,

            /** The signature drawn beside the acceptance. Required: no account opens without one. */
            @NotNull(message = "Draw your signature to sign the agreements.")
            @jakarta.validation.Valid
            SignatureInput signature) {
    }

    /**
     * An electronic signature: a PNG of what the customer drew, as a data URL,
     * and the name they typed. The time is the server's, not this one's.
     */
    public record SignatureInput(
            @NotNull(message = "Draw your signature.")
            @jakarta.validation.constraints.Size(max = 600000, message = "That signature is too large. Draw it again.")
            String image,

            @NotNull(message = "Type your full name.")
            @jakarta.validation.constraints.Size(min = 2, max = 160, message = "Type your full name.")
            String typedName,

            java.time.Instant signedAt) {
    }

    /**
     * Step 2, confirming the code sent by email.
     *
     * <p>Only the code. The email and the purpose are read off the draft rather
     * than accepted from the client — a client that could name the address a
     * code was checked against could check a code against somebody else's.
     */
    public record EmailCodeRequest(
            @NotBlank(message = "Enter the code we sent you.")
            @Pattern(regexp = "^\\d{6}$", message = "The code is six digits.")
            String code) {
    }

    /** What every step answers with: where the draft is, and what comes next. */
    public record DraftResponse(
            UUID draftId,
            String signupToken,
            SignupStep step,
            SignupStep nextStep,
            String email,
            Instant expiresAt,
            String message) {
    }
}
