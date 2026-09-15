package com.quadrilateral.kudi9ja.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.quadrilateral.kudi9ja.domain.savings.AutoFrequency;
import com.quadrilateral.kudi9ja.domain.settings.PlatformSettings;
import com.quadrilateral.kudi9ja.domain.settings.SettingsDefaults;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The money formulas, checked against the figures the product publishes.
 *
 * <p>These are not tests of the implementation so much as of the promises. Each
 * one pins a number the app shows a customer, or an invariant the rate card has
 * to satisfy for the pricing to make sense at all — the sort of thing that
 * would still "work" if it broke, and would simply be quietly wrong.
 */
class FinanceTest {

    private final PlatformSettings settings = SettingsDefaults.first();

    // ── Fixed Savings: priced by the day ───────────────────────────────────

    @Nested
    @DisplayName("Fixed Savings is priced by the day, never rounded to months")
    class FixedSavings {

        /**
         * The published table, on ₦100,000 at 17% a year over a 365-day year.
         *
         * <p>171 days is in here on purpose. It is not a whole number of months
         * and never rounds to one — a customer who asks for 171 days is priced
         * for 171 days.
         */
        @ParameterizedTest(name = "{0} days on ₦100,000 returns ₦{1}")
        @CsvSource({
                "30,   1397.26",
                "60,   2794.52",
                "90,   4191.78",
                "171,  7964.38",
                "365, 17000.00",
                "730, 34000.00",
                "1825, 85000.00"
        })
        void matchesThePublishedTable(int days, BigDecimal expected) {
            BigDecimal interest = Finance.savingsInterest(
                    settings, new BigDecimal("100000"), days);

            assertThat(interest).isEqualByComparingTo(expected);
        }

        @Test
        @DisplayName("a full year returns exactly the annual rate")
        void aYearReturnsTheAnnualRate() {
            assertThat(Finance.savingsInterest(settings, new BigDecimal("100000"), 365))
                    .isEqualByComparingTo("17000.00");
        }

        /**
         * Interest is flat and never compounds. Two years is exactly twice one
         * year — not 1.17² − 1.
         */
        @Test
        @DisplayName("interest is flat: two years is exactly twice one year")
        void neverCompounds() {
            BigDecimal oneYear = Finance.savingsInterest(settings, new BigDecimal("100000"), 365);
            BigDecimal twoYears = Finance.savingsInterest(settings, new BigDecimal("100000"), 730);

            assertThat(twoYears).isEqualByComparingTo(oneYear.multiply(BigDecimal.valueOf(2)));
            // Compounded, two years would be ₦36,890. It must not be.
            assertThat(twoYears).isEqualByComparingTo("34000.00");
        }

        /**
         * Ten times the principal earns ten times the return, to within the
         * kobo that rounding can move.
         *
         * <p>Not to the kobo exactly, and it should not be: ₦100,000 over 90
         * days is ₦4,191.7808, rounded to ₦4,191.78, while ₦1,000,000 is
         * ₦41,917.808, rounded to ₦41,917.81. Each is correctly rounded at its
         * own size, and rounding is not linear. Insisting on exact linearity
         * here would mean rounding the small plan wrongly to make the
         * arithmetic tidy.
         */
        @Test
        @DisplayName("the return scales with the principal, to within rounding")
        void scalesWithPrincipal() {
            BigDecimal onHundredThousand =
                    Finance.savingsInterest(settings, new BigDecimal("100000"), 90);
            BigDecimal onOneMillion =
                    Finance.savingsInterest(settings, new BigDecimal("1000000"), 90);

            assertThat(onOneMillion)
                    .isCloseTo(
                            onHundredThousand.multiply(BigDecimal.TEN),
                            org.assertj.core.data.Offset.offset(new BigDecimal("0.10")));
        }
    }

    // ── Target Savings: a bonus on what was actually saved ─────────────────

    @Nested
    @DisplayName("Target Savings pays a bonus by term band")
    class TargetSavings {

        @ParameterizedTest(name = "{0} months earns {1}")
        @CsvSource({
                "3,  0.025",
                "5,  0.025",
                "6,  0.05",
                "11, 0.05",
                "12, 0.10",
                "24, 0.10"
        })
        void bonusRatesFollowTheBands(int months, BigDecimal expectedRate) {
            assertThat(settings.targetRateFor(months)).isEqualByComparingTo(expectedRate);
        }

