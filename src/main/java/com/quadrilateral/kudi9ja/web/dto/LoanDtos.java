package com.quadrilateral.kudi9ja.web.dto;

import com.quadrilateral.kudi9ja.domain.loan.Installment;
import com.quadrilateral.kudi9ja.domain.loan.InstallmentStatus;
import com.quadrilateral.kudi9ja.domain.loan.Loan;
import com.quadrilateral.kudi9ja.domain.loan.LoanStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.quadrilateral.kudi9ja.domain.loan.RepaymentFrequency;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Quoting, requesting, servicing and settling a loan. */
public final class LoanDtos {

    private LoanDtos() {
    }

    public record RepayRequest(
            @NotNull @DecimalMin(value = "0.01", message = "An amount must be above zero.")
            BigDecimal amount,

            @NotBlank(message = "Your PIN is needed.")
            String pin) {
    }

    public record LoanPinRequest(
            @NotBlank(message = "Your PIN is needed.")
            String pin) {
    }

    /**
     * What a loan of this size and tenure would cost.
     *
     * <p>The client never prices a loan. Every figure here comes from the
     * server, from the same code that will price the loan itself.
     */
    public record QuoteResponse(
            BigDecimal principal,
            int months,
            BigDecimal flatRate,
            String flatRateLabel,
            BigDecimal costPerMonthPct,
            BigDecimal totalInterest,
            BigDecimal totalRepayable,
            RepaymentFrequency repaymentFrequency,
            String repaymentFrequencyLabel,
            int installmentCount,
            BigDecimal installmentAmount,
            /** The share of the loan and interest in one instalment, and the transaction fee on top. */
            BigDecimal installmentBase,
            BigDecimal installmentFee,
            /** The transaction fee on every repayment, as a fraction: 0.02 for 2%. */
            BigDecimal repaymentFeeRate,
            String repaymentFeeRateLabel,
            /** What the transaction fees on all the instalments add up to. */
            BigDecimal totalRepaymentFees,
            /** The loan and its interest, before the transaction fees. */
            BigDecimal loanAndInterest,
            BigDecimal processingFee,
            String processingFeeBasis,
            BigDecimal transactionFee,
            BigDecimal netDisbursed,
            Instant firstDueDate,
            Instant dueDate,
            List<ScheduleRow> schedule,
            boolean withinLimits,
            String note) {
    }

    public record ScheduleRow(
            int number,
            Instant dueDate,
            BigDecimal amount,
            BigDecimal amountPaid,
            BigDecimal outstanding,
            InstallmentStatus status,
            String statusLabel,
            long daysUntilDue,
            /** The share of the loan and interest in this instalment. */
            BigDecimal base,
            /** The transaction fee in this instalment. */
            BigDecimal fee) {

        public static ScheduleRow from(Installment installment, Instant now) {
            return new ScheduleRow(
                    installment.number(),
                    installment.dueDate(),
                    installment.amount(),
                    installment.amountPaid(),
                    installment.outstanding(),
                    installment.status(),
                    installment.status().label(),
                    installment.daysUntilDue(now),
                    installment.base(),
                    installment.fee());
        }
    }

