package com.quadrilateral.kudi9ja.web.dto;

import com.quadrilateral.kudi9ja.domain.loanapplication.Guarantor;
import com.quadrilateral.kudi9ja.domain.loanapplication.LoanApplication;
import com.quadrilateral.kudi9ja.domain.loanapplication.LoanApplicationStatus;
import com.quadrilateral.kudi9ja.domain.loanapplication.StoredDocument;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** Applying to borrow, and the decision on it. */
public final class LoanApplicationDtos {

    private LoanApplicationDtos() {
    }

    /**
     * One guarantor, as the form sends them.
     *
     * <p>The BVN is validated for <b>shape</b> and nothing else. Eleven digits
     * is all we can honestly check about a number belonging to somebody who is
     * not our customer and has not consented to us asking about them.
     */
    public record GuarantorPayload(
            @NotBlank(message = "Enter the guarantor's full name.")
            @Size(max = 160, message = "That name is too long.")
            String fullName,

            @NotBlank(message = "Enter the guarantor's phone number.")
            @Pattern(regexp = "^0[7-9][01]\\d{8}$",
                    message = "A Nigerian mobile number is eleven digits.")
            String phone,

            @NotBlank(message = "Enter the guarantor's address.")
            @Size(max = 400, message = "That address is too long.")
            String address,

            @NotBlank(message = "Say what this guarantor is to you.")
            @Size(max = 120, message = "Keep that to a few words.")
            String relationship,

            @NotBlank(message = "Enter the guarantor's BVN.")
            @Pattern(regexp = "^\\d{11}$", message = "A BVN is eleven digits.")
            String bvn,

            @Size(max = 160, message = "That is too long.")
            String occupation,

            @Email(message = "That email address does not look right.")
            @Size(max = 200, message = "That email address is too long.")
            String email) {

        public Guarantor toGuarantor() {
            Guarantor guarantor = new Guarantor();
            guarantor.setFullName(fullName.trim());
            guarantor.setPhone(phone.trim());
            guarantor.setAddress(address.trim());
            guarantor.setRelationship(relationship.trim());
            guarantor.setBvn(bvn.trim());
            guarantor.setOccupation(occupation == null ? null : occupation.trim());
            guarantor.setEmail(email == null || email.isBlank() ? null : email.trim());
            return guarantor;
        }

        public static GuarantorPayload from(Guarantor guarantor) {
            return new GuarantorPayload(
                    guarantor.getFullName(),
                    guarantor.getPhone(),
                    guarantor.getAddress(),
                    guarantor.getRelationship(),
                    guarantor.getBvn(),
                    guarantor.getOccupation(),
                    guarantor.getEmail());
        }
    }

    /**
     * The part of the form that is not a file.
     *
     * <p>Sent as one JSON part alongside the uploads, rather than as a dozen
     * form fields: the guarantors are a repeated structure, and flattening them
     * into {@code guarantor1Bvn}, {@code guarantor2Bvn} makes the shape a
     * naming convention that both sides have to remember.
     */
    public record ApplicationFormRequest(
            @NotNull(message = "How much do you need?")
            @DecimalMin(value = "0.01", message = "An amount must be above zero.")
            BigDecimal amount,

            @NotNull(message = "Over how many months?")
            @Min(value = 1, message = "A loan runs for at least a month.")
            @Max(value = 60, message = "That tenure is not offered.")
            Integer months,

            @NotBlank(message = "Say what the loan is for.")
            @Size(max = 200, message = "Keep the purpose short.")
            String purpose,

            @NotBlank(message = "Enter the name of your business.")
            @Size(max = 200, message = "That name is too long.")
            String businessName,

            @NotBlank(message = "Enter the address of your business.")
            @Size(max = 400, message = "That address is too long.")
            String businessAddress,

            @DecimalMin(value = "0", message = "An income cannot be negative.")
            BigDecimal monthlyIncome,

            @NotNull(message = "Two guarantors are needed.")
            @Size(min = 2, max = 2, message = "Two guarantors are needed, no more and no fewer.")
            List<@Valid GuarantorPayload> guarantors,

            @NotBlank(message = "Enter your PIN.")
            String pin) {
    }

    /** One uploaded document, as the panel receives it. */
    public record ApplicationDocument(
            String label,
            String contentType,
            Long sizeBytes,
            String url) {

        static ApplicationDocument of(
                String label, StoredDocument document, Function<String, String> urlFor) {
            return new ApplicationDocument(
                    label,
                    document.getContentType(),
                    document.getSizeBytes(),
                    urlFor.apply(document.getKey()));
        }
    }

