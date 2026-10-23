package com.quadrilateral.kudi9ja.domain.admin;

import com.quadrilateral.kudi9ja.domain.group.LoanGroupRepository;
import com.quadrilateral.kudi9ja.domain.officer.LoanOfficerService;
import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who has access to the admin panel, and at what level.
 *
 * <p>Three rules shape everything here, and each one closes a way the panel
 * could otherwise be lost or given away by accident:
 *
 * <ol>
 *   <li><b>A grant creates no account and no password.</b> It means: when
 *       somebody signs in with that email, the panel appears. So access may
 *       only be granted to an email that already belongs to an account — an
 *       owner cannot hand the panel to an address nobody holds by mistyping
 *       it, and there is never a second credential to steal.
 *   <li><b>An admin cannot revoke their own access.</b> Not demote, not
 *       suspend, not remove. Every one of those would take away the permission
 *       needed to undo it.
 *   <li><b>The last active owner cannot be demoted or suspended.</b> Rule two
 *       stops an owner locking themselves out; without this one, two owners
 *       could still lock the company out by demoting each other in turn.
 * </ol>
 */
@Service
public class AdminTeamService {

    private final AdminUserRepository admins;
    private final UserRepository users;
    private final AdminAccessService access;
    private final AuditService audit;
    private final LoanOfficerService officers;
    private final LoanGroupRepository groups;

    public AdminTeamService(
            AdminUserRepository admins,
            UserRepository users,
            AdminAccessService access,
            AuditService audit,
            LoanOfficerService officers,
            LoanGroupRepository groups) {
        this.admins = admins;
        this.users = users;
        this.access = access;
        this.audit = audit;
        this.officers = officers;
        this.groups = groups;
    }

    @Transactional(readOnly = true)
    public List<AdminUser> list() {
        access.requireCanView();
        return admins.findAllByOrderByRoleAscNameAsc();
    }

    /**
     * Gives an existing account the panel.
     *
     * <p>The name and phone are copied off that account rather than typed, so
     * the team list always names the person the email actually belongs to.
     */
    @Transactional
    public AdminUser grant(String email, AdminRole role) {
        AdminUser actor = access.requireCanManageTeam();
        String normalised = email.trim().toLowerCase(Locale.ROOT);

        User account = users.findByEmailIgnoreCase(normalised)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.NOT_FOUND,
                        "No Kudi9ja account uses that email address. "
                                + "They need an account before they can be given the panel."));

        // A suspended grant is restored rather than duplicated: the row carries
        // the history of what that person did, and a second row for the same
        // email would make the audit trail ambiguous.
        AdminUser existing = admins.findByEmailIgnoreCase(normalised).orElse(null);
        if (existing != null) {
            if (existing.isActive()) {
                throw new ApiException(
                        ErrorCode.CONFLICT,
                        existing.getName() + " already has access as " + existing.getRole().label() + ".");
            }
            AdminRole was = existing.getRole();
            existing.setActive(true);
            existing.setRole(role);
            existing.setName(account.getFullName());
            existing.setPhone(account.getPhone());
            AdminUser restored = admins.save(existing);
            audit.record(
                    actorOf(actor),
                    AuditCategory.TEAM,
                    "Access restored",
                    restored.getName() + " (" + restored.getEmail() + ") was restored as "
                            + role.label() + ", having previously been " + was.label() + ".");
            return restored;
        }

        AdminUser granted = admins.save(AdminUser.grant(
                account.getId(),
                normalised,
                account.getFullName(),
                account.getPhone(),
                role,
                actor.getName()));

        audit.record(
                actorOf(actor),
                AuditCategory.TEAM,
                "Access granted",
                granted.getName() + " (" + granted.getEmail() + ") was given the panel as "
                        + role.label() + ".");
        return granted;
    }

    @Transactional
    public AdminUser changeRole(UUID adminId, AdminRole role) {
        AdminUser actor = access.requireCanManageTeam();
        access.refuseSelfAction(actor, adminId, "change the role on");

        AdminUser target = require(adminId);
        AdminRole was = target.getRole();
        if (was == role) {
            return target;
        }
        if (was == AdminRole.OWNER) {
            refuseLosingTheLastOwner("demote");
        }

        target.setRole(role);
        AdminUser saved = admins.save(target);

        audit.record(
                actorOf(actor),
                AuditCategory.TEAM,
                was.ordinal() < role.ordinal() ? "Admin demoted" : "Admin promoted",
                saved.getName() + " (" + saved.getEmail() + ") moved from "
                        + was.label() + " → " + role.label() + ".");
        return saved;
    }

    /**
     * Suspends or restores a grant.
     *
     * <p>Suspending rather than removing keeps the record of what that person
     * did while they held the panel, which the audit log depends on.
     */
    @Transactional
    public AdminUser setActive(UUID adminId, boolean active, String reason) {
        AdminUser actor = access.requireCanManageTeam();
        access.refuseSelfAction(actor, adminId, active ? "restore" : "suspend");

        AdminUser target = require(adminId);
        if (target.isActive() == active) {
            return target;
        }
        if (!active && target.getRole() == AdminRole.OWNER) {
            refuseLosingTheLastOwner("suspend");
        }

        target.setActive(active);
        AdminUser saved = admins.save(target);

        audit.record(
                actorOf(actor),
                AuditCategory.TEAM,
                active ? "Access restored" : "Access suspended",
                saved.getName() + " (" + saved.getEmail() + ") was "
                        + (active ? "restored" : "suspended")
                        + (reason == null || reason.isBlank() ? "." : ". Reason: " + reason.trim()));
        return saved;
    }

    /**
     * Removes a grant entirely.
     *
     * <p>The account is untouched: the person keeps their wallet, their savings
     * and their loans. All they lose is the panel.
     */
    @Transactional
    public void revoke(UUID adminId) {
        AdminUser actor = access.requireCanManageTeam();
        access.refuseSelfAction(actor, adminId, "remove");

        AdminUser target = require(adminId);
        if (target.getRole() == AdminRole.OWNER) {
            refuseLosingTheLastOwner("remove");
        }

        long running = groups.countByOfficerId(target.getId());
        if (running > 0) {
            throw new ApiException(ErrorCode.CONFLICT, target.getName() + " runs " + running + " loan group"
                    + (running == 1 ? "" : "s") + ". Give " + (running == 1 ? "it" : "them") + " another officer first.");
        }
        officers.beforeLeaving(target);
        admins.delete(target);
        audit.record(
                actorOf(actor),
                AuditCategory.TEAM,
                "Access removed",
                target.getName() + " (" + target.getEmail() + ") no longer has the panel. "
                        + "Their Kudi9ja account is unaffected.");
    }

    /** The caller's own grant, so the panel can render the team list correctly. */
    @Transactional
    public AdminUser me() {
        return access.require();
    }

    private AdminUser require(UUID adminId) {
        return admins.findById(adminId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "That team member was not found."));
    }

    /**
     * Rule two stops an owner locking themselves out. This stops two owners
     * locking the company out by taking turns on each other.
     */
    private void refuseLosingTheLastOwner(String what) {
        if (admins.countByRoleAndActiveTrue(AdminRole.OWNER) <= 1) {
            throw new ApiException(
                    ErrorCode.LAST_OWNER,
                    "This is the only active owner. Make somebody else an owner before you " + what + " them, "
                            + "or nobody will be able to manage the team.");
        }
    }

    private AuditService.Actor actorOf(AdminUser admin) {
        return new AuditService.Actor(admin.getUserId(), admin.getName(), admin.getEmail());
    }
}
