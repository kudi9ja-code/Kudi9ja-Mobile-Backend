package com.quadrilateral.kudi9ja;

import static org.assertj.core.api.Assertions.assertThat;

import com.quadrilateral.kudi9ja.domain.loan.LateCharges;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LateChargesTest {

    // Due at noon Lagos time on 10 November.
    private static final Instant DUE = Instant.parse("2026-11-10T11:00:00Z");

    @Test
    void chargesPointEighteenPercentOfThePrincipalADay() {
        assertThat(LateCharges.daily(new BigDecimal("100000"))).isEqualByComparingTo("180");
        // Rounded up to the naira.
        assertThat(LateCharges.daily(new BigDecimal("55555"))).isEqualByComparingTo("101");
    }

    @Test
    void neverChargesDaysBeforeItWentLive() {
        LocalDate live = LocalDate.of(2026, 11, 23);
        // Overdue since the 11th, but the first day charged is the deploy day.
        assertThat(LateCharges.daysToCharge(DUE, null, live, live)).isEqualTo(1);
        assertThat(LateCharges.daysToCharge(DUE, null, live, live.plusDays(4))).isEqualTo(5);
    }

    @Test
    void startsTheDayAfterTheDueDateAndNeverChargesADayTwice() {
        LocalDate live = LocalDate.of(2026, 11, 1);
        assertThat(LateCharges.daysToCharge(DUE, null, live, LocalDate.of(2026, 11, 10))).isZero();
        assertThat(LateCharges.daysToCharge(DUE, null, live, LocalDate.of(2026, 11, 11))).isEqualTo(1);
        assertThat(LateCharges.daysToCharge(DUE, LocalDate.of(2026, 11, 11), live, LocalDate.of(2026, 11, 11))).isZero();
        assertThat(LateCharges.daysToCharge(DUE, LocalDate.of(2026, 11, 11), live, LocalDate.of(2026, 11, 14))).isEqualTo(3);
    }
}
