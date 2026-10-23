package com.quadrilateral.kudi9ja.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.domain.loan.Installment;
import com.quadrilateral.kudi9ja.domain.loan.Loan;
import com.quadrilateral.kudi9ja.domain.loan.RepaymentFrequency;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Weekly and monthly repayment: the same total, split differently. */
@DisplayName("Repayment frequency")
class RepaymentFrequencyTest {

    private static final Instant DISBURSED = Instant.parse("2026-01-31T09:00:00Z");

    @Test
    @DisplayName("a tenure has as many weekly instalments as the months hold weeks")
    void weeksInATenure() {
        assertThat(RepaymentFrequency.WEEKLY.installments(1)).isEqualTo(4);
        assertThat(RepaymentFrequency.WEEKLY.installments(3)).isEqualTo(13);
        assertThat(RepaymentFrequency.WEEKLY.installments(6)).isEqualTo(26);
        assertThat(RepaymentFrequency.WEEKLY.installments(12)).isEqualTo(52);
        assertThat(RepaymentFrequency.WEEKLY.installments(24)).isEqualTo(104);
        assertThat(RepaymentFrequency.MONTHLY.installments(6)).isEqualTo(6);
    }

    @Test
    @DisplayName("a weekly schedule sums exactly to the total, one instalment every seven days")
    void weeklySchedule() {
        Loan loan = loan(RepaymentFrequency.WEEKLY, 6);

        List<Installment> schedule = loan.schedule(DISBURSED);

        assertThat(schedule).hasSize(26);
        assertThat(schedule.stream().map(Installment::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(loan.totalRepayable());
        for (int i = 0; i < schedule.size(); i++) {
            assertThat(Duration.between(DISBURSED, schedule.get(i).dueDate()).toDays()).isEqualTo(7L * (i + 1));
        }
    }

    @Test
    @DisplayName("monthly loans are unchanged: one instalment a calendar month")
    void monthlyUnchanged() {
        Loan loan = loan(RepaymentFrequency.MONTHLY, 3);

        List<Installment> schedule = loan.schedule(DISBURSED);

        assertThat(schedule).hasSize(3);
        // 31 January → 28 February: the day clamps to the month's end.
        assertThat(schedule.get(0).dueDate()).isEqualTo(Instant.parse("2026-02-28T09:00:00Z"));
        assertThat(loan.installmentAmount()).isEqualByComparingTo(
                Money.of(loan.totalRepayable().divide(BigDecimal.valueOf(3), 8, java.math.RoundingMode.HALF_UP)));
    }

    private static Loan loan(RepaymentFrequency frequency, int months) {
        Loan loan = new Loan();
        loan.setPrincipal(new BigDecimal("150000.00"));
        loan.setTenureMonths(months);
        loan.setRepaymentFrequency(frequency);
        loan.setFlatRate(new BigDecimal("0.33"));
        loan.setProcessingFee(new BigDecimal("5000.00"));
        loan.setDisbursedAt(DISBURSED);
        loan.setAmountRepaid(Money.zero());
        loan.setRebateGranted(Money.zero());
        return loan;
    }
}
