package com.quadrilateral.kudi9ja.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.domain.loan.Installment;
import com.quadrilateral.kudi9ja.domain.loan.Loan;
import com.quadrilateral.kudi9ja.domain.loan.RepaymentFrequency;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Weekly and monthly repayment, and the transaction fee on each repayment:
 * instalments rounded up to the naira, 2% of each on top, also rounded up.
 */
@DisplayName("Repayment frequency and the transaction fee on each repayment")
class RepaymentFrequencyTest {

    private static final BigDecimal TWO_PERCENT = new BigDecimal("0.02");

    @Test
    @DisplayName("weekly counts follow the calendar from the day the money is paid out")
    void weeksFollowTheCalendar() {
        // 23 October to 23 January is 92 days: 13 whole weeks.
        assertThat(RepaymentFrequency.WEEKLY.installments(lagos("2026-10-23"), 3)).isEqualTo(13);
        // 30 November to 28 February is 90 days: 12 whole weeks.
        assertThat(RepaymentFrequency.WEEKLY.installments(lagos("2026-11-30"), 3)).isEqualTo(12);
        // A month of 31 days is 4 weeks; a year of 365 days is 52.
        assertThat(RepaymentFrequency.WEEKLY.installments(lagos("2026-10-23"), 1)).isEqualTo(4);
        assertThat(RepaymentFrequency.WEEKLY.installments(lagos("2026-10-23"), 12)).isEqualTo(52);
        // Monthly is one a month, whatever the date.
        assertThat(RepaymentFrequency.MONTHLY.installments(lagos("2026-10-23"), 6)).isEqualTo(6);
    }

    @Test
    @DisplayName("₦125,000 over 12 weeks: ₦10,417 + ₦209 = ₦10,626 a week")
    void weeklyExample() {
        List<Finance.InstalmentPart> plan = Finance.instalmentPlan(new BigDecimal("125000"), 12, TWO_PERCENT, true);

        assertThat(plan).hasSize(12);
        assertThat(plan.get(0).base()).isEqualByComparingTo("10417");
        assertThat(plan.get(0).fee()).isEqualByComparingTo("209");
        assertThat(plan.get(0).total()).isEqualByComparingTo("10626");
        // The last takes the rounding, so the shares add up to exactly ₦125,000.
        assertThat(plan.get(11).base()).isEqualByComparingTo("10413");
        assertThat(plan.get(11).fee()).isEqualByComparingTo("209");
        assertThat(sumOfBases(plan)).isEqualByComparingTo("125000");
    }

    @Test
    @DisplayName("₦125,000 over 3 months: ₦41,667 + ₦834 = ₦42,501 a month")
    void monthlyExample() {
        List<Finance.InstalmentPart> plan = Finance.instalmentPlan(new BigDecimal("125000"), 3, TWO_PERCENT, true);

        assertThat(plan.get(0).base()).isEqualByComparingTo("41667");
        assertThat(plan.get(0).fee()).isEqualByComparingTo("834");
        assertThat(plan.get(0).total()).isEqualByComparingTo("42501");
        assertThat(plan.get(2).base()).isEqualByComparingTo("41666");
        assertThat(plan.get(2).fee()).isEqualByComparingTo("834");
        assertThat(sumOfBases(plan)).isEqualByComparingTo("125000");
    }

