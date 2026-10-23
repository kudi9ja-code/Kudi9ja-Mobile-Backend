package com.quadrilateral.kudi9ja.domain.officer;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.domain.admin.AdminAccessService;
import com.quadrilateral.kudi9ja.domain.admin.AdminRole;
import com.quadrilateral.kudi9ja.domain.admin.AdminUser;
import com.quadrilateral.kudi9ja.domain.admin.AdminUserRepository;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.loan.ImportedLoan;
import com.quadrilateral.kudi9ja.domain.loan.ImportedLoanRepository;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.web.dto.LoanOfficerDtos;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who on the team looks after which borrower.
 *
 * <p>The rule is at least one, as many as needed, and one lead. Nobody is ever
 * left without an officer by this class: removing the last one is refused,
 * an officer leaving the team is refused while a borrower depends on them
 * alone, and anywhere a loan comes into being without one — a paper loan
 * entered, an application approved — the admin who did it becomes the lead.
 *
 * <p>A paper loan's borrower may not have an account yet. Their officers sit on
 * the paper loan's file, and move to the account when the loan attaches to it.
 */
@Service
public class LoanOfficerService {

    private final LoanOfficerAssignmentRepository assignments;
    private final AdminUserRepository admins;
    private final UserRepository users;
    private final ImportedLoanRepository imports;
    private final AdminAccessService access;
    private final AuditService audit;

    public LoanOfficerService(
            LoanOfficerAssignmentRepository assignments,
            AdminUserRepository admins,
            UserRepository users,
            ImportedLoanRepository imports,
            AdminAccessService access,
            AuditService audit) {
        this.assignments = assignments;
        this.admins = admins;
        this.users = users;
        this.imports = imports;
        this.access = access;
        this.audit = audit;
    }

    /** Who a borrower is, whichever way they are known. */
    private record Target(UUID userId, UUID importedLoanId, String name, String ref) {
    }

    // ── Reading ───────────────────────────────────────────────────────────

