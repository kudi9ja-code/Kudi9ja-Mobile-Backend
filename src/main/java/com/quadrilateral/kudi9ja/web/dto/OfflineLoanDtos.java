package com.quadrilateral.kudi9ja.web.dto;

import com.quadrilateral.kudi9ja.domain.loan.LoanDocument;
import com.quadrilateral.kudi9ja.domain.loan.RecordedRepayment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The file on a loan made outside the app: the borrower's details, the
 * documents an admin attached, and the repayments that arrived by hand.
 */
public final class OfflineLoanDtos {

    private OfflineLoanDtos() {
    }

    /**
     * Corrections to a waiting borrower's file. Every field is optional and
     * only the ones sent are changed. The money terms are not here: a loan's
     * figures are corrected by removing the entry and entering it again,
     * which leaves both versions on the audit log.
     */
    public record OfflineLoanUpdate(
            /** Only while the customer has not activated their account: it is how they are recognised at signup. */
            @Pattern(regexp = "^\\d{11}$", message = "A BVN is eleven digits.")
            String bvn,

            @Size(min = 2, max = 160, message = "Enter the customer's full name.")
            String fullName,

            @Email(message = "That is not a valid email address.")
            @Size(max = 190, message = "That email address is too long.")
            String email,

            @Pattern(regexp = "^$|^0[7-9][01]\\d{8}$", message = "Enter a Nigerian mobile number, like 08012345678.")
            String phone,

            @Pattern(regexp = "^$|^\\d{11}$", message = "A NIN is eleven digits.")
            String nin,

            @Past(message = "The date of birth must be in the past.")
            LocalDate dateOfBirth,

            @Size(max = 40, message = "Keep the gender under 40 characters.") String gender,
            @Size(max = 400, message = "Keep the address under 400 characters.") String address,
            @Size(max = 60, message = "Keep the state under 60 characters.") String state,
            @Size(max = 160, message = "Keep the occupation under 160 characters.") String occupation,
            @Size(max = 200, message = "Keep the business name under 200 characters.") String businessName,
            @Size(max = 400, message = "Keep the business address under 400 characters.") String businessAddress,

            @DecimalMin(value = "0", message = "The monthly income cannot be negative.")
            BigDecimal monthlyIncome,

            @Size(max = 120, message = "Keep the bank name under 120 characters.") String payoutBank,

            @Pattern(regexp = "^$|^\\d{10}$", message = "An account number is ten digits.")
            String payoutAccountNumber,

            @Size(max = 200, message = "Keep the purpose under 200 characters.")
            String purpose,

            @Size(max = 2000, message = "Keep the notes under 2,000 characters.")
            String notes,

            @Size(max = 4, message = "Up to four guarantors.")
            List<LoanImportDtos.@Valid OfflineGuarantorPayload> guarantors) {
    }

    /** One attached document, with a signed link that expires. */
    public record OfflineDocument(
            UUID id,
            String label,
            String contentType,
            Long sizeBytes,
            String filename,
            String uploadedBy,
            Instant uploadedAt,
            String url) {

        public static OfflineDocument from(LoanDocument d, Function<String, String> urlFor) {
            return new OfflineDocument(
                    d.getId(),
                    d.getLabel(),
                    d.getContentType(),
                    d.getSizeBytes(),
                    d.getOriginalFilename(),
                    d.getUploadedBy(),
                    d.getUploadedAt(),
                    urlFor.apply(d.getStorageKey()));
        }
    }

    public record OfflineRepaymentRequest(
            @NotNull(message = "Enter the amount that was paid.")
            @DecimalMin(value = "0.01", message = "The amount paid must be above zero.")
            BigDecimal amount,

            /** When the money arrived. Defaults to now. */
            Instant paidAt,

            @NotBlank(message = "Say how it was paid.")
            @Pattern(regexp = "CASH|BANK_TRANSFER|POS|CHEQUE|OTHER",
                    message = "The method is one of CASH, BANK_TRANSFER, POS, CHEQUE or OTHER.")
            String method,

            @Size(max = 120, message = "Keep the reference under 120 characters.")
            String reference,

            @Size(max = 500, message = "Keep the note under 500 characters.")
            String note) {
    }

    public record OfflineRepayment(
            UUID id,
            UUID importedLoanId,
            UUID loanId,
            BigDecimal amount,
            Instant paidAt,
            String method,
            String reference,
            String note,
            String recordedBy,
            Instant recordedAt) {

        public static OfflineRepayment from(RecordedRepayment r) {
            return new OfflineRepayment(
                    r.getId(),
                    r.getImportedLoanId(),
                    r.getLoanId(),
                    r.getAmount(),
                    r.getPaidAt(),
                    r.getMethod(),
                    r.getReference(),
                    r.getNote(),
                    r.getRecordedBy(),
                    r.getRecordedAt());
        }
    }

    /** Everything on one imported loan, for the admin's page about it. */
    public record OfflineLoanFile(
            LoanImportDtos.ImportedLoanResponse loan,
            List<OfflineDocument> documents,
            List<OfflineRepayment> repayments) {
    }

    /**
     * What sits on a loan beyond its figures: documents and repayments
     * recorded by hand, whether against the loan or against the import it
     * came from.
     */
    public record LoanRecords(
            UUID loanId,
            UUID importedLoanId,
            List<OfflineDocument> documents,
            List<OfflineRepayment> repayments) {
    }

    /** Everything recorded by hand about one customer, across all their loans. */
    public record CustomerLoanRecords(
            List<LoanImportDtos.ImportedLoanResponse> importedLoans,
            List<OfflineDocument> documents,
            List<OfflineRepayment> repayments) {
    }
}
