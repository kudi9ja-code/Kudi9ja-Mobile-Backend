package com.quadrilateral.kudi9ja.web.controller.admin;

import com.quadrilateral.kudi9ja.domain.admin.RepaymentHistoryService;
import com.quadrilateral.kudi9ja.web.dto.RepaymentDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Every repayment customers have made. Read-only, open to every admin. */
@RestController
@RequestMapping("/api/v1/admin/repayments")
@Tag(name = "Admin — repayments", description = "Repayment history across all loans")
public class AdminRepaymentController {

    private final RepaymentHistoryService repayments;

    public AdminRepaymentController(RepaymentHistoryService repayments) {
        this.repayments = repayments;
    }

    @GetMapping
    @Operation(summary = "Repayments, newest first, with totals for everything the filters match")
    public RepaymentDtos.History history(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) RepaymentDtos.Source source,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        return repayments.history(query, source, from, to, page, size);
    }
}
