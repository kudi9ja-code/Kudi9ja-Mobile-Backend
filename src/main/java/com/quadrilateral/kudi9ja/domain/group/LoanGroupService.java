package com.quadrilateral.kudi9ja.domain.group;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.domain.admin.AdminAccessService;
import com.quadrilateral.kudi9ja.domain.admin.AdminRole;
import com.quadrilateral.kudi9ja.domain.admin.AdminUser;
import com.quadrilateral.kudi9ja.domain.admin.AdminUserRepository;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.loan.ImportedLoan;
import com.quadrilateral.kudi9ja.domain.loan.ImportedLoanRepository;
import com.quadrilateral.kudi9ja.domain.loan.Loan;
import com.quadrilateral.kudi9ja.domain.loan.LoanRepository;
import com.quadrilateral.kudi9ja.domain.loan.LoanStatus;
import com.quadrilateral.kudi9ja.domain.officer.LoanOfficerService;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.web.dto.LoanGroupDtos;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Group loans.
 *
 * <p>Anyone with access to the admin panel may open a group, and must name its
 * loan officer — any member of the team — when they do. After that the group is
 * run by that officer, whatever their role: adding borrowers, correcting them,
 * taking them off. An owner or administrator can step in. Every borrower added to a group
 * gets the group's officer as their lead loan officer.
 *
 * <p>Borrowers are added with the full paper-loan form (through the loan
 * import, which calls back into {@link #addImported}), or picked from the
 * customers who already have an account.
 */
@Service
public class LoanGroupService {

    private final LoanGroupRepository groups;
    private final LoanGroupMemberRepository members;
    private final AdminUserRepository admins;
    private final UserRepository users;
    private final ImportedLoanRepository imports;
    private final LoanRepository loans;
    private final LoanOfficerService officers;
    private final AdminAccessService access;
    private final AuditService audit;

    public LoanGroupService(
            LoanGroupRepository groups,
            LoanGroupMemberRepository members,
            AdminUserRepository admins,
            UserRepository users,
            ImportedLoanRepository imports,
            LoanRepository loans,
            LoanOfficerService officers,
            AdminAccessService access,
            AuditService audit) {
        this.groups = groups;
        this.members = members;
        this.admins = admins;
        this.users = users;
        this.imports = imports;
        this.loans = loans;
        this.officers = officers;
        this.access = access;
        this.audit = audit;
    }

    // ── Reading ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<LoanGroupDtos.LoanGroupView> list() {
        AdminUser me = access.requireCanView();
        List<LoanGroup> all = groups.findAllByOrderByNumberAsc();
        Map<UUID, List<LoanGroupMember>> byGroup = members.findByGroupIdIn(all.stream().map(LoanGroup::getId).toList())
                .stream().collect(Collectors.groupingBy(LoanGroupMember::getGroupId));
        return all.stream().map(g -> view(g, byGroup.getOrDefault(g.getId(), List.of()), me)).toList();
    }

    @Transactional(readOnly = true)
    public LoanGroupDtos.LoanGroupDetail detail(UUID groupId) {
        AdminUser me = access.requireCanView();
        LoanGroup g = require(groupId);
        List<LoanGroupMember> rows = members.findByGroupIdOrderByAddedAtAsc(groupId);
        return new LoanGroupDtos.LoanGroupDetail(view(g, rows, me), memberViews(rows));
    }

    /** Every group membership, for labelling the customers list. */
    @Transactional(readOnly = true)
    public List<LoanGroupDtos.GroupLabel> labels() {
        access.requireCanView();
        Map<UUID, LoanGroup> byId = groups.findAll().stream()
                .collect(Collectors.toMap(LoanGroup::getId, Function.identity()));
        return members.findAll().stream()
                .filter(m -> byId.containsKey(m.getGroupId()))
                .map(m -> {
                    LoanGroup g = byId.get(m.getGroupId());
                    return new LoanGroupDtos.GroupLabel(m.getUserId(), m.getImportedLoanId(), g.getId(), g.getNumber(), g.getName());
                })
                .toList();
    }

    /** The groups one customer belongs to, for their profile. */
    @Transactional(readOnly = true)
    public List<LoanGroupDtos.LoanGroupView> forCustomer(UUID customerId) {
        AdminUser me = access.requireCanView();
        List<UUID> ids = members.findByUserId(customerId).stream().map(LoanGroupMember::getGroupId).distinct().toList();
        return groups.findAllById(ids).stream()
                .sorted(Comparator.comparingInt(LoanGroup::getNumber))
                .map(g -> view(g, members.findByGroupIdOrderByAddedAtAsc(g.getId()), me))
                .toList();
    }

    @Transactional(readOnly = true)
    public int nextNumber() {
        access.requireCanView();
        return groups.highestNumber() + 1;
    }

    // ── Running a group ───────────────────────────────────────────────────

    @Transactional
    public LoanGroupDtos.LoanGroupDetail create(LoanGroupDtos.CreateLoanGroup request) {
        AdminUser actor = access.requireCanView();
        int number = request.number() == null ? groups.highestNumber() + 1 : request.number();
        if (groups.existsByNumber(number)) {
            throw new ApiException(ErrorCode.CONFLICT, "Group " + number + " already exists. Choose another number.");
        }
        AdminUser officer = officerOrThrow(request.officerId());

        LoanGroup g = new LoanGroup();
        g.setId(UUID.randomUUID());
        g.setNumber(number);
        g.setName(request.name().trim());
        g.setArea(blankToNull(request.area()));
        g.setDescription(blankToNull(request.description()));
        setOfficer(g, officer);
        g.setCreatedAt(Instant.now());
        g.setCreatedBy(actor.getName() + " <" + actor.getEmail() + ">");
        groups.save(g);

        audit.record(actorOf(actor), AuditCategory.LOAN, "Loan group opened",
                actor.getName() + " opened " + g.label() + (g.getArea() == null ? "" : " (" + g.getArea() + ")")
                        + ", with " + officer.getName() + " as its loan officer.");
        return new LoanGroupDtos.LoanGroupDetail(view(g, List.of(), actor), List.of());
    }

    @Transactional
    public LoanGroupDtos.LoanGroupDetail update(UUID groupId, LoanGroupDtos.UpdateLoanGroup request) {
        LoanGroup g = require(groupId);
        AdminUser actor = requireCanRun(g);
        if (request.name() != null && !request.name().isBlank()) {
            g.setName(request.name().trim());
        }
        if (request.area() != null) {
            g.setArea(blankToNull(request.area()));
        }
        if (request.description() != null) {
            g.setDescription(blankToNull(request.description()));
        }
        if (request.active() != null) {
            g.setActive(request.active());
        }
        if (request.officerId() != null && !request.officerId().equals(g.getOfficerId())) {
            AdminUser officer = officerOrThrow(request.officerId());
            String before = g.getOfficerName();
            setOfficer(g, officer);
            // The new officer takes on every borrower in the group, and leads.
            for (LoanGroupMember m : members.findByGroupIdOrderByAddedAtAsc(groupId)) {
                giveOfficer(m, officer, actor);
            }
            audit.record(actorOf(actor), AuditCategory.LOAN, "Loan group officer changed",
                    g.label() + " moved from " + before + " to " + officer.getName() + ".");
        }
        groups.save(g);
        return detail(groupId);
    }

    /** An existing customer joins the group. */
    @Transactional
    public LoanGroupDtos.LoanGroupDetail addCustomer(UUID groupId, String customerRef) {
        LoanGroup g = require(groupId);
        AdminUser actor = requireCanRun(g);
        User user = users.findByCustomerRef(customerRef.trim().toUpperCase(Locale.ROOT))
                .or(() -> users.findByCustomerRef(customerRef.trim()))
                .orElseThrow(() -> ApiException.notFound("A customer with that account number"));
        if (members.findByGroupIdAndUserId(groupId, user.getId()).isPresent()) {
            throw new ApiException(ErrorCode.CONFLICT, user.getFullName() + " is already in " + g.label() + ".");
        }
        LoanGroupMember m = new LoanGroupMember();
        m.setId(UUID.randomUUID());
        m.setGroupId(groupId);
        m.setUserId(user.getId());
        m.setAddedAt(Instant.now());
        m.setAddedBy(actor.getName());
        members.save(m);
        giveOfficer(m, admins.findById(g.getOfficerId()).orElse(null), actor);
        audit.record(actorOf(actor), AuditCategory.LOAN, "Added to a loan group",
                user.getFullName() + " (" + user.getCustomerRef() + ") joined " + g.label() + ".", user.getId(), user.getCustomerRef());
        return detail(groupId);
    }

    @Transactional
    public LoanGroupDtos.LoanGroupDetail removeMember(UUID groupId, UUID memberId) {
        LoanGroup g = require(groupId);
        AdminUser actor = requireCanRun(g);
        LoanGroupMember m = members.findById(memberId)
                .filter(x -> x.getGroupId().equals(groupId))
                .orElseThrow(() -> ApiException.notFound("That member"));
        members.delete(m);
        audit.record(actorOf(actor), AuditCategory.LOAN, "Removed from a loan group",
                nameOf(m) + " left " + g.label() + ". Their loan and its records are unchanged.");
        return detail(groupId);
    }

    // ── Hooks for the loan import ─────────────────────────────────────────

    /**
     * Checks the signed-in admin may enter a borrower into this group — its
     * officer, an owner or an administrator — and returns them as the actor.
     */
    @Transactional(readOnly = true)
    public AdminUser requireCanAddTo(UUID groupId) {
        LoanGroup g = require(groupId);
        AdminUser actor = requireCanRun(g);
        if (!g.isActive()) {
            throw new ApiException(ErrorCode.CONFLICT, g.label() + " is closed to new borrowers.");
        }
        return actor;
    }

    /** The group's officer, who leads on every borrower in it. */
    @Transactional(readOnly = true)
    public AdminUser officerOf(UUID groupId) {
        return admins.findById(require(groupId).getOfficerId()).orElse(null);
    }

    /** A paper loan was just entered into a group. */
    @Transactional
    public void addImported(UUID groupId, ImportedLoan loan, AdminUser actor) {
        LoanGroup g = require(groupId);
        LoanGroupMember m = new LoanGroupMember();
        m.setId(UUID.randomUUID());
        m.setGroupId(groupId);
        m.setImportedLoanId(loan.getId());
        m.setUserId(loan.getUserId());
        m.setAddedAt(Instant.now());
        m.setAddedBy(actor.getName());
        members.save(m);
        audit.record(actorOf(actor), AuditCategory.LOAN, "Added to a loan group",
                loan.getFullName() + " was entered into " + g.label() + " with a " + Money.naira(loan.getPrincipal()) + " loan.");
    }

    /** The borrower signed up: their group places now name their account too. */
    @Transactional
    public void attachAccount(UUID importedLoanId, UUID userId) {
        for (LoanGroupMember m : members.findByImportedLoanId(importedLoanId)) {
            if (m.getUserId() == null) {
                m.setUserId(userId);
                members.save(m);
            }
        }
    }

    // ── Plumbing ──────────────────────────────────────────────────────────

    private LoanGroup require(UUID groupId) {
        return groups.findById(groupId).orElseThrow(() -> ApiException.notFound("That group"));
    }

    /** The group's own officer, whatever their role, or an owner or administrator. */
    private AdminUser requireCanRun(LoanGroup g) {
        AdminUser actor = access.requireCanView();
        if (!canRun(g, actor)) {
            throw ApiException.forbidden(g.label() + " is run by " + g.getOfficerName()
                    + ". Only they, an owner or an administrator can change it.");
        }
        return actor;
    }

    private static boolean canRun(LoanGroup g, AdminUser admin) {
        return admin != null && admin.isActive()
                && (admin.getRole() == AdminRole.OWNER || admin.getRole() == AdminRole.ADMIN || admin.getId().equals(g.getOfficerId()));
    }

    private AdminUser officerOrThrow(UUID officerId) {
        AdminUser officer = admins.findById(officerId).orElseThrow(() -> ApiException.notFound("That team member"));
        if (!officer.isActive()) {
            throw ApiException.validation(officer.getName() + " cannot run a group: their panel access is suspended.");
        }
        return officer;
    }

    private static void setOfficer(LoanGroup g, AdminUser officer) {
        g.setOfficerId(officer.getId());
        g.setOfficerUserId(officer.getUserId());
        g.setOfficerName(officer.getName());
        g.setOfficerEmail(officer.getEmail());
    }

    private void giveOfficer(LoanGroupMember m, AdminUser officer, AdminUser actor) {
        if (officer == null) {
            return;
        }
        if (m.getUserId() != null) {
            officers.addInternally(m.getUserId(), null, officer, actor, true);
        } else if (m.getImportedLoanId() != null) {
            officers.addInternally(null, m.getImportedLoanId(), officer, actor, true);
        }
    }

    private LoanGroupDtos.LoanGroupView view(LoanGroup g, List<LoanGroupMember> rows, AdminUser me) {
        List<LoanGroupDtos.LoanGroupMemberView> mv = memberViews(rows);
        BigDecimal lent = mv.stream().map(LoanGroupDtos.LoanGroupMemberView::lent).reduce(Money.zero(), Money::add);
        BigDecimal owed = mv.stream().map(LoanGroupDtos.LoanGroupMemberView::outstanding).reduce(Money.zero(), Money::add);
        AdminUser officer = admins.findById(g.getOfficerId()).orElse(null);
        return new LoanGroupDtos.LoanGroupView(
                g.getId(), g.getNumber(), g.getName(), g.getArea(), g.getDescription(),
                g.getOfficerId(), g.getOfficerName(), g.getOfficerEmail(), officer != null && officer.isActive(),
                g.isActive(), mv.size(), (int) mv.stream().filter(LoanGroupDtos.LoanGroupMemberView::signedUp).count(),
                lent, owed, (int) mv.stream().filter(LoanGroupDtos.LoanGroupMemberView::overdue).count(),
                g.getCreatedAt(), g.getCreatedBy(), canRun(g, me));
    }

    private List<LoanGroupDtos.LoanGroupMemberView> memberViews(List<LoanGroupMember> rows) {
        Map<UUID, User> people = users.findAllById(rows.stream().map(LoanGroupMember::getUserId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        Map<UUID, ImportedLoan> paper = imports.findAllById(rows.stream().map(LoanGroupMember::getImportedLoanId).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(ImportedLoan::getId, Function.identity()));
        Instant now = Instant.now();
        List<LoanGroupDtos.LoanGroupMemberView> out = new ArrayList<>();
        for (LoanGroupMember m : rows) {
            User u = m.getUserId() == null ? null : people.get(m.getUserId());
            ImportedLoan p = m.getImportedLoanId() == null ? null : paper.get(m.getImportedLoanId());
            if (u != null) {
                List<Loan> theirs = loans.findByUserIdOrderByRequestedAtDesc(u.getId()).stream()
                        .filter(l -> l.getStatus() != LoanStatus.REJECTED && l.getStatus() != LoanStatus.CANCELLED && l.getStatus() != LoanStatus.PENDING)
                        .toList();
                BigDecimal lent = theirs.stream().map(Loan::getPrincipal).reduce(Money.zero(), Money::add);
                BigDecimal owed = theirs.stream().filter(Loan::isOpen).map(Loan::outstanding).reduce(Money.zero(), Money::add);
                boolean overdue = theirs.stream().anyMatch(l -> l.getStatus() == LoanStatus.OVERDUE);
                Instant nextDue = theirs.stream().filter(Loan::isOpen).map(Loan::getDueDate).filter(Objects::nonNull).min(Instant::compareTo).orElse(null);
                out.add(new LoanGroupDtos.LoanGroupMemberView(m.getId(), u.getId(), m.getImportedLoanId(), u.getFullName(),
                        u.getCustomerRef(), u.getPhone(), true, theirs.size(), lent, owed, overdue, nextDue, m.getAddedAt(), m.getAddedBy()));
            } else if (p != null) {
                BigDecimal owed = p.outstanding();
                Instant due = p.dueDate();
                boolean overdue = Money.isPositive(owed) && due != null && now.isAfter(due);
                out.add(new LoanGroupDtos.LoanGroupMemberView(m.getId(), null, p.getId(), p.getFullName(), null, p.getPhone(),
                        false, 1, p.getPrincipal(), owed, overdue, due, m.getAddedAt(), m.getAddedBy()));
            }
        }
        return out;
    }

    private String nameOf(LoanGroupMember m) {
        if (m.getUserId() != null) {
            return users.findById(m.getUserId()).map(User::getFullName).orElse("A customer");
        }
        return imports.findById(m.getImportedLoanId()).map(ImportedLoan::getFullName).orElse("A borrower");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static AuditService.Actor actorOf(AdminUser admin) {
        return new AuditService.Actor(admin.getUserId(), admin.getName(), admin.getEmail());
    }
}
