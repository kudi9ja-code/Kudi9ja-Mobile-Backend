package com.quadrilateral.kudi9ja.web.dto;

import com.quadrilateral.kudi9ja.common.util.Masks;
import com.quadrilateral.kudi9ja.domain.admin.AdminRole;
import com.quadrilateral.kudi9ja.domain.user.AccountStatus;
import com.quadrilateral.kudi9ja.domain.user.KycTier;
import com.quadrilateral.kudi9ja.domain.user.ThemeMode;
import com.quadrilateral.kudi9ja.domain.user.User;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The account, as the app sees it. */
public final class UserDtos {

    private UserDtos() {
    }

    /**
     * The profile.
     *
     * <p>Four things are deliberately absent and must stay absent: the password,
     * passcode and PIN hashes, and the security answer. The BVN and NIN are
     * present only as their last four digits.
     *
     * @param customerRef what a customer quotes so a payment can be matched. It
     *                    is <b>not an account number</b> and is not payable
     *                    into — Kudi9ja issues no account numbers.
     */
    public record ProfileResponse(
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
            boolean hasPayoutAccount,
            KycTier kycTier,
            String kycTierLabel,
            boolean emailVerified,
            boolean phoneVerified,
            boolean biometricsEnabled,
            String securityQuestion,
            ThemeMode themeMode,
            boolean hideBalance,
            boolean autoDebit,
            AccountStatus accountStatus,
            boolean admin,
            AdminRole adminRole,
            Instant createdAt) {

        /**
         * @param adminRole what this account may do in the panel, or null if it
         *                  holds no grant. It travels with the flag because the
         *                  app draws the panel entrance the moment it signs in,
         *                  and a flag on its own left it labelling an owner
         *                  "Viewer" until they had opened the panel once and
         *                  come back. Hiding a control is all it is used for —
         *                  the role is re-read from the database and enforced
         *                  again on every admin request.
         */
        public static ProfileResponse from(User user, AdminRole adminRole) {
            return new ProfileResponse(
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
                    user.hasPayoutAccount(),
                    user.getKycTier(),
                    user.getKycTier().label(),
                    user.isEmailVerified(),
                    user.isPhoneVerified(),
                    user.isBiometricsEnabled(),
                    user.getSecurityQuestion(),
                    user.getThemeMode(),
                    user.isHideBalance(),
                    user.isAutoDebit(),
                    user.getAccountStatus(),
                    adminRole != null,
                    adminRole,
                    user.getCreatedAt());
        }
    }

    /**
     * What a customer may change about themselves.
     *
     * <p>Name, date of birth, BVN and NIN are absent on purpose: those were
     * verified against the issuing institutions, and letting them be edited
     * afterwards would undo the verification. They change through support, with
     * evidence.
     *
     * <p>The payout account is absent too. It is where money leaves, so it
     * changes through its own endpoint, behind a one-time code and a fresh name
     * enquiry.
     */
    public record UpdateProfileRequest(
            @Pattern(regexp = "^0[7-9][01]\\d{8}$",
                    message = "A Nigerian mobile number is eleven digits.")
            String phone,

            @Size(max = 400, message = "That address is too long.")
            String address,

            String state,

            ThemeMode themeMode,

            Boolean hideBalance,

            Boolean biometricsEnabled,

            Boolean autoDebit) {
    }

    /** Changing where money leaves to. Confirmed by a code and a name enquiry. */
    public record ChangePayoutRequest(
            @jakarta.validation.constraints.NotBlank(message = "Choose your bank.")
            String bank,

            @jakarta.validation.constraints.NotBlank
            @Pattern(regexp = "^\\d{10}$", message = "An account number is ten digits.")
            String accountNumber,

            @jakarta.validation.constraints.NotBlank(message = "Enter the code we sent you.")
            String code,

            @jakarta.validation.constraints.NotBlank(message = "Your PIN is needed.")
            String pin) {
    }

    /** The figures the dashboard leads with, all computed here. */
    public record DashboardResponse(
            java.math.BigDecimal balance,
            java.math.BigDecimal totalSaved,
            java.math.BigDecimal totalOwed,
            java.math.BigDecimal netWorth,
            java.math.BigDecimal totalInterestEarned,
            java.math.BigDecimal thriftCommitted,
            int creditScore,
            String creditBand,
            int activePlans,
            int activeLoans,
            int activeCircles,
            long unreadNotifications,
            boolean hideBalance,
            NextRepayment nextRepayment) {
    }

    public record NextRepayment(
            UUID loanId,
            String purpose,
            int installmentNumber,
            java.math.BigDecimal amount,
            Instant dueDate,
            long daysUntilDue) {
    }
}
