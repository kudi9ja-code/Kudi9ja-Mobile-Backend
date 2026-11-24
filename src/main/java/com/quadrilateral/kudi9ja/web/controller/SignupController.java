package com.quadrilateral.kudi9ja.web.controller;

import com.quadrilateral.kudi9ja.domain.kyc.OtpService;
import com.quadrilateral.kudi9ja.domain.user.SignupDraft;
import com.quadrilateral.kudi9ja.domain.user.SignupService;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.AuthService;
import com.quadrilateral.kudi9ja.web.dto.AuthDtos;
import com.quadrilateral.kudi9ja.web.dto.SignupDtos;
import com.quadrilateral.kudi9ja.web.support.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Signing up, in the eight gated steps the app walks through.
 *
 * <p>Each step is submitted on its own and validated as it arrives. That is not
 * a courtesy to the wizard — it is how the gating is enforced. The client runs
 * the same sequence and shows the same eight screens, but a client is a thing
 * an attacker controls: <b>anything a client can skip, it will</b>, so the
 * server refuses step four until step three has passed, and refuses to open an
 * account at all until all seven are behind it.
 *
 * <p>These endpoints are open, because by definition nobody has an account yet.
 * What holds them together instead is the <b>draft id</b>, minted on the first
 * step and quoted on each one after it. It is a random identifier returned only
 * to the client that started the sign-up, and it is the only handle on a
 * part-finished draft.
 *
 * <p>A draft holds a BVN and an NIN before it becomes an account, which is why
 * it expires on a short clock and is purged nightly rather than kept.
 */
@RestController
@RequestMapping("/api/v1/auth/signup")
@Tag(name = "Sign-up", description = "The eight gated steps of opening an account")
public class SignupController {

    private final SignupService signups;
    private final AuthService auth;

    public SignupController(SignupService signups, AuthService auth) {
        this.signups = signups;
        this.auth = auth;
    }

    @PostMapping("/personal")
    @Operation(summary = "Step 1 — name, email, phone, date of birth and gender. Eighteen or over.")
    public ResponseEntity<SignupDtos.DraftResponse> personal(
            @Valid @RequestBody SignupDtos.PersonalRequest request,
            HttpServletRequest http) {

        SignupDraft draft = signups.submitPersonal(
                request, RequestContext.device(http), RequestContext.ipAddress(http));

        return ResponseEntity.status(HttpStatus.CREATED).body(describe(
                draft,
                "We have sent a six-digit code to " + draft.getEmail() + "."));
    }

    /**
     * Step 2. Email is the only channel verified — there is no SMS step. The
     * phone is collected so support can reach the customer, not as a second
     * factor, and the sign-up documentation says so rather than implying
     * otherwise.
     */
    @PostMapping("/{draftId}/email")
    @Operation(summary = "Step 2 — confirm the six-digit code sent by email")
    public SignupDtos.DraftResponse verifyEmail(
            @PathVariable UUID draftId,
            @Valid @RequestBody SignupDtos.EmailCodeRequest request) {

        return describe(signups.verifyEmail(draftId, request.code()), "Email confirmed.");
    }

    @PostMapping("/{draftId}/email/resend")
    @Operation(summary = "Send the email code again, subject to the resend cooldown")
    public AuthDtos.OtpResponse resendEmailCode(@PathVariable UUID draftId) {
        OtpService.Issued issued = signups.resendEmailCode(draftId);
        return new AuthDtos.OtpResponse(
                issued.expiresAt(),
                issued.resendAfterSeconds(),
                issued.codeLength(),
                "A new code is on its way.");
    }

    @PostMapping("/{draftId}/identity")
    @Operation(summary = "Step 3 — BVN, NIN, address and state, checked against the issuing institutions")
    public SignupDtos.DraftResponse identity(
            @PathVariable UUID draftId,
            @Valid @RequestBody SignupDtos.IdentityRequest request) {

        return describe(signups.submitIdentity(draftId, request), "Identity confirmed.");
    }

