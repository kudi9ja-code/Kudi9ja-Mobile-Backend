package com.quadrilateral.kudi9ja.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Group loans: numbered, named groups of borrowers, each run by one loan officer. */
public final class LoanGroupDtos {

    private LoanGroupDtos() {
    }

    public record CreateLoanGroup(
            /** Leave empty for the next free number. */
            @Min(value = 1, message = "A group number starts at 1.")
            @Max(value = 100000, message = "That group number is too large.")
            Integer number,

            @NotBlank(message = "Give the group a name, such as the market it is in.")
            @Size(max = 120, message = "Keep the name under 120 characters.")
            String name,

            @Size(max = 200, message = "Keep the area under 200 characters.")
            String area,

            @Size(max = 1000, message = "Keep the description under 1,000 characters.")
            String description,

            @NotNull(message = "Choose the loan officer for this group.")
            UUID officerId) {
    }

    /** Only what is sent changes. */
    public record UpdateLoanGroup(
            @Size(min = 1, max = 120, message = "Keep the name under 120 characters.") String name,
            @Size(max = 200, message = "Keep the area under 200 characters.") String area,
            @Size(max = 1000, message = "Keep the description under 1,000 characters.") String description,
            UUID officerId,
            Boolean active) {
    }

    public record AddGroupCustomer(
            @NotBlank(message = "Enter the customer's account number.")
            @Size(max = 40, message = "Keep the account number under 40 characters.")
            String customerRef) {
    }

    public record LoanGroupView(
            UUID id,
            int number,
            String name,
            String area,
            String description,
            UUID officerId,
            String officerName,
            String officerEmail,
            boolean officerStillOnTeam,
            boolean active,
            int members,
            int signedUp,
            BigDecimal totalLent,
            BigDecimal outstanding,
            int overdueMembers,
            Instant createdAt,
            String createdBy,
            /** Whether the signed-in admin may add, edit and remove members. */
            boolean canManage) {
    }

    public record LoanGroupMemberView(
            UUID id,
            UUID customerId,
            UUID importedLoanId,
            String name,
            String customerRef,
            String phone,
            boolean signedUp,
            int loans,
            BigDecimal lent,
            BigDecimal outstanding,
            boolean overdue,
            Instant nextDue,
            Instant addedAt,
            String addedBy) {
    }

    /**
     * One place a borrower holds in a group, for labelling lists: the customer
     * (once signed up), the paper loan (until then, and kept after), and the group.
     */
    public record GroupLabel(UUID customerId, UUID importedLoanId, UUID groupId, int number, String name) {
    }

    public record LoanGroupDetail(LoanGroupView group, List<LoanGroupMemberView> members) {
    }
}