    /**
     * What the customer sees of their own application.
     *
     * <p>No document URLs: the customer uploaded these files and has them
     * already, and minting a signed link to a bank statement for anybody who
     * asks is a way of handing one out. The rejection reason is the field that
     * matters here — it is the whole point of telling them.
     */
    public record LoanApplicationResponse(
            UUID id,
            BigDecimal amount,
            int tenureMonths,
            String purpose,
            String businessName,
            String businessAddress,
            BigDecimal monthlyIncome,
            List<GuarantorPayload> guarantors,
            int documentsAttached,
            LoanApplicationStatus status,
            String statusLabel,
            Instant submittedAt,
            Instant reviewedAt,
            String rejectionReason,
            UUID loanId) {

        public static LoanApplicationResponse from(LoanApplication application) {
            return new LoanApplicationResponse(
                    application.getId(),
                    application.getAmount(),
                    application.getTenureMonths(),
                    application.getPurpose(),
                    application.getBusinessName(),
                    application.getBusinessAddress(),
                    application.getMonthlyIncome(),
                    application.getGuarantors().stream().map(GuarantorPayload::from).toList(),
                    application.allDocuments().size(),
                    application.getStatus(),
                    application.getStatus().label(),
                    application.getSubmittedAt(),
                    application.getReviewedAt(),
                    application.getRejectionReason(),
                    application.getLoanId());
        }
    }

    /** A row in the admin queue. Enough to triage, not enough to decide on. */
    public record AdminApplicationRow(
            UUID id,
            UUID userId,
            String customerName,
            String customerRef,
            BigDecimal amount,
            int tenureMonths,
            String purpose,
            String businessName,
            LoanApplicationStatus status,
            String statusLabel,
            Integer scoreAtSubmission,
            Instant submittedAt,
            Instant reviewedAt,
            String reviewedBy) {

        public static AdminApplicationRow from(LoanApplication application) {
            return new AdminApplicationRow(
                    application.getId(),
                    application.getUserId(),
                    application.getCustomerName(),
                    application.getCustomerRef(),
                    application.getAmount(),
                    application.getTenureMonths(),
                    application.getPurpose(),
                    application.getBusinessName(),
                    application.getStatus(),
                    application.getStatus().label(),
                    application.getScoreAtSubmission(),
                    application.getSubmittedAt(),
                    application.getReviewedAt(),
                    application.getReviewedBy());
        }
    }

    /**
     * The whole file, for the admin who has to decide it.
     *
     * <p>The document URLs are signed and expiring, and opening one is written
     * to the audit log. A bank statement says more about a person than anything
     * else they will ever send us.
     */
    public record AdminApplicationDetail(
            UUID id,
            UUID userId,
            String customerName,
            String customerRef,
            BigDecimal amount,
            int tenureMonths,
            String purpose,
            String businessName,
            String businessAddress,
            BigDecimal monthlyIncome,
            List<GuarantorPayload> guarantors,
            ApplicationDocument bankStatement,
            List<ApplicationDocument> businessPhotos,
            LoanApplicationStatus status,
            String statusLabel,
            Integer scoreAtSubmission,
            Instant submittedAt,
            Instant reviewedAt,
            String reviewedBy,
            String rejectionReason,
            UUID loanId) {

        public static AdminApplicationDetail from(
                LoanApplication application, Function<String, String> urlFor) {

            List<ApplicationDocument> photos = new java.util.ArrayList<>();
            List<StoredDocument> stored = application.getBusinessPhotos();
            for (int i = 0; i < stored.size(); i++) {
                photos.add(ApplicationDocument.of(
                        "Business premises " + (i + 1), stored.get(i), urlFor));
            }

            return new AdminApplicationDetail(
                    application.getId(),
                    application.getUserId(),
                    application.getCustomerName(),
                    application.getCustomerRef(),
                    application.getAmount(),
                    application.getTenureMonths(),
                    application.getPurpose(),
                    application.getBusinessName(),
                    application.getBusinessAddress(),
                    application.getMonthlyIncome(),
                    application.getGuarantors().stream().map(GuarantorPayload::from).toList(),
                    application.getBankStatement() == null
                            ? null
                            : ApplicationDocument.of(
                                    "Bank statement", application.getBankStatement(), urlFor),
                    photos,
                    application.getStatus(),
                    application.getStatus().label(),
                    application.getScoreAtSubmission(),
                    application.getSubmittedAt(),
                    application.getReviewedAt(),
                    application.getReviewedBy(),
                    application.getRejectionReason(),
                    application.getLoanId());
        }
    }

    /** An approval, with an optional note for the record. */
    public record ApproveApplicationRequest(
            @Size(max = 1000, message = "That note is too long.")
            String note) {
    }

    /**
     * A refusal.
     *
     * <p>The reason is required and is shown to the customer verbatim. Write it
     * for them.
     */
    public record RejectApplicationRequest(
            @NotBlank(message = "Say why this was declined. The customer is shown what you write.")
            @Size(min = 10, max = 1000,
                    message = "Give the customer a sentence they can act on.")
            String reason) {
    }
}
