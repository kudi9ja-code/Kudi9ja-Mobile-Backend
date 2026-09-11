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
            BigDecimal monthlyRepayment,
            BigDecimal processingFee,
            String processingFeeBasis,
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
            long daysUntilDue) {

        public static ScheduleRow from(Installment installment, Instant now) {
            return new ScheduleRow(
                    installment.number(),
                    installment.dueDate(),
                    installment.amount(),
                    installment.amountPaid(),
                    installment.outstanding(),
                    installment.status(),
                    installment.status().label(),
                    installment.daysUntilDue(now));
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
            BigDecimal monthlyRepayment,
            BigDecimal amountRepaid,
            BigDecimal rebateGranted,
            BigDecimal outstanding,
            double repaymentProgress,
            int installmentsPaid,
            List<ScheduleRow> schedule,
            ScheduleRow nextInstallment,
            boolean canCancel,
            BigDecimal cancellationAmount,
            Instant cancellationDeadline,
            BigDecimal earlySettlementRebate,
            BigDecimal earlySettlementAmount,
            String decisionReasons) {

        public static LoanResponse from(
                Loan loan,
                Instant now,
                BigDecimal rebate,
                BigDecimal settlementAmount,
                int cancellationWindowHours) {

            List<ScheduleRow> schedule = loan.schedule(now).stream()
                    .map(installment -> ScheduleRow.from(installment, now))
                    .toList();

            boolean canCancel = loan.withinCancellationWindow(now, cancellationWindowHours);

            return new LoanResponse(
                    loan.getId(),
                    loan.getPrincipal(),
                    loan.getTenureMonths(),
                    loan.getFlatRate(),
                    ratePct(loan.getFlatRate()),
                    loan.getProcessingFee(),
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
                    loan.monthlyRepayment(),
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
                    canCancel,
                    canCancel ? loan.cancellationAmount() : null,
                    loan.getDisbursedAt() == null
                            ? null
                            : loan.getDisbursedAt().plusSeconds(cancellationWindowHours * 3600L),
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
     * What this customer may borrow, and why.
     *
     * @param factors the score breakdown, so a customer can see what would
     *                move it rather than being handed a number
     */
    public record EligibilityResponse(
            boolean eligible,
            BigDecimal offer,
            BigDecimal headroom,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            int minMonths,
            int maxMonths,
            int creditScore,
            String creditBand,
            BigDecimal totalSaved,
            BigDecimal openPrincipal,
            List<CreditFactor> factors,
            Map<Integer, BigDecimal> rateCard,
            String reason) {
    }

    /** One contributor to the credit score. */
    public record CreditFactor(
            String label,
            String detail,
            int points,
            int maxPoints,
            boolean negative,
            double fill,
            boolean maxed) {

        public static CreditFactor of(String label, String detail, int points, int maxPoints) {
            return of(label, detail, points, maxPoints, false);
        }

        public static CreditFactor of(
                String label, String detail, int points, int maxPoints, boolean negative) {
            double fill = maxPoints <= 0 ? 0 : Math.max(0, Math.min(1, (double) points / maxPoints));
            return new CreditFactor(
                    label, detail, points, maxPoints, negative, fill, maxPoints > 0 && points >= maxPoints);
        }
    }

    /** The score on its own, with the breakdown behind it. */
    public record CreditScoreResponse(
            int score,
            String band,
            int floor,
            int ceiling,
            List<CreditFactor> factors,
            String note) {
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