        @Test
        @DisplayName("a target month is thirty days")
        void aTargetMonthIsThirtyDays() {
            assertThat(Finance.targetDays(settings, 6)).isEqualTo(180);
        }

        @Test
        @DisplayName("the number of runs follows the frequency")
        void runsFollowFrequency() {
            assertThat(Finance.targetRuns(settings, AutoFrequency.DAILY, 6)).isEqualTo(180);
            assertThat(Finance.targetRuns(settings, AutoFrequency.WEEKLY, 6)).isEqualTo(180 / 7);
            assertThat(Finance.targetRuns(settings, AutoFrequency.MONTHLY, 6)).isEqualTo(6);
        }

        /**
         * The bonus is paid on what was <b>actually</b> saved, not on the goal.
         * A missed auto-save is skipped rather than failed, so the two figures
         * routinely differ and paying on the goal would be paying for money
         * that never arrived.
         */
        @Test
        @DisplayName("the bonus is computed on what was saved, not on the goal")
        void bonusIsOnWhatWasSaved() {
            BigDecimal saved = new BigDecimal("450000");
            BigDecimal bonus = Finance.targetBonus(saved, new BigDecimal("0.10"));

            assertThat(bonus).isEqualByComparingTo("45000.00");
        }
    }

    // ── Lending: flat interest, priced per tenure ──────────────────────────

    @Nested
    @DisplayName("The loan rate card holds its two invariants")
    class LoanRateCard {

        /**
         * <b>Nobody is better off borrowing for longer than they need.</b>
         *
         * <p>If the total ever fell as the tenure rose, a customer who needed
         * three months would be right to take six, which is the opposite of
         * what the pricing intends.
         */
        @Test
        @DisplayName("the total always rises with the tenure")
        void totalRisesWithTenure() {
            BigDecimal principal = new BigDecimal("1000000");
            BigDecimal previous = BigDecimal.ZERO;

            for (int months = 1; months <= settings.getMaxLoanTenureMonths(); months++) {
                BigDecimal total = Finance.loanTotal(settings, principal, months);

                assertThat(total)
                        .as("total at %d months must exceed the total at %d", months, months - 1)
                        .isGreaterThan(previous);
                previous = total;
            }
        }

        /**
         * <b>The cost per month always falls.</b>
         *
         * <p>12.50% a month at one month, 8.33% at three, 6.50% at twelve,
         * 5.58% at twenty-four. Borrowing for longer costs more in total and
         * less per month, which is what makes a longer tenure a real choice
         * rather than a trap.
         */
        @Test
        @DisplayName("the cost per month always falls")
        void costPerMonthFalls() {
            BigDecimal previous = null;

            for (int months = 1; months <= settings.getMaxLoanTenureMonths(); months++) {
                BigDecimal perMonth = settings.loanRateFor(months)
                        .divide(BigDecimal.valueOf(months), 6, RoundingMode.HALF_UP);

                if (previous != null) {
                    assertThat(perMonth)
                            .as("monthly cost at %d months must be below the cost at %d",
                                    months, months - 1)
                            .isLessThan(previous);
                }
                previous = perMonth;
            }
        }

        @Test
        @DisplayName("every selectable tenure has a rate")
        void everyTenureIsPriced() {
            for (int months = 1; months <= settings.getMaxLoanTenureMonths(); months++) {
                assertThat(settings.getLoanRates())
                        .as("tenure %d must be priced", months)
                        .containsKey(months);
            }
        }

        @ParameterizedTest(name = "{0} months is priced at {1}")
        @CsvSource({"1, 0.125", "2, 0.17", "3, 0.25", "12, 0.78", "24, 1.34"})
        void matchesThePublishedCard(int months, BigDecimal expected) {
            assertThat(settings.loanRateFor(months)).isEqualByComparingTo(expected);
        }

        @Test
        @DisplayName("interest is flat: it does not grow with time elapsed")
        void interestIsFlat() {
            BigDecimal principal = new BigDecimal("500000");

            // Three months at 25% is ₦125,000 whether it is repaid on day one
            // or on the last day. Nothing accrues.
            assertThat(Finance.loanInterest(settings, principal, 3))
                    .isEqualByComparingTo("125000.00");
            assertThat(Finance.loanTotal(settings, principal, 3))
                    .isEqualByComparingTo("625000.00");
        }
    }

    // ── The management fee ─────────────────────────────────────────────────

    @Nested
    @DisplayName("The management fee is continuous at the threshold")
    class ManagementFee {

