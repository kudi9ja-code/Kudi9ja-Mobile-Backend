package com.quadrilateral.kudi9ja.web.dto;

import com.quadrilateral.kudi9ja.domain.admin.AdminRole;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Loan officers: who on the team looks after which borrower. */
public final class LoanOfficerDtos {

    private LoanOfficerDtos() {
    }

    /** Someone on the team who can be made a loan officer. */
    public record OfficerOption(UUID id, String name, String email, AdminRole role, String roleLabel, long borrowers) {
    }

    public record AssignOfficers(
            @NotEmpty(message = "Choose at least one loan officer.")
            @Size(max = 10, message = "Up to ten loan officers at once.")
            List<UUID> officerIds,

            /** Make the first of these the lead. */
            boolean lead) {
    }

    /** An officer on a borrower, as the panel sees it. */
    public record AssignedOfficer(
            UUID id,
            UUID officerId,
            String name,
            String email,
            String phone,
            boolean lead,
            boolean stillOnTeam,
            Instant assignedAt,
            String assignedBy) {
    }

    /** An officer as the customer sees them: the name only, no contact details. */
    public record MyOfficer(String name, boolean lead) {
    }

    /** One borrower in an officer's book. */
    public record OfficerBorrower(
            UUID assignmentId,
            UUID customerId,
            UUID importedLoanId,
            String name,
            String customerRef,
            boolean signedUp,
            boolean lead,
            Instant assignedAt) {
    }
}