    @PostMapping("/{draftId}/liveness")
    @Operation(summary = "Step 4 — face check: photos of a blink, a head turn and an open mouth")
    public SignupDtos.DraftResponse liveness(
            @PathVariable UUID draftId,
            @Valid @RequestBody SignupDtos.LivenessRequest request) {

        return describe(signups.submitLiveness(draftId, request), "Face check done.");
    }

    /**
     * Step 5. The account must be in the customer's own name: the server
     * resolves the account name with the bank and refuses a mismatch, because
     * the Terms promise payouts only to the customer.
     */
    @PostMapping("/{draftId}/payout")
    @Operation(summary = "Step 5 — the customer's own bank account, resolved and name-matched")
    public SignupDtos.DraftResponse payout(
            @PathVariable UUID draftId,
            @Valid @RequestBody SignupDtos.PayoutRequest request) {

        SignupDraft draft = signups.submitPayout(draftId, request);
        return describe(draft, "Account confirmed as " + draft.getPayoutAccountName() + ".");
    }

    @PostMapping("/{draftId}/password")
    @Operation(summary = "Step 6 — password and security question")
    public SignupDtos.DraftResponse password(
            @PathVariable UUID draftId,
            @Valid @RequestBody SignupDtos.PasswordRequest request) {

        return describe(signups.submitPassword(draftId, request), "Password set.");
    }

    @PostMapping("/{draftId}/passcode")
    @Operation(summary = "Step 7 — the six-digit sign-in passcode")
    public SignupDtos.DraftResponse passcode(
            @PathVariable UUID draftId,
            @Valid @RequestBody SignupDtos.SetPasscodeRequest request) {

        return describe(signups.submitPasscode(draftId, request), "Passcode set.");
    }

    @PostMapping("/{draftId}/pin")
    @Operation(summary = "Step 8 — the four-digit transaction PIN")
    public SignupDtos.DraftResponse pin(
            @PathVariable UUID draftId,
            @Valid @RequestBody SignupDtos.SetPinRequest request) {

        return describe(signups.submitPin(draftId, request), "Transaction PIN set.");
    }

    /**
     * Step 9, and the account exists.
     *
     * <p>The version of each document accepted is recorded against the account
     * along with the timestamp and the device. The Terms lean on that record as
     * evidence, so it is written here and never reconstructed later.
     *
     * <p>A session is opened straight away: the customer has just typed a
     * password, a passcode and a PIN, and asking them to sign in again would be
     * asking for a fourth credential in ninety seconds.
     */
    @PostMapping("/{draftId}/complete")
    @Operation(summary = "Step 9 — accept the three documents and open the account")
    public ResponseEntity<AuthDtos.SessionResponse> complete(
            @PathVariable UUID draftId,
            @Valid @RequestBody SignupDtos.ReviewRequest request,
            HttpServletRequest http) {

        User user = signups.complete(draftId, request);
        AuthService.Signed signed = auth.openSession(
                user, RequestContext.device(http), RequestContext.ipAddress(http));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AuthDtos.SessionResponse.from(signed));
    }

    /** Where a draft has got to, for a client resuming one. */
    @PostMapping("/{draftId}")
    @Operation(summary = "Where this sign-up has got to")
    public SignupDtos.DraftResponse resume(@PathVariable UUID draftId) {
        return describe(signups.draft(draftId), "Pick up where you left off.");
    }

    /**
     * The draft id doubles as the sign-up token.
     *
     * <p>The two are listed separately in the response because they are separate
     * ideas — one names the draft, the other authorises continuing it — and
     * keeping the field lets that separation be made real later without
     * changing the client.
     */
    private static SignupDtos.DraftResponse describe(SignupDraft draft, String message) {
        return new SignupDtos.DraftResponse(
                draft.getId(),
                draft.getId().toString(),
                draft.getStep(),
                draft.getStep().next(),
                draft.getEmail(),
                draft.getExpiresAt(),
                message);
    }
}
