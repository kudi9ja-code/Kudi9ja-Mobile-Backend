package com.quadrilateral.kudi9ja.web.controller.admin;

import com.quadrilateral.kudi9ja.domain.admin.ExportService;
import com.quadrilateral.kudi9ja.web.dto.ExportDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports to download. The panel turns each into an Excel workbook or a PDF;
 * this hands it the rows, for owners and administrators only, and logs the
 * download.
 */
@RestController
@RequestMapping("/api/v1/admin/exports")
@Tag(name = "Admin — reports", description = "Customers, loans, repayments and the rest, for Excel or PDF")
public class AdminExportController {

    private final ExportService exports;

    public AdminExportController(ExportService exports) {
        this.exports = exports;
    }

    @GetMapping("/{report}")
    @Operation(summary = "One report: customers, loans, repayments, transactions, paystack, withdrawals or applications")
    public ExportDtos.Report export(
            @PathVariable String report,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return exports.export(report, from, to);
    }
}
