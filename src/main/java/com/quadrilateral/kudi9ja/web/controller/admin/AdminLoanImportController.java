package com.quadrilateral.kudi9ja.web.controller.admin;

import com.quadrilateral.kudi9ja.domain.loan.LoanImportService;
import com.quadrilateral.kudi9ja.web.dto.LoanImportDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The loans written on paper before the app existed, entered by hand.
 *
 * <p>Each is entered against the customer's BVN and attaches itself to their
 * account the moment they sign up — or at once, if they already have. An
 * unclaimed entry can be removed; a claimed one is a loan on somebody's
 * account and is closed the way any loan is.
 */
@RestController
@RequestMapping("/api/v1/admin/loans/imports")
@Tag(name = "Admin — lending", description = "The loan book, reminders and write-offs")
public class AdminLoanImportController {

    private final LoanImportService imports;

    public AdminLoanImportController(LoanImportService imports) {
        this.imports = imports;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Enter a loan from the paper records. It reaches the customer's account "
            + "when their BVN is verified at signup, or now if they already have one.")
    public LoanImportDtos.ImportedLoanResponse importLoan(
            @Valid @RequestBody LoanImportDtos.ImportLoanRequest request) {
        return LoanImportDtos.ImportedLoanResponse.from(imports.importLoan(request));
    }

    @GetMapping
    @Operation(summary = "Entered loans: all, only those still waiting, or only those claimed")
    public List<LoanImportDtos.ImportedLoanResponse> list(
            @RequestParam(required = false) Boolean claimed) {
        return imports.list(claimed).stream()
                .map(LoanImportDtos.ImportedLoanResponse::from)
                .toList();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove an entry that nobody has claimed yet")
    public Map<String, Boolean> delete(@PathVariable UUID id) {
        imports.delete(id);
        return Map.of("removed", true);
    }
}
