package com.quadrilateral.kudi9ja.web.dto;

import com.quadrilateral.kudi9ja.common.util.Masks;
import com.quadrilateral.kudi9ja.domain.admin.AdminRole;
import com.quadrilateral.kudi9ja.domain.admin.AdminUser;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditEntry;
import com.quadrilateral.kudi9ja.domain.user.AccountStatus;
import com.quadrilateral.kudi9ja.domain.user.KycTier;
import com.quadrilateral.kudi9ja.domain.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * What the admin panel sends and receives.
 *
 * <p>The panel sees more of a customer than the customer's own app shows them,
 * because an admin matching a bank narration needs the full name and the
 * customer reference. It still never sees a hash, a security answer, or a full
 * BVN or NIN — an admin has no use for those, and the Privacy Policy promises
 * identity data is held to the minimum the job needs.
 */
public final class AdminDtos {

    private AdminDtos() {
    }

    // ---------------------------------------------------------------- team

    /**
     * Granting panel access.
     *
     * <p>Only the email and the role are sent. The name and phone are copied
     * from the account that already holds the address — they are never typed,
     * so the team list cannot drift from the accounts it names.
     */
    public record GrantAccessRequest(
            @NotBlank(message = "Enter the email address of the person you are giving access to.")
            @Email(message = "That is not a valid email address.")
            @Size(max = 190, message = "That email address is too long.")
            String email,

            @NotNull(message = "Choose a role.")
            AdminRole role) {
    }

    /** Promoting or demoting. The email cannot change; a new email is a new grant. */
    public record ChangeRoleRequest(
            @NotNull(message = "Choose a role.")
            AdminRole role) {
    }

    /** Suspending or restoring an existing grant. */
    public record SetActiveRequest(
            @NotNull
            Boolean active,

            @Size(max = 300, message = "Keep the reason under 300 characters.")
            String reason) {
    }

    public record TeamMemberResponse(
            UUID id,
            UUID userId,
            String name,
            String email,
            String phone,
            AdminRole role,
            String roleLabel,
            String roleBlurb,
            Instant addedAt,
            String addedBy,
            boolean active,
            Instant lastActiveAt,
            boolean isMe,
            boolean canBeEdited) {

        public static TeamMemberResponse from(AdminUser admin, UUID viewerAdminId) {
            boolean me = admin.getId().equals(viewerAdminId);
            return new TeamMemberResponse(
                    admin.getId(),
                    admin.getUserId(),
                    admin.getName(),
                    admin.getEmail(),
                    admin.getPhone(),
                    admin.getRole(),
                    admin.getRole().label(),
                    admin.getRole().blurb(),
                    admin.getAddedAt(),
                    admin.getAddedBy(),
                    admin.isActive(),
                    admin.getLastActiveAt(),
                    me,
                    // Self-lockout is impossible by construction, so the panel is
                    // told not to offer the buttons rather than being refused later.
                    !me);
        }
    }

    /** The role cards the panel renders when choosing a role. */
    public record RoleOption(String value, String label, String blurb) {

        public static List<RoleOption> all() {
            return Arrays.stream(AdminRole.values())
                    .map(r -> new RoleOption(r.name(), r.label(), r.blurb()))
                    .toList();
        }
    }

    // ------------------------------------------------------------ customers

    /** One row of the customer list. */
    public record CustomerRow(
            UUID id,
            String customerRef,
            String fullName,
            String email,
            String phone,
            KycTier kycTier,
            String kycTierLabel,
            AccountStatus accountStatus,
            String accountStatusLabel,
            BigDecimal balance,
            Instant createdAt,
            Instant lastActiveAt) {
    }

    /**
     * A customer's full record, as the panel opens it.
     *
     * <p>The BVN and NIN appear as their last four digits only: enough to
     * confirm a match against a document a customer has sent in, not enough to
     * make the panel a harvestable identity database.
     */
    public record CustomerDetail(
            UUID id,
            String customerRef,
            String fullName,
            String email,
            String phone,
            LocalDate dateOfBirth,
            String gender,
            String bvnLast4,
            String ninLast4,
            String address,
            String state,
            String payoutBank,
            String payoutAccountNumber,
            String payoutAccountName,
            boolean payoutVerified,
            KycTier kycTier,
            String kycTierLabel,
            boolean emailVerified,
            boolean phoneVerified,
            AccountStatus accountStatus,
            String accountStatusLabel,
            String statusNote,
            Instant createdAt,
            Instant lastActiveAt,
            boolean isAdmin,
            AdminRole adminRole,
            CustomerFinancials financials) {

        public static CustomerDetail from(User user, AdminUser grant, CustomerFinancials financials) {
            return new CustomerDetail(
                    user.getId(),
                    user.getCustomerRef(),
                    user.getFullName(),
                    user.getEmail(),
                    user.getPhone(),
                    user.getDateOfBirth(),
                    user.getGender(),
                    Masks.lastFour(user.getBvn()),
                    Masks.lastFour(user.getNin()),
                    user.getAddress(),
                    user.getState(),
                    user.getPayoutBank(),
                    user.getPayoutAccountNumber(),
                    user.getPayoutAccountName(),
                    user.getPayoutVerifiedAt() != null,
                    user.getKycTier(),
                    user.getKycTier().label(),
                    user.isEmailVerified(),
                    user.isPhoneVerified(),
                    user.getAccountStatus(),
                    user.getAccountStatus().label(),
                    user.getStatusNote(),
                    user.getCreatedAt(),
                    user.getLastActiveAt(),
                    grant != null && grant.isActive(),
                    grant == null ? null : grant.getRole(),
                    financials);
        }
    }

