package com.quadrilateral.kudi9ja.domain.loan;

import com.quadrilateral.kudi9ja.common.util.Dates;
import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.domain.notification.NotificationService;
import com.quadrilateral.kudi9ja.domain.notification.NotifyKind;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds the daily late charge to every loan past its final due date.
 *
 * <p>0.18% of the original principal per day, from the day after the due date
 * until the loan is repaid in full, with no cap. Days before late charges went
 * live are never charged. Each loan remembers the last day charged, so running
 * this more than once a day, or after a missed run, charges each day exactly once.
 */
@Service
public class LateChargeService {

    private static final Logger log = LoggerFactory.getLogger(LateChargeService.class);

    private final LateChargePolicyRepository policy;
    private final LoanRepository loans;
    private final ImportedLoanRepository imports;
    private final NotificationService notifications;

    public LateChargeService(
            LateChargePolicyRepository policy,
            LoanRepository loans,
            ImportedLoanRepository imports,
            NotificationService notifications) {
        this.policy = policy;
        this.loans = loans;
        this.imports = imports;
        this.notifications = notifications;
    }

    /** The first day charged. Set to today the first time it is asked for: the day this went live. */
    @Transactional
    public LocalDate startsOn(Instant now) {
        LateChargePolicy row = policy.findById(LateChargePolicy.ID).orElseGet(() -> {
            LateChargePolicy p = new LateChargePolicy();
            p.setId(LateChargePolicy.ID);
            return p;
        });
        if (row.getStartsOn() == null) {
            row.setStartsOn(now.atZone(Dates.LAGOS).toLocalDate());
            policy.save(row);
            log.info("Late charges start on {}", row.getStartsOn());
        }
        return row.getStartsOn();
    }

    /** Charges every day not yet charged, up to and including today. Returns how many loans were charged. */
    @Transactional
    public int accrue(Instant now) {
        LocalDate startsOn = startsOn(now);
        LocalDate today = now.atZone(Dates.LAGOS).toLocalDate();
        int charged = 0;

        for (Loan loan : loans.findAllOpen()) {
            if (!Money.isPositive(loan.outstanding())) {
                continue;
            }
            long days = LateCharges.daysToCharge(loan.getDueDate(), loan.getLateChargedThrough(), startsOn, today);
            if (days <= 0) {
                continue;
            }
            boolean first = !Money.isPositive(loan.lateCharges());
            BigDecimal added = loan.addLateCharges(days, today);
            if (loan.getStatus() == LoanStatus.ACTIVE) {
                loan.setStatus(LoanStatus.OVERDUE);
            }
            loans.save(loan);
            charged++;

            if (first) {
                notifications.push(
                        loan.getUserId(),
                        NotifyKind.REPAYMENT_DUE,
                        "A late charge has been added to your loan",
                        "Your " + loan.getPurpose() + " loan is past its final due date. A late charge of "
                                + LateCharges.RATE_LABEL + " of the amount you borrowed ("
                                + Money.naira(LateCharges.daily(loan.getPrincipal()))
                                + ") is added every day until it is repaid in full. You now owe "
                                + Money.naira(loan.outstanding()) + ".",
                        added);
            }
        }

        for (ImportedLoan row : imports.findByClaimedAtIsNullOrderByImportedAtDesc()) {
            if (row.getDisbursedAt() == null || !Money.isPositive(row.outstanding())) {
                continue;
            }
            long days = LateCharges.daysToCharge(row.dueDate(), row.getLateChargedThrough(), startsOn, today);
            if (days <= 0) {
                continue;
            }
            row.addLateCharges(days, today);
            imports.save(row);
            charged++;
        }

        if (charged > 0) {
            log.info("Late charges added to {} loan(s) through {}", charged, today);
        }
        return charged;
    }
}
