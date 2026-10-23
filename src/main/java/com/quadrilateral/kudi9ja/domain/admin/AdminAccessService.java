package com.quadrilateral.kudi9ja.domain.admin;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.security.auth.AuthPrincipal;
import com.quadrilateral.kudi9ja.security.auth.CurrentUser;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether the caller holds panel access, and whether their role allows what
 * they are about to do.
 *
 * <p>Every admin endpoint calls one of the {@code require} methods. The check
 * runs against the database on <b>this request</b>, not against a claim in the
 * caller's token: a grant can be suspended between one request and the next,
 * and a token is a thing the client holds and can forge.
 */
@Service
public class AdminAccessService {

    private final AdminUserRepository admins;
    private final CurrentUser currentUser;

    public AdminAccessService(AdminUserRepository admins, CurrentUser currentUser) {
        this.admins = admins;
        this.currentUser = currentUser;
    }

    /** The caller's grant, refusing anyone who does not hold one. */
    @Transactional
    public AdminUser require() {
        AuthPrincipal principal = currentUser.require();
        AdminUser admin = admins.findByEmailIgnoreCaseAndActiveTrue(principal.email())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.NOT_AN_ADMIN, "You do not have access to the admin panel."));
        // Cheap, and it is what makes the team list's "last active" true.
        admin.setLastActiveAt(Instant.now());
        return admins.save(admin);
    }

    public AdminUser requireCanApprovePayments() {
        return requirePermission(AdminRole::canApprovePayments,
                "Only an owner, administrator or support can act on payments.");
    }

    public AdminUser requireCanActOnLoans() {
        return requirePermission(AdminRole::canActOnLoans,
                "Only an owner, administrator or support can act on loans.");
    }

    public AdminUser requireCanManageCustomers() {
        return requirePermission(AdminRole::canManageCustomers,
                "Only an owner, administrator or support can change a customer's standing.");
    }

    public AdminUser requireCanEditSettings() {
        return requirePermission(AdminRole::canEditSettings,
                "Only an owner or administrator can change rates, limits and switches.");
    }

    public AdminUser requireCanManageTeam() {
        return requirePermission(AdminRole::canManageTeam,
                "Only an owner can add, promote, suspend or remove admins.");
    }

    /** Every admin may look. Being watched is the point of the audit log. */
    /** Downloading reports: a bulk copy of customers' data, so owners and administrators only. */
    public AdminUser requireCanExport() {
        return requirePermission(AdminRole::canEditSettings,
                "Only an owner or an administrator can download reports.");
    }

    public AdminUser requireCanView() {
        return require();
    }

    private AdminUser requirePermission(Predicate<AdminRole> permitted, String message) {
        AdminUser admin = require();
        if (!permitted.test(admin.getRole())) {
            throw new ApiException(ErrorCode.FORBIDDEN, message);
        }
        return admin;
    }

    /**
     * Refuses an admin acting on their own grant.
     *
     * <p>Self-lockout is impossible by construction: an admin may not change
     * their own role, suspend themselves or remove their own access. Each of
     * those would revoke the very permission needed to undo it.
     */
    public void refuseSelfAction(AdminUser actor, UUID targetAdminId, String what) {
        if (actor.getId().equals(targetAdminId)) {
            throw new ApiException(
                    ErrorCode.SELF_LOCKOUT,
                    "You cannot " + what + " your own access from inside the panel. "
                            + "Ask another owner to do it.");
        }
    }
}
