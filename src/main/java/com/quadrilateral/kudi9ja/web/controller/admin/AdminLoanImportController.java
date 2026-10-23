package com.quadrilateral.kudi9ja.web.controller.admin;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.domain.loan.LoanImportService;
import com.quadrilateral.kudi9ja.domain.loan.OfflineLoanService;
import com.quadrilateral.kudi9ja.web.dto.LoanImportDtos;
import com.quadrilateral.kudi9ja.web.dto.OfflineLoanDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The loans written on paper before the app existed, entered by hand.
 *
 * <p>Each is entered against the customer's BVN and attaches itself to their
 * account the moment they sign up — or at once, if they already have. An
 * unclaimed entry can be removed; a claimed one is a loan on somebody's
 * account and is closed the way any loan is.
 *
 * <p>Each entry also carries the borrower's file: their details, guarantors,
 * the documents an admin attached, and the repayments that came in by hand.
 */
@RestController
@RequestMapping("/api/v1/admin/loans/imports")
@Tag(name = "Admin — lending", description = "The loan book, reminders and write-offs")
public class AdminLoanImportController {

    private final LoanImportService imports;
    private final OfflineLoanService offline;

    public AdminLoanImportController(LoanImportService imports, OfflineLoanService offline) {
        this.imports = imports;
        this.offline = offline;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Enter a loan from the paper records. It reaches the customer's account "
            + "when their BVN is verified at signup, or now if they already have one.")
    public LoanImportDtos.ImportedLoanResponse importLoan(
            @Valid @RequestBody LoanImportDtos.ImportLoanRequest request) {
        return imports.view(imports.importLoan(request));
    }

    @GetMapping
    @Operation(summary = "Entered loans: all, only those still waiting, or only those claimed")
    public List<LoanImportDtos.ImportedLoanResponse> list(
            @RequestParam(required = false) Boolean claimed) {
        return imports.list(claimed).stream()
                .map(imports::view)
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "One entry with its whole file: details, guarantors, documents and repayments")
    public OfflineLoanDtos.OfflineLoanFile file(@PathVariable UUID id) {
        return offline.file(id);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Correct the borrower's file. Identity fields only while the loan is still waiting.")
    public OfflineLoanDtos.OfflineLoanFile update(
            @PathVariable UUID id,
            @Valid @RequestBody OfflineLoanDtos.OfflineLoanUpdate request) {
        return offline.update(id, request);
    }

    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Attach a document to the borrower's file")
    public OfflineLoanDtos.OfflineDocument attach(
            @PathVariable UUID id,
            @RequestParam(value = "label", required = false) String label,
            @RequestParam("file") MultipartFile file) {
        return offline.attachToImport(id, label, upload(file));
    }

    @DeleteMapping("/documents/{documentId}")
    @Operation(summary = "Remove a document from a loan file")
    public Map<String, Boolean> removeDocument(@PathVariable UUID documentId) {
        offline.removeDocument(documentId);
        return Map.of("removed", true);
    }

    @PostMapping("/{id}/repayments")
    @Operation(summary = "Record a repayment that arrived outside the app. Never touches a wallet.")
    public OfflineLoanDtos.OfflineLoanFile recordRepayment(
            @PathVariable UUID id,
            @Valid @RequestBody OfflineLoanDtos.OfflineRepaymentRequest request) {
        return offline.recordOnImport(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove an entry that nobody has claimed yet")
    public Map<String, Boolean> delete(@PathVariable UUID id) {
        imports.delete(id);
        return Map.of("removed", true);
    }

    static OfflineLoanService.Upload upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.validation("Attach a file.");
        }
        try {
            return new OfflineLoanService.Upload(file.getOriginalFilename(), file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw ApiException.validation("That file could not be read. Try attaching it again.");
        }
    }
}