    /** The money side of a customer record. Every figure is derived server-side. */
    public record CustomerFinancials(
            BigDecimal balance,
            BigDecimal totalSaved,
            BigDecimal totalInterestEarned,
            BigDecimal totalDeposited,
            BigDecimal totalOwed,
            BigDecimal pendingWithdrawals,
            /**
             * Running now, and ever opened.
             *
             * <p>Both, because they answer different questions. "Two active
             * plans" describes the customer today; "two active of eleven"
             * describes somebody who has been saving with us for years, and an
             * admin deciding whether to release a payment wants the second.
             */
            long activePlans,
            long totalPlans,
            long activeLoans,
            long totalLoans,
            long overdueLoans,
            long transactionCount) {
    }

    /**
     * Changing a customer's standing.
     *
     * <p>Freezing stops money moving; flagging only records a concern. Both
     * carry a mandatory reason, so the audit log reads as a conversation rather
     * than a row of unexplained switches.
     */
    public record SetCustomerStatusRequest(
            @NotNull(message = "Choose a status.")
            AccountStatus status,

            @NotBlank(message = "Say why.")
            @Size(max = 500, message = "Keep it under 500 characters.")
            String reason) {
    }

    public record FlagCustomerRequest(
            @NotBlank(message = "Say why this customer is being flagged.")
            @Size(max = 500, message = "Keep it under 500 characters.")
            String reason) {
    }

    // ------------------------------------------------------------- overview

    /**
     * The panel's front page.
     *
     * <p>{@code customerFundsHeld} is what the collection account should be
     * holding on the customers' behalf. It is the figure to reconcile the bank
     * statement against, and it is deliberately first.
     */
    public record OverviewResponse(
            BigDecimal customerFundsHeld,
            Queues queues,
            Book book,
            People people,
            List<AuditRow> recentActivity,
            Instant generatedAt) {
    }

    /**
     * What is waiting on an admin. Both pay-ins and withdrawals carry a
     * one-working-day review promise, so the counts that have outrun it are
     * reported separately rather than buried in the total.
     */
    public record Queues(
            long pendingPayIns,
            BigDecimal pendingPayInValue,
            long pendingWithdrawals,
            BigDecimal pendingWithdrawalValue,
            long unmatchedPayIns,
            long payInsBreachingSla,
            long withdrawalsBreachingSla) {
    }

    public record Book(
            BigDecimal totalSaved,

            /** Interest paid <b>to</b> savers. Money the company has given up. */
            BigDecimal totalInterestPaid,

            BigDecimal totalLent,

            /**
             * Interest charged on the loans that are still running.
             *
             * <p>What the live book earns if it all comes back — contracted,
             * not collected. It sits beside {@link #totalInterestPaid} so the
             * panel shows both sides of the rate card rather than only what
             * savings cost.
             */
            BigDecimal totalInterestCharged,

            BigDecimal totalOverdue,
            long activePlans,
            long maturedPlans,
            long activeLoans,
            long overdueLoans,
            long repaidLoans) {
    }

    public record People(
            long customers,
            long activeCustomers,
            long frozenCustomers,
            long dormantCustomers,
            long newThisWeek,
            long admins) {
    }

    // ---------------------------------------------------------------- audit

    public record AuditRow(
            UUID id,
            String actor,
            AuditCategory category,
            String categoryLabel,
            String action,
            String detail,
            Instant date) {

        public static AuditRow from(AuditEntry entry) {
            return new AuditRow(
                    entry.getId(),
                    entry.getActor(),
                    entry.getCategory(),
                    entry.getCategory().label(),
                    entry.getAction(),
                    entry.getDetail(),
                    entry.getOccurredAt());
        }
    }

    // ----------------------------------------------------------------- loans

    /**
     * Writing a loan off closes it without payment. It is the one admin action
     * that forgives money, so the reason is mandatory.
     */
    public record WriteOffRequest(
            @NotBlank(message = "Say why this loan is being written off.")
            @Size(max = 500, message = "Keep it under 500 characters.")
            String reason) {
    }

    /**
     * A collections contact.
     *
     * <p>Logged, because the Privacy Policy commits to logging every one, and
     * refused outside 8am–8pm, because the same document commits to that too.
     */
    public record RemindRequest(
            @Size(max = 500, message = "Keep it under 500 characters.")
            String note) {
    }

    public record AdminLoanRow(
            UUID loanId,
            UUID customerId,
            String customerName,
            String customerRef,
            BigDecimal principal,
            BigDecimal outstanding,

            /**
             * What has come back, and what was taken as a fee. Both are on the
             * row because the panel's lending book totals them, and a total it
             * has to assemble from a call per loan is a total it will get
             * wrong.
             */
            BigDecimal amountRepaid,
            BigDecimal processingFee,

            int tenureMonths,
            String purpose,
            String status,
            String statusLabel,
            Instant disbursedAt,
            Instant dueDate,
            long daysOverdue) {
    }
}
