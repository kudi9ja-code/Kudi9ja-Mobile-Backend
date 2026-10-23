package com.quadrilateral.kudi9ja.domain.loan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * On start-up, puts any loan entered by hand that is still waiting for its
 * borrower onto a real account, opening one where needed. Loans entered before
 * accounts were opened for borrowers straight away are brought into line once,
 * the first time this version runs; afterwards there is nothing to do.
 */
@Component
@Order(100)
public class WaitingLoanAdopter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WaitingLoanAdopter.class);

    private final LoanImportService imports;

    public WaitingLoanAdopter(LoanImportService imports) {
        this.imports = imports;
    }

    @Override
    public void run(ApplicationArguments args) {
        int placed = imports.adoptWaiting();
        if (placed > 0) {
            log.info("Put {} waiting loan(s) entered by hand onto customer accounts", placed);
        }
    }
}