    /**
     * Any amount, either frequency: every instalment is its share rounded up
     * plus 2% of that share rounded up, and the schedule adds up to exactly
     * the loan, the interest and every fee.
     */
    @ParameterizedTest(name = "₦{0} at {1} over {2} months, {3}")
    @CsvSource({
            "100000, 0.25, 3, WEEKLY",
            "100000, 0.25, 3, MONTHLY",
            "900000, 0.31, 4, WEEKLY",
            "1500000, 0.53, 8, MONTHLY",
            "5000000, 0.78, 12, WEEKLY",
            "5000000, 0.78, 12, MONTHLY",
            "20000, 1.34, 24, WEEKLY"
    })
    @DisplayName("the schedule always adds up to the loan, the interest and every fee")
    void anyAmountAddsUp(BigDecimal principal, BigDecimal rate, int months, RepaymentFrequency frequency) {
        Instant disbursed = lagos("2026-10-23");
        Loan loan = loan(principal, rate, months, frequency, disbursed);

        List<Installment> schedule = loan.schedule(disbursed);

        BigDecimal loanAndInterest = principal.add(principal.multiply(rate)).setScale(0, RoundingMode.CEILING);
        assertThat(loan.loanAndInterest()).isEqualByComparingTo(loanAndInterest);
        assertThat(schedule).hasSize(loan.installmentCount());

        BigDecimal share = loanAndInterest.divide(BigDecimal.valueOf(schedule.size()), 0, RoundingMode.CEILING);
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        for (int i = 0; i < schedule.size(); i++) {
            Installment row = schedule.get(i);
            if (i < schedule.size() - 1) {
                assertThat(row.base()).isEqualByComparingTo(share);
            }
            // Whole naira, and the fee is 2% of the share, rounded up.
            assertThat(row.amount().stripTrailingZeros().scale()).isLessThanOrEqualTo(0);
            assertThat(row.fee()).isEqualByComparingTo(row.base().multiply(TWO_PERCENT).setScale(0, RoundingMode.CEILING));
            total = total.add(row.amount());
            fees = fees.add(row.fee());
        }
        assertThat(fees).isEqualByComparingTo(loan.repaymentFees());
        assertThat(total).isEqualByComparingTo(loan.totalRepayable());
        assertThat(loan.totalRepayable()).isEqualByComparingTo(loanAndInterest.add(fees));

        // Weekly instalments seven days apart; the loan is due with the last.
        if (frequency == RepaymentFrequency.WEEKLY) {
            assertThat(Duration.between(schedule.get(0).dueDate(), schedule.get(1).dueDate()).toDays()).isEqualTo(7);
        }
        assertThat(loan.getDueDate()).isEqualTo(schedule.get(schedule.size() - 1).dueDate());
    }

    @Test
    @DisplayName("a loan priced before the fee keeps its schedule: to the kobo, no fee")
    void olderLoansUnchanged() {
        Loan loan = new Loan();
        loan.setPrincipal(new BigDecimal("150000.00"));
        loan.setTenureMonths(3);
        loan.setRepaymentFrequency(RepaymentFrequency.MONTHLY);
        loan.setFlatRate(new BigDecimal("0.33"));
        loan.setProcessingFee(new BigDecimal("5000.00"));
        loan.setDisbursedAt(lagos("2026-01-31"));
        loan.setAmountRepaid(Money.zero());
        loan.setRebateGranted(Money.zero());

        List<Installment> schedule = loan.schedule(lagos("2026-01-31"));

        assertThat(schedule).hasSize(3);
        assertThat(schedule.get(0).amount()).isEqualByComparingTo("66500.00");
        assertThat(schedule.get(0).fee()).isEqualByComparingTo("0");
        assertThat(loan.totalRepayable()).isEqualByComparingTo("199500.00");
    }

    private static Loan loan(
            BigDecimal principal, BigDecimal rate, int months, RepaymentFrequency frequency, Instant disbursed) {
        Loan loan = new Loan();
        loan.setPrincipal(Money.of(principal));
        loan.setTenureMonths(months);
        loan.setRepaymentFrequency(frequency);
        loan.setFlatRate(rate);
        loan.setProcessingFee(Money.zero());
        loan.setDisbursedAt(disbursed);
        loan.setAmountRepaid(Money.zero());
        loan.setRebateGranted(Money.zero());
        loan.fixRepaymentTerms(disbursed, TWO_PERCENT);
        return loan;
    }

    private static BigDecimal sumOfBases(List<Finance.InstalmentPart> plan) {
        return plan.stream().map(Finance.InstalmentPart::base).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Instant lagos(String day) {
        return java.time.LocalDate.parse(day).atTime(10, 0).atZone(java.time.ZoneId.of("Africa/Lagos")).toInstant();
    }
}