    /** A loan, as the app shows it. */
    public record LoanResponse(
            UUID id,
            BigDecimal principal,
            int tenureMonths,
            BigDecimal flatRate,
            String flatRateLabel,
            BigDecimal processingFee,
            BigDecimal transactionFee,
            BigDecimal netDisbursed,
            String purpose,
            LoanStatus status,
            String statusLabel,
            Instant requestedAt,
            Instant disbursedAt,
            Instant dueDate,
            Instant settledAt,
            BigDecimal totalInterest,
            BigDecimal totalRepayable,
            RepaymentFrequency repaymentFrequency,
            String repaymentFrequencyLabel,
            int installmentCount,
            BigDecimal installmentAmount,
            /** The share of the loan and interest in one instalment, and the transaction fee on top. */
            BigDecimal installmentBase,
            BigDecimal installmentFee,
            /** The transaction fee on every repayment, as a fraction: 0.02 for 2%. */
            BigDecimal repaymentFeeRate,
            String repaymentFeeRateLabel,
            /** What the transaction fees on all the instalments add up to. */
            BigDecimal totalRepaymentFees,
            /** The loan and its interest, before the transaction fees. */
            BigDecimal loanAndInterest,
            BigDecimal amountRepaid,
            BigDecimal rebateGranted,
            BigDecimal outstanding,
            double repaymentProgress,
            int installmentsPaid,
            List<ScheduleRow> schedule,
            ScheduleRow nextInstallment,
            BigDecimal earlySettlementRebate,
            BigDecimal earlySettlementAmount,
            String decisionReasons) {

        public static LoanResponse from(
                Loan loan,
                Instant now,
                BigDecimal rebate,
                BigDecimal settlementAmount) {

            List<ScheduleRow> schedule = loan.schedule(now).stream()
                    .map(installment -> ScheduleRow.from(installment, now))
                    .toList();

            return new LoanResponse(
                    loan.getId(),
                    loan.getPrincipal(),
                    loan.getTenureMonths(),
                    loan.getFlatRate(),
                    ratePct(loan.getFlatRate()),
                    loan.getProcessingFee(),
                    loan.transactionFee(),
                    loan.netDisbursed(),
                    loan.getPurpose(),
                    loan.getStatus(),
                    loan.getStatus().label(),
                    loan.getRequestedAt(),
                    loan.getDisbursedAt(),
                    loan.getDueDate(),
                    loan.getSettledAt(),
                    loan.totalInterest(),
                    loan.totalRepayable(),
                    loan.frequency(),
                    loan.frequency().label(),
                    loan.installmentCount(),
                    loan.installmentAmount(),
                    schedule.isEmpty() ? java.math.BigDecimal.ZERO : schedule.get(0).base(),
                    schedule.isEmpty() ? java.math.BigDecimal.ZERO : schedule.get(0).fee(),
                    loan.repaymentFeeRate(),
                    ratePct(loan.repaymentFeeRate()),
                    loan.repaymentFees(),
                    loan.loanAndInterest(),
                    loan.getAmountRepaid(),
                    loan.getRebateGranted(),
                    loan.outstanding(),
                    loan.repaymentProgress(),
                    loan.installmentsPaid(now),
                    schedule,
                    schedule.stream()
                            .filter(row -> row.status() != InstallmentStatus.PAID)
                            .findFirst()
                            .orElse(null),
                    rebate,
                    settlementAmount,
                    loan.getDecisionReasons());
        }

        private static String ratePct(BigDecimal rate) {
            if (rate == null) {
                return "0%";
            }
            BigDecimal pct = rate.multiply(BigDecimal.valueOf(100))
                    .setScale(2, java.math.RoundingMode.HALF_UP)
                    .stripTrailingZeros();
            return pct.toPlainString() + "%";
        }
    }

    /**
     * Whether this customer may apply, and the range they may ask within.
     *
     * <p>There is no offer and no score. A customer asks for what they need,
     * between the smallest and the largest loan the company writes, and a
     * person decides on the application. The only things that refuse here are
     * the ones no application can change: an unverified identity, a frozen
     * account, lending switched off.
     */
    public record EligibilityResponse(
            boolean eligible,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            int minMonths,
            int maxMonths,
            Map<Integer, BigDecimal> rateCard,
            String reason,
            /** True when this customer borrows on the loan-group terms. */
            boolean groupPricing,
            /** Everything that prices this customer's loan, standard or group. */
            SettingsDtos.Lending terms) {
    }

    /** What happened when a loan was repaid, settled or cancelled. */
    public record RepaymentResponse(
            UUID loanId,
            BigDecimal amountPaid,
            BigDecimal rebate,
            BigDecimal outstanding,
            LoanStatus status,
            BigDecimal newBalance,
            String message) {
    }
}
