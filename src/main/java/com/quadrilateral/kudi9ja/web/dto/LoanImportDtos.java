package com.quadrilateral.kudi9ja.web.dto;

import com.quadrilateral.kudi9ja.common.util.Masks;
import com.quadrilateral.kudi9ja.domain.loan.ImportedLoan;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entering the loans that were written on paper before the app existed.
 *
 * <p>The admin types what the notebook says. The rate is the rate the customer
 * was actually charged, as a fraction the way the rate card holds it: 0.10 for
 * ten percent. What is still owed is worked out from the figures, never typed.
 */
public final class LoanImportDtos {

    private LoanImportDtos() {
    }

    public record ImportLoanRequest(
            @NotBlank(message = "The customer's BVN is needed. It is how the loan finds them when they sign up.")
            @Pattern(regexp = "^\\d{11}$", message = "A BVN is eleven digits.")
            String bvn,

            @NotBlank(message = "Enter the customer's name as it appears on their BVN.")
            @Size(min = 2, max = 160, message = "Enter the customer's full name.")
            String fullName,

            @Email(message = "That is not a valid email address.")
            @Size(max = 190, message = "That email address is too long.")
            String email,

            @Pattern(regexp = "^0[7-9][01]\\d{8}$",
                    message = "Enter a Nigerian mobile number, like 08012345678.")
            String phone,

            @NotNull(message = "Enter the amount that was lent.")
            @DecimalMin(value = "1", message = "The amount lent must be above zero.")
            BigDecimal principal,

            @Min(value = 1, message = "The tenure is at least one month.")
            @Max(value = 60, message = "The tenure is at most sixty months.")
            int tenureMonths,

            @NotNull(message = "Enter the flat rate that was charged, as a fraction: 0.10 for 10%.")
            @DecimalMin(value = "0", message = "The rate cannot be negative.")
            @DecimalMax(value = "0.999999", message = "The rate is a fraction: 0.10 for 10%, not 10.")
            BigDecimal flatRate,

            @DecimalMin(value = "0", message = "The processing fee cannot be negative.")
            BigDecimal processingFee,

            @NotBlank(message = "Say what the loan was for.")
            @Size(max = 200, message = "Keep the purpose under 200 characters.")
            String purpose,

            @NotNull(message = "Enter the date the money was sent.")
            Instant disbursedAt,

            @DecimalMin(value = "0", message = "The amount repaid cannot be negative.")
            BigDecimal amountRepaid) {
    }

    /** What the admin sees. The BVN is never shown whole, even to the person who typed it. */
    public record ImportedLoanResponse(
            UUID id,
            String bvnLast4,
            String fullName,
            String email,
            String phone,
            BigDecimal principal,
            int tenureMonths,
            BigDecimal flatRate,
            BigDecimal processingFee,
            String purpose,
            Instant disbursedAt,
            Instant dueDate,
            BigDecimal totalRepayable,
            BigDecimal amountRepaid,
            BigDecimal outstanding,
            String importedBy,
            Instant importedAt,
            boolean claimed,
            Instant claimedAt,
            UUID customerId,
            UUID loanId) {

        public static ImportedLoanResponse from(ImportedLoan row) {
            return new ImportedLoanResponse(
                    row.getId(),
                    row.getBvn() == null ? null : Masks.lastFour(row.getBvn()),
                    row.getFullName(),
                    row.getEmail(),
                    row.getPhone(),
                    row.getPrincipal(),
                    row.getTenureMonths(),
                    row.getFlatRate(),
                    row.getProcessingFee(),
                    row.getPurpose(),
                    row.getDisbursedAt(),
                    row.dueDate(),
                    row.totalRepayable(),
                    row.getAmountRepaid(),
                    row.outstanding(),
                    row.getImportedBy(),
                    row.getImportedAt(),
                    row.isClaimed(),
                    row.getClaimedAt(),
                    row.getUserId(),
                    row.getLoanId());
        }
    }
}
