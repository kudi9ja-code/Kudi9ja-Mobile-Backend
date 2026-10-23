package com.quadrilateral.kudi9ja.web.dto;

import com.quadrilateral.kudi9ja.common.util.Masks;
import com.quadrilateral.kudi9ja.domain.loan.ImportedLoan;
import com.quadrilateral.kudi9ja.domain.loan.OfflineGuarantor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.quadrilateral.kudi9ja.domain.loan.RepaymentFrequency;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

            /** How often they pay back. Has to be chosen. */
            @NotNull(message = "Choose whether they repay weekly or monthly.")
            RepaymentFrequency repaymentFrequency,

            // The rate card runs past 100% at the long tenures (134% over 24
            // months), so the ceiling sits above it. It is still low enough to
            // catch a percentage typed where a fraction belongs: 10, not 0.10.
            @NotNull(message = "Enter the flat rate that was charged, as a fraction: 0.10 for 10%.")
            @DecimalMin(value = "0", message = "The rate cannot be negative.")
            @DecimalMax(value = "3", message = "The rate is a fraction: 0.10 for 10%, not 10.")
            BigDecimal flatRate,

            @DecimalMin(value = "0", message = "The management fee cannot be negative.")
            BigDecimal processingFee,

            /**
             * A flat fee taken off the payout. Loans are no longer given with
             * one — the transaction fee is on each repayment — so it is only
             * for an older paper loan that had one.
             */
            @DecimalMin(value = "0", message = "The transaction fee cannot be negative.")
            BigDecimal transactionFee,

            /** The transaction fee on each repayment, as a fraction: 0.02 for 2%. Today's rate if left out. */
            @DecimalMin(value = "0", message = "The transaction fee on each repayment cannot be negative.")
            @DecimalMax(value = "0.2", message = "The transaction fee on each repayment is a fraction: 0.02 for 2%.")
            BigDecimal repaymentFeeRate,

            @NotBlank(message = "Say what the loan was for.")
            @Size(max = 200, message = "Keep the purpose under 200 characters.")
            String purpose,

            @NotNull(message = "Enter the date the money was sent.")
            Instant disbursedAt,

            @DecimalMin(value = "0", message = "The amount repaid cannot be negative.")
            BigDecimal amountRepaid,

            // ── The borrower's file. All optional. ──────────────────────────

            @Pattern(regexp = "^$|^\\d{11}$", message = "A NIN is eleven digits.")
            String nin,

            @Past(message = "The date of birth must be in the past.")
            LocalDate dateOfBirth,

            @Size(max = 40, message = "Keep the gender under 40 characters.")
            String gender,

            @Size(max = 400, message = "Keep the address under 400 characters.")
            String address,

            @Size(max = 60, message = "Keep the state under 60 characters.")
            String state,

            @Size(max = 160, message = "Keep the occupation under 160 characters.")
            String occupation,

            @Size(max = 200, message = "Keep the business name under 200 characters.")
            String businessName,

            @Size(max = 400, message = "Keep the business address under 400 characters.")
            String businessAddress,

            @DecimalMin(value = "0", message = "The monthly income cannot be negative.")
            BigDecimal monthlyIncome,

            @Size(max = 120, message = "Keep the bank name under 120 characters.")
            String payoutBank,

            @Pattern(regexp = "^$|^\\d{10}$", message = "An account number is ten digits.")
            String payoutAccountNumber,

            @Size(max = 2000, message = "Keep the notes under 2,000 characters.")
            String notes,

            @Size(max = 4, message = "Up to four guarantors.")
            List<@Valid OfflineGuarantorPayload> guarantors,

            /** The loan officers to look after this borrower; the first leads. Empty means whoever enters it. */
            @Size(max = 10, message = "Up to ten loan officers.")
            List<UUID> officerIds,

            /** The loan group this borrower belongs to, if any. */
            UUID groupId) {
    }

    /** A guarantor as the admin types it. Only the name is required. */
    public record OfflineGuarantorPayload(
            @NotBlank(message = "Enter the guarantor's full name.")
            @Size(max = 160, message = "Keep the guarantor's name under 160 characters.")
            String fullName,

            @Pattern(regexp = "^$|^0[7-9][01]\\d{8}$",
                    message = "Enter the guarantor's Nigerian mobile number, like 08012345678.")
            String phone,

            @Size(max = 400, message = "Keep the guarantor's address under 400 characters.")
            String address,

            @Size(max = 60, message = "Keep the guarantor's state under 60 characters.")
            String state,

            @Size(max = 120, message = "Keep the local government under 120 characters.")
            String localGovernment,

            @Size(max = 200, message = "Keep the landmark under 200 characters.")
            String landmark,

            @Size(max = 120, message = "Keep the relationship under 120 characters.")
            String relationship,

            @Pattern(regexp = "^$|^\\d{11}$", message = "A guarantor's BVN is eleven digits.")
            String bvn,

            @Size(max = 160, message = "Keep the guarantor's occupation under 160 characters.")
            String occupation,

            @Email(message = "That is not a valid email address for the guarantor.")
            @Size(max = 200, message = "That email address is too long.")
            String email) {

        public OfflineGuarantor toEntity() {
            OfflineGuarantor g = new OfflineGuarantor();
            g.setFullName(fullName.trim());
            g.setPhone(blankToNull(phone));
            g.setAddress(blankToNull(address));
            g.setState(blankToNull(state));
            g.setLocalGovernment(blankToNull(localGovernment));
            g.setLandmark(blankToNull(landmark));
            g.setRelationship(blankToNull(relationship));
            g.setBvn(blankToNull(bvn));
            g.setOccupation(blankToNull(occupation));
            g.setEmail(blankToNull(email));
            return g;
        }

        public static OfflineGuarantorPayload from(OfflineGuarantor g) {
            return new OfflineGuarantorPayload(
                    g.getFullName(),
                    g.getPhone(),
                    g.getAddress(),
                    g.getState(),
                    g.getLocalGovernment(),
                    g.getLandmark(),
                    g.getRelationship(),
                    g.getBvn(),
                    g.getOccupation(),
                    g.getEmail());
        }
    }

    /** What the admin sees. The BVN and NIN are never shown whole, even to the person who typed them. */
    public record ImportedLoanResponse(
            UUID id,
            String bvnLast4,
            String fullName,
            String email,
            String phone,
            BigDecimal principal,
            int tenureMonths,
            RepaymentFrequency repaymentFrequency,
            BigDecimal flatRate,
            BigDecimal processingFee,
            BigDecimal transactionFee,
            /** The transaction fee on each repayment, as a fraction, and what those fees add up to. */
            BigDecimal repaymentFeeRate,
            BigDecimal repaymentFees,
            int installmentCount,
            /** Instalments in whole naira, rounded up — every loan since the repayment fee. */
            boolean wholeNaira,
            BigDecimal netDisbursed,
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
            UUID loanId,
            String ninLast4,
            LocalDate dateOfBirth,
            String gender,
            String address,
            String state,
            String occupation,
            String businessName,
            String businessAddress,
            BigDecimal monthlyIncome,
            String payoutBank,
            String payoutAccountNumber,
            String notes,
            List<OfflineGuarantorPayload> guarantors) {

        public static ImportedLoanResponse from(ImportedLoan row) {
            return from(row, null, null);
        }

        /**
         * With the live loan when the entry is on an account: what has been
         * repaid and what is still owed come from the loan as it stands, not
         * from the day it was entered.
         */
        public static ImportedLoanResponse from(
                ImportedLoan row,
                com.quadrilateral.kudi9ja.domain.loan.Loan live,
                com.quadrilateral.kudi9ja.domain.user.User owner) {
            // Once on an account, who the borrower is lives there.
            String bvn = owner != null ? owner.getBvn() : row.getBvn();
            String nin = owner != null ? owner.getNin() : row.getNin();
            return new ImportedLoanResponse(
                    row.getId(),
                    bvn == null ? null : Masks.lastFour(bvn),
                    row.getFullName(),
                    owner != null ? owner.getEmail() : row.getEmail(),
                    owner != null ? owner.getPhone() : row.getPhone(),
                    row.getPrincipal(),
                    row.getTenureMonths(),
                    RepaymentFrequency.orMonthly(row.getRepaymentFrequency()),
                    row.getFlatRate(),
                    row.getProcessingFee(),
                    row.transactionFee(),
                    row.getRepaymentFeeRate(),
                    live == null ? row.repaymentFees() : live.repaymentFees(),
                    live == null ? row.installmentCount() : live.installmentCount(),
                    live == null || live.isWholeNaira(),
                    row.netDisbursed(),
                    row.getPurpose(),
                    row.getDisbursedAt(),
                    row.dueDate(),
                    live == null ? row.totalRepayable() : live.totalRepayable(),
                    live == null ? row.getAmountRepaid() : live.getAmountRepaid(),
                    live == null ? row.outstanding() : live.outstanding(),
                    row.getImportedBy(),
                    row.getImportedAt(),
                    row.isClaimed(),
                    row.getClaimedAt(),
                    row.getUserId(),
                    row.getLoanId(),
                    nin == null ? null : Masks.lastFour(nin),
                    owner != null ? owner.getDateOfBirth() : row.getDateOfBirth(),
                    row.getGender(),
                    row.getAddress(),
                    row.getState(),
                    row.getOccupation(),
                    row.getBusinessName(),
                    row.getBusinessAddress(),
                    row.getMonthlyIncome(),
                    row.getPayoutBank(),
                    row.getPayoutAccountNumber(),
                    row.getNotes(),
                    row.getGuarantors().stream().map(OfflineGuarantorPayload::from).toList());
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
