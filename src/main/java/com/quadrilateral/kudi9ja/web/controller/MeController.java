package com.quadrilateral.kudi9ja.web.controller;

import com.quadrilateral.kudi9ja.domain.kyc.OtpService;
import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.domain.legal.LegalDocument;
import com.quadrilateral.kudi9ja.domain.legal.LegalDocumentKind;
import com.quadrilateral.kudi9ja.domain.legal.LegalService;
import com.quadrilateral.kudi9ja.web.support.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import com.quadrilateral.kudi9ja.domain.user.ProfileService;
import com.quadrilateral.kudi9ja.security.auth.CurrentUser;
import com.quadrilateral.kudi9ja.web.dto.AuthDtos;
import com.quadrilateral.kudi9ja.web.dto.UserDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer's own account.
 *
 * <p>What is editable here is decided by where a fact came from. Name, date of
 * birth, BVN and NIN were checked against the issuing institutions, so they are
 * absent from {@code PATCH /me} — letting them be retyped afterwards would undo
 * the verification, and they change through support with evidence instead. The
 * payout account has its own endpoint behind a one-time code, the PIN and a
 * fresh name enquiry, because it is where money leaves. Everything else is a
 * preference and changes freely.
 *
 * <p>Nothing here ever returns a hash, the security answer, or a full BVN or
 * NIN — those come back as their last four digits, which is enough to recognise
 * and not enough to reuse.
 *
 * <p>The theme lives on the account rather than the device on purpose: a
 * customer who picks light mode and then changes phones should not have to pick
 * it again.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Account", description = "Profile, dashboard and payout account")
public class MeController {

    private final ProfileService profiles;
    private final LegalService legal;
    private final CurrentUser currentUser;

    public MeController(ProfileService profiles, LegalService legal, CurrentUser currentUser) {
        this.profiles = profiles;
        this.legal = legal;
        this.currentUser = currentUser;
    }

    @GetMapping("/me")
    @Operation(summary = "The signed-in customer's profile")
    public UserDtos.ProfileResponse me() {
        return profiles.profile(currentUser.requireId());
    }

    @PatchMapping("/me")
    @Operation(summary = "Change phone, address, state, theme and other preferences")
    public UserDtos.ProfileResponse update(@Valid @RequestBody UserDtos.UpdateProfileRequest request) {
        return profiles.update(currentUser.requireId(), request);
    }

    /**
     * The figures the dashboard leads with.
     *
     * <p>All computed server-side. Balance, total saved, what is owed, the
     * credit score and the next repayment were all worked out on the device in
     * the Flutter client; every one of them is now read from here and displayed.
     */
    @GetMapping("/me/dashboard")
    @Operation(summary = "Balance, savings, borrowing, score and the next repayment")
    public UserDtos.DashboardResponse dashboard() {
        return profiles.dashboard(currentUser.requireId());
    }

    /** Sends the code that confirms a payout-account change. */
    @PostMapping("/me/payout/code")
    @Operation(summary = "Send the code needed to change the payout account")
    public AuthDtos.OtpResponse startPayoutChange() {
        OtpService.Issued issued = profiles.startPayoutChange(currentUser.requireId());
        return new AuthDtos.OtpResponse(
                issued.expiresAt(),
                issued.resendAfterSeconds(),
                issued.codeLength(),
                "We have sent a code to your email address.");
    }

    /**
     * Changes where money leaves to.
     *
     * <p>Three gates, because this is the field an attacker with a live session
     * actually wants: the code proves they hold the email, the PIN proves they
     * are at the phone, and the name enquiry proves the account belongs to the
     * customer. The customer is notified afterwards either way, so if it was
     * not them they find out in time to stop the next payout.
     */
    @PatchMapping("/me/payout")
    @Operation(summary = "Change the payout account. Code, PIN and a name enquiry.")
    public UserDtos.ProfileResponse changePayout(
            @Valid @RequestBody UserDtos.ChangePayoutRequest request) {
        return profiles.changePayoutAccount(currentUser.requireId(), request);
    }

    /**
     * Documents this customer has not yet accepted.
     *
     * <p>The company gives thirty days' notice before a material change takes
     * effect, so a new version usually appears here well before it binds. The
     * app uses this to prompt rather than to block.
     */
    @GetMapping("/me/legal/outstanding")
    @Operation(summary = "Documents published since this customer last accepted")
    public List<Map<String, String>> outstandingDocuments() {
        return legal.outstandingFor(currentUser.requireId()).stream()
                .map(MeController::describe)
                .toList();
    }

    /**
     * Records that this customer has read and accepted a new version.
     *
     * <p>The version is named rather than assumed: the app sends the number
     * of the document it showed, and if that is no longer the one in force the
     * acceptance is refused and the app is told to fetch the current one.
     */
    @PostMapping("/me/legal/accept")
    @Operation(summary = "Accept the current version of a document")
    public Map<String, String> accept(
            @Valid @RequestBody UserDtos.AcceptDocumentRequest request, HttpServletRequest http) {
        LegalDocumentKind kind = LegalDocumentKind.fromId(request.document());
        LegalDocument current = legal.current(kind);
        if (!current.getVersion().equals(request.version())) {
            throw new ApiException(
                    ErrorCode.LEGAL_ACCEPTANCE_REQUIRED,
                    "The " + current.getTitle() + " has changed since you opened it. "
                            + "Read the current version and accept that one.",
                    Map.of("document", kind.id(), "currentVersion", current.getVersion()));
        }
        legal.accept(
                currentUser.requireId(),
                kind,
                request.version(),
                RequestContext.device(http),
                RequestContext.ipAddress(http));
        return Map.of(
                "document", kind.id(),
                "version", current.getVersion(),
                "message", "Thank you. You have accepted the " + current.getTitle() + ".");
    }

    @GetMapping("/me/legal/acceptances")
    @Operation(summary = "Which version of each document this customer accepted, and when")
    public List<Map<String, String>> acceptances() {
        return legal.acceptancesFor(currentUser.requireId()).stream()
                .map(acceptance -> Map.of(
                        "kind", acceptance.getKind().name(),
                        "version", acceptance.getDocumentVersion(),
                        "acceptedAt", String.valueOf(acceptance.getAcceptedAt()),
                        "device", String.valueOf(acceptance.getDevice())))
                .toList();
    }

    private static Map<String, String> describe(LegalDocument document) {
        return Map.of(
                "kind", document.getKind().name(),
                "id", document.getKind().id(),
                "title", document.getTitle(),
                "version", document.getVersion(),
                "effectiveFrom", String.valueOf(document.getEffectiveFrom()),
                // What changed, so the app can say so rather than presenting
                // a whole document and asking what is different.
                "changeSummary", String.valueOf(document.getChangeSummary()));
    }
}
