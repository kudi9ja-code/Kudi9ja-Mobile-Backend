package com.quadrilateral.kudi9ja.web.controller.admin;

import com.quadrilateral.kudi9ja.domain.credit.CreditCheck;
import com.quadrilateral.kudi9ja.domain.credit.CreditCheckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A customer's credit score from the bureaus, on their admin record. */
@RestController
@RequestMapping("/api/v1/admin/customers/{customerId}/credit-checks")
@Tag(name = "Admin — credit checks", description = "Credit bureau score by BVN, via Dojah")
public class AdminCreditCheckController {

    private final CreditCheckService credit;

    public AdminCreditCheckController(CreditCheckService credit) {
        this.credit = credit;
    }

    @GetMapping
    @Operation(summary = "Credit checks already made for this customer, newest first")
    public History history(@PathVariable UUID customerId) {
        return new History(
                credit.available(),
                credit.history(customerId).stream().map(Check::from).toList());
    }

    @PostMapping
    @Operation(summary = "Fetch a fresh credit score. Charged to the Dojah wallet; logged as a data access.")
    public Check check(@PathVariable UUID customerId) {
        return Check.from(credit.check(customerId));
    }

    /** @param available whether this server can run a check (Dojah keys set) */
    public record History(boolean available, List<Check> checks) {
    }

    public record Check(
            UUID id,
            Instant checkedAt,
            String checkedBy,
            boolean found,
            Integer score,
            String rating,
            String reasons,
            String hasLoans,
            Integer delinquentFacilities,
            String lastReported) {

        static Check from(CreditCheck c) {
            return new Check(c.getId(), c.getCheckedAt(), c.getCheckedBy(), c.isFound(), c.getScore(),
                    c.getRating(), c.getReasons(), c.getHasLoans(), c.getDelinquentFacilities(),
                    c.getLastReported());
        }
    }
}