    /** Everyone on the team who may be made an officer: anyone with active panel access. */
    @Transactional(readOnly = true)
    public List<LoanOfficerDtos.OfficerOption> options() {
        access.requireCanView();
        return admins.findAllByOrderByRoleAscNameAsc().stream()
                .filter(AdminUser::isActive)
                .map(a -> new LoanOfficerDtos.OfficerOption(
                        a.getId(), a.getName(), a.getEmail(), a.getRole(), a.getRole().label(),
                        assignments.countByOfficerId(a.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LoanOfficerDtos.AssignedOfficer> forCustomer(UUID customerId) {
        access.requireCanView();
        return views(assignments.findByUserIdOrderByLeadDescAssignedAtAsc(customerId));
    }

    @Transactional(readOnly = true)
    public List<LoanOfficerDtos.AssignedOfficer> forImport(UUID importedLoanId) {
        access.requireCanView();
        // Once the entry is on an account, its officers are the account's.
        UUID owner = imports.findById(importedLoanId).map(ImportedLoan::getUserId).orElse(null);
        if (owner != null) {
            return views(assignments.findByUserIdOrderByLeadDescAssignedAtAsc(owner));
        }
        return views(assignments.findByImportedLoanIdOrderByLeadDescAssignedAtAsc(importedLoanId));
    }

    /** Officers by customer, for a list of customers in one query. */
    @Transactional(readOnly = true)
    public Map<UUID, List<String>> namesByCustomer(Collection<UUID> customerIds) {
        if (customerIds.isEmpty()) {
            return Map.of();
        }
        return assignments.findByUserIdIn(customerIds).stream()
                .sorted((a, b) -> Boolean.compare(b.isLead(), a.isLead()))
                .collect(Collectors.groupingBy(
                        LoanOfficerAssignment::getUserId,
                        Collectors.mapping(LoanOfficerAssignment::getOfficerName, Collectors.toList())));
    }

    /** Officers by paper loan, likewise. */
    @Transactional(readOnly = true)
    public Map<UUID, List<String>> namesByImport(Collection<UUID> importIds) {
        if (importIds.isEmpty()) {
            return Map.of();
        }
        return assignments.findByImportedLoanIdIn(importIds).stream()
                .sorted((a, b) -> Boolean.compare(b.isLead(), a.isLead()))
                .collect(Collectors.groupingBy(
                        LoanOfficerAssignment::getImportedLoanId,
                        Collectors.mapping(LoanOfficerAssignment::getOfficerName, Collectors.toList())));
    }

    /** The borrowers one officer looks after. */
    @Transactional(readOnly = true)
    public List<LoanOfficerDtos.OfficerBorrower> book(UUID officerId) {
        access.requireCanView();
        List<LoanOfficerAssignment> rows = assignments.findByOfficerIdOrderByAssignedAtDesc(officerId);
        Map<UUID, User> people = users.findAllById(rows.stream().map(LoanOfficerAssignment::getUserId)
                        .filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<UUID, ImportedLoan> paper = imports.findAllById(rows.stream().map(LoanOfficerAssignment::getImportedLoanId)
                        .filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(ImportedLoan::getId, Function.identity()));
        List<LoanOfficerDtos.OfficerBorrower> out = new ArrayList<>();
        for (LoanOfficerAssignment a : rows) {
            if (a.getUserId() != null) {
                User u = people.get(a.getUserId());
                out.add(new LoanOfficerDtos.OfficerBorrower(a.getId(), a.getUserId(), null,
                        u == null ? "Unknown customer" : u.getFullName(), u == null ? null : u.getCustomerRef(),
                        true, a.isLead(), a.getAssignedAt()));
            } else {
                ImportedLoan l = paper.get(a.getImportedLoanId());
                out.add(new LoanOfficerDtos.OfficerBorrower(a.getId(), null, a.getImportedLoanId(),
                        l == null ? "Unknown borrower" : l.getFullName(), null, false, a.isLead(), a.getAssignedAt()));
            }
        }
        return out;
    }

    /** The customer's own view: who to talk to about their loan. */
    @Transactional(readOnly = true)
    public List<LoanOfficerDtos.MyOfficer> mine(UUID userId) {
        List<LoanOfficerAssignment> rows = assignments.findByUserIdOrderByLeadDescAssignedAtAsc(userId);
        Map<UUID, AdminUser> team = team(rows);
        return rows.stream()
                .map(a -> {
                    AdminUser admin = team.get(a.getOfficerId());
                    return new LoanOfficerDtos.MyOfficer(a.getOfficerName(), a.getOfficerEmail(),
                            admin == null ? null : admin.getPhone(), a.isLead());
                })
                .toList();
    }

    // ── Changing ──────────────────────────────────────────────────────────

    @Transactional
    public List<LoanOfficerDtos.AssignedOfficer> assignToCustomer(UUID customerId, List<UUID> officerIds, boolean lead) {
        AdminUser actor = access.requireCanActOnLoans();
        User user = users.findById(customerId).orElseThrow(() -> ApiException.notFound("That customer"));
        Target target = new Target(user.getId(), null, user.getFullName(), user.getCustomerRef());
        assign(actor, target, officerIds, lead);
        return views(assignments.findByUserIdOrderByLeadDescAssignedAtAsc(customerId));
    }

    @Transactional
    public List<LoanOfficerDtos.AssignedOfficer> assignToImport(UUID importedLoanId, List<UUID> officerIds, boolean lead) {
        AdminUser actor = access.requireCanActOnLoans();
        ImportedLoan loan = imports.findById(importedLoanId).orElseThrow(() -> ApiException.notFound("That loan"));
        if (loan.getUserId() != null) {
            // Attached already: the officers live on the account now.
            return assignToCustomer(loan.getUserId(), officerIds, lead);
        }
        Target target = new Target(null, loan.getId(), loan.getFullName(), "paper loan " + loan.getId());
        assign(actor, target, officerIds, lead);
        return views(assignments.findByImportedLoanIdOrderByLeadDescAssignedAtAsc(importedLoanId));
    }

    /** Takes one officer off a borrower. Never the last one. */
    @Transactional
    public void remove(UUID assignmentId) {
        AdminUser actor = access.requireCanActOnLoans();
        LoanOfficerAssignment a = assignments.findById(assignmentId)
                .orElseThrow(() -> ApiException.notFound("That loan officer"));
        List<LoanOfficerAssignment> siblings = siblings(a);
        if (siblings.size() <= 1) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "Every borrower keeps at least one loan officer. Add another before removing "
                            + a.getOfficerName() + ".");
        }
        assignments.delete(a);
        if (a.isLead()) {
            siblings.stream().filter(s -> !s.getId().equals(a.getId())).findFirst().ifPresent(next -> {
                next.setLead(true);
                assignments.save(next);
            });
        }
        audit.record(actorOf(actor), AuditCategory.LOAN, "Loan officer removed",
                a.getOfficerName() + " no longer looks after " + describe(a) + ".", a.getUserId(), null);
    }

    @Transactional
    public void makeLead(UUID assignmentId) {
        AdminUser actor = access.requireCanActOnLoans();
        LoanOfficerAssignment a = assignments.findById(assignmentId)
                .orElseThrow(() -> ApiException.notFound("That loan officer"));
        for (LoanOfficerAssignment s : siblings(a)) {
            boolean lead = s.getId().equals(a.getId());
            if (s.isLead() != lead) {
                s.setLead(lead);
                assignments.save(s);
            }
        }
        audit.record(actorOf(actor), AuditCategory.LOAN, "Lead loan officer changed",
                a.getOfficerName() + " now leads on " + describe(a) + ".", a.getUserId(), null);
    }

    // ── Hooks for the rest of lending ─────────────────────────────────────

    /** A paper loan was entered: the officers chosen, or the admin who entered it. */
    @Transactional
    public void startImport(ImportedLoan loan, AdminUser enteredBy, List<UUID> officerIds) {
        Target target = new Target(null, loan.getId(), loan.getFullName(), "paper loan " + loan.getId());
        List<UUID> chosen = officerIds == null || officerIds.isEmpty() ? List.of(enteredBy.getId()) : officerIds;
        assign(enteredBy, target, chosen, true);
    }

    /**
     * Puts one officer on one borrower, for the rest of lending — a group
     * handing its borrowers to its officer. Permission was checked by the
     * caller. Exactly one of {@code userId} and {@code importedLoanId}.
     */
    @Transactional
    public void addInternally(UUID userId, UUID importedLoanId, AdminUser officer, AdminUser actor, boolean lead) {
        Target target;
        if (userId != null) {
            User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("That customer"));
            target = new Target(user.getId(), null, user.getFullName(), user.getCustomerRef());
        } else {
            ImportedLoan loan = imports.findById(importedLoanId).orElseThrow(() -> ApiException.notFound("That loan"));
            target = loan.getUserId() != null
                    ? new Target(loan.getUserId(), null, loan.getFullName(), "paper loan " + loan.getId())
                    : new Target(null, loan.getId(), loan.getFullName(), "paper loan " + loan.getId());
        }
        assign(actor, target, List.of(officer.getId()), lead);
    }

    /** A loan was approved for a customer who has no officer yet: the approver takes it. */
    @Transactional
    public void ensureForCustomer(UUID customerId, UUID approverUserId) {
        if (assignments.countByUserId(customerId) > 0) {
            return;
        }
        AdminUser approver = admins.findByUserId(approverUserId).orElse(null);
        User user = users.findById(customerId).orElse(null);
        if (approver == null || user == null) {
            return;
        }
        assign(approver, new Target(user.getId(), null, user.getFullName(), user.getCustomerRef()),
                List.of(approver.getId()), true);
    }

    /** The borrower signed up and the paper loan attached: officers move to the account. */
    @Transactional
    public void moveToAccount(UUID importedLoanId, UUID userId) {
        Set<UUID> already = assignments.findByUserIdOrderByLeadDescAssignedAtAsc(userId).stream()
                .map(LoanOfficerAssignment::getOfficerId)
                .collect(Collectors.toSet());
        boolean hasLead = assignments.findByUserIdOrderByLeadDescAssignedAtAsc(userId).stream()
                .anyMatch(LoanOfficerAssignment::isLead);
        for (LoanOfficerAssignment a : assignments.findByImportedLoanIdOrderByLeadDescAssignedAtAsc(importedLoanId)) {
            if (already.contains(a.getOfficerId())) {
                assignments.delete(a);
                continue;
            }
            a.setImportedLoanId(null);
            a.setUserId(userId);
            if (a.isLead() && hasLead) {
                a.setLead(false);
            }
            hasLead = hasLead || a.isLead();
            already.add(a.getOfficerId());
            assignments.save(a);
        }
    }

    /**
     * Someone is leaving the team. Refused while any borrower has them as their
     * only officer; otherwise they come off everyone's list, and a new lead is
     * named wherever they led.
     */
    @Transactional
    public void beforeLeaving(AdminUser leaving) {
        List<LoanOfficerAssignment> theirs = assignments.findByOfficerIdOrderByAssignedAtDesc(leaving.getId());
        long alone = theirs.stream().filter(a -> siblings(a).size() <= 1).count();
        if (alone > 0) {
            throw new ApiException(ErrorCode.CONFLICT,
                    leaving.getName() + " is the only loan officer for " + alone + " borrower"
                            + (alone == 1 ? "" : "s") + ". Give them another officer first.");
        }
        for (LoanOfficerAssignment a : theirs) {
            List<LoanOfficerAssignment> siblings = siblings(a);
            assignments.delete(a);
            if (a.isLead()) {
                siblings.stream().filter(s -> !s.getId().equals(a.getId())).findFirst().ifPresent(next -> {
                    next.setLead(true);
                    assignments.save(next);
                });
            }
        }
    }

    // ── Plumbing ──────────────────────────────────────────────────────────

    private void assign(AdminUser actor, Target target, List<UUID> officerIds, boolean lead) {
        List<LoanOfficerAssignment> current = new ArrayList<>(target.userId() != null
                ? assignments.findByUserIdOrderByLeadDescAssignedAtAsc(target.userId())
                : assignments.findByImportedLoanIdOrderByLeadDescAssignedAtAsc(target.importedLoanId()));
        Set<UUID> present = current.stream().map(LoanOfficerAssignment::getOfficerId).collect(Collectors.toSet());
        boolean needsLead = current.stream().noneMatch(LoanOfficerAssignment::isLead);

        List<String> added = new ArrayList<>();
        UUID makeLead = lead && !officerIds.isEmpty() ? officerIds.get(0) : null;
        for (UUID officerId : new LinkedHashSet<>(officerIds)) {
            AdminUser officer = admins.findById(officerId)
                    .orElseThrow(() -> ApiException.notFound("That loan officer"));
            if (!officer.isActive()) {
                throw ApiException.validation(officer.getName() + " cannot be a loan officer: their panel access is suspended.");
            }
            if (present.contains(officerId)) {
                continue;
            }
            LoanOfficerAssignment a = new LoanOfficerAssignment();
            a.setId(UUID.randomUUID());
            a.setUserId(target.userId());
            a.setImportedLoanId(target.importedLoanId());
            a.setOfficerId(officer.getId());
            a.setOfficerUserId(officer.getUserId());
            a.setOfficerName(officer.getName());
            a.setOfficerEmail(officer.getEmail());
            a.setAssignedAt(Instant.now());
            a.setAssignedBy(actor.getName() + " <" + actor.getEmail() + ">");
            // With no lead asked for, the first newcomer leads only if nobody does yet.
            boolean leads = needsLead && makeLead == null;
            a.setLead(leads);
            if (leads) {
                needsLead = false;
            }
            assignments.save(a);
            current.add(a);
            present.add(officerId);
            added.add(officer.getName());
        }

        if (makeLead != null) {
            for (LoanOfficerAssignment a : current) {
                boolean isLead = a.getOfficerId().equals(makeLead);
                if (a.isLead() != isLead) {
                    a.setLead(isLead);
                    assignments.save(a);
                }
            }
        }

        if (!added.isEmpty()) {
            audit.record(actorOf(actor), AuditCategory.LOAN,
                    added.size() == 1 ? "Loan officer assigned" : "Loan officers assigned",
                    String.join(", ", added) + (added.size() == 1 ? " now looks after " : " now look after ")
                            + target.name() + " (" + target.ref() + ").",
                    target.userId(), target.ref());
        }
    }

    private List<LoanOfficerAssignment> siblings(LoanOfficerAssignment a) {
        return new ArrayList<>(a.getUserId() != null
                ? assignments.findByUserIdOrderByLeadDescAssignedAtAsc(a.getUserId())
                : assignments.findByImportedLoanIdOrderByLeadDescAssignedAtAsc(a.getImportedLoanId()));
    }

    private String describe(LoanOfficerAssignment a) {
        if (a.getUserId() != null) {
            return users.findById(a.getUserId())
                    .map(u -> u.getFullName() + " (" + u.getCustomerRef() + ")")
                    .orElse("a customer");
        }
        return imports.findById(a.getImportedLoanId())
                .map(l -> l.getFullName() + " (paper loan)")
                .orElse("a paper loan");
    }

    private List<LoanOfficerDtos.AssignedOfficer> views(List<LoanOfficerAssignment> rows) {
        Map<UUID, AdminUser> team = team(rows);
        return rows.stream()
                .map(a -> {
                    AdminUser admin = team.get(a.getOfficerId());
                    return new LoanOfficerDtos.AssignedOfficer(
                            a.getId(), a.getOfficerId(), a.getOfficerName(), a.getOfficerEmail(),
                            admin == null ? null : admin.getPhone(), a.isLead(),
                            admin != null && admin.isActive(), a.getAssignedAt(), a.getAssignedBy());
                })
                .toList();
    }

    private Map<UUID, AdminUser> team(List<LoanOfficerAssignment> rows) {
        Map<UUID, AdminUser> out = new HashMap<>();
        admins.findAllById(rows.stream().map(LoanOfficerAssignment::getOfficerId).distinct().toList())
                .forEach(a -> out.put(a.getId(), a));
        return out;
    }

    private static AuditService.Actor actorOf(AdminUser admin) {
        return new AuditService.Actor(admin.getUserId(), admin.getName(), admin.getEmail());
    }
}