        /**
         * The two rules meet exactly at ₦500,000, because 1% of ₦500,000 <i>is</i>
         * ₦5,000. A discontinuity here would mean borrowing one naira more cost
         * a step change in fee, and customers would learn to borrow ₦499,999.
         */
        @Test
        @DisplayName("flat and percentage meet exactly at ₦500,000")
        void curveIsContinuousAtTheThreshold() {
            assertThat(Finance.processingFee(settings, new BigDecimal("500000")))
                    .isEqualByComparingTo("5000.00");
            assertThat(Finance.processingFee(settings, new BigDecimal("500001")))
                    .isEqualByComparingTo("5000.01");
        }

        @ParameterizedTest(name = "₦{0} attracts a fee of ₦{1}")
        @CsvSource({
                "50000,   5000.00",
                "200000,  5000.00",
                "500000,  5000.00",
                "1000000, 10000.00",
                "5000000, 50000.00"
        })
        void followsTheTwoRules(BigDecimal principal, BigDecimal expected) {
            assertThat(Finance.processingFee(settings, principal)).isEqualByComparingTo(expected);
        }

        /**
         * Above the threshold the fee is 1% of the <b>whole</b> principal, not
         * of the excess over ₦500,000.
         */
        @Test
        @DisplayName("above the threshold the fee is 1% of the whole principal")
        void percentageAppliesToTheWholePrincipal() {
            assertThat(Finance.processingFee(settings, new BigDecimal("1000000")))
                    .isEqualByComparingTo("10000.00")
                    // 1% of the excess would be ₦5,000. It is not that.
                    .isNotEqualByComparingTo("5000.00");
        }

        /**
         * The fee comes out of the disbursement and is never added to the debt.
         * Somebody borrowing ₦500,000 owes interest on ₦500,000 and receives
         * ₦495,000.
         */
        @Test
        @DisplayName("the fee is deducted from the disbursement, never added to the debt")
        void feeIsNettedFromDisbursement() {
            BigDecimal principal = new BigDecimal("500000");

            assertThat(Finance.netDisbursed(settings, principal))
                    .isEqualByComparingTo("495000.00");
            // The debt is priced on the gross principal, not the net.
            assertThat(Finance.loanTotal(settings, principal, 3))
                    .isEqualByComparingTo("625000.00");
        }
    }

    // ── Early settlement ───────────────────────────────────────────────────

    @Nested
    @DisplayName("Settling early earns a rebate, never a charge")
    class EarlySettlement {

        /**
         * ₦1,000,000 over 12 months at 78% carries ₦780,000 of interest.
         *
         * <p>Three whole months in, the month now running is not rebated —
         * only the eight that never started — so the rebate is
         * 780,000 × 8/12 × 0.5 = ₦260,000.
         *
         * <p>The disbursement date is pinned to exactly three calendar months
         * ago rather than to ninety days ago. Ninety days is two whole months
         * and change for most of the year, which would quietly test a different
         * case than the comment claimed.
         */
        @Test
        @DisplayName("half the interest on the months that never started comes back")
        void rebatesUnearnedInterest() {
            Instant now = Instant.now();
            Instant disbursed = now.atZone(java.time.ZoneOffset.UTC)
                    .minusMonths(3)
                    .toInstant();

            BigDecimal rebate = Finance.earlyPayoffRebate(
                    settings, new BigDecimal("780000"), new BigDecimal("1500000"), 12, disbursed, now);

            assertThat(rebate).isEqualByComparingTo("260000.00");
        }

        @Test
        @DisplayName("the rebate is capped at what is still owed")
        void neverExceedsTheOutstanding() {
            BigDecimal outstanding = new BigDecimal("50000");

            BigDecimal rebate = Finance.earlyPayoffRebate(
                    settings,
                    new BigDecimal("780000"),
                    outstanding,
                    12,
                    Instant.now().minus(30, ChronoUnit.DAYS),
                    Instant.now());

            assertThat(rebate).isLessThanOrEqualTo(outstanding);
        }

        /**
         * A rebate is never negative. There is no early-settlement charge in
         * this product, so the worst outcome of settling early is that it saves
         * nothing — never that it costs something.
         */
        @Test
        @DisplayName("settling in the final month costs nothing extra")
        void isNeverACharge() {
            BigDecimal rebate = Finance.earlyPayoffRebate(
                    settings,
                    new BigDecimal("125000"),
                    new BigDecimal("100000"),
                    3,
                    Instant.now().minus(85, ChronoUnit.DAYS),
                    Instant.now());

            assertThat(rebate).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }
    }
}
