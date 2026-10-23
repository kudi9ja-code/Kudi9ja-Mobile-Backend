package com.quadrilateral.kudi9ja.web.controller.admin;

import com.quadrilateral.kudi9ja.common.api.PageResponse;
import com.quadrilateral.kudi9ja.domain.admin.AdminLoanService;
import com.quadrilateral.kudi9ja.domain.loan.LoanService;
import com.quadrilateral.kudi9ja.domain.loan.LoanStatus;
import com.quadrilateral.kudi9ja.domain.loan.OfflineLoanService;
import com.quadrilateral.kudi9ja.web.dto.AdminDtos;
import com.quadrilateral.kudi9ja.web.dto.LoanDtos;
import com.quadrilateral.kudi9ja.web.dto.OfflineLoanDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The loan book, and the two things an admin may do to a loan.
 *
 * <p>Neither of them moves money. Repayment is the borrower's action, from
 * their wallet or by a bank transfer claimed as a loan repayment. What is here
 * is asking, and giving up on asking — and, for money that came back outside
 * the app, recording that it did, with the documents that go with the loan.
 *
 * <p>Both are governed by written commitments rather than by operational
 * preference. A reminder is refused outside <b>8am–8pm Lagos time</b> and is
 * logged, because the Privacy Policy commits to both — and it goes to the
 * borrower's own notification feed and nowhere else, because the same document
 * promises we never contact their phone contacts, their family or their
 * employer. A write-off closes a loan without payment and carries a mandatory
 * reason, because it is the one admin action that forgives money.
 *
 * <p>What is deliberately absent: any endpoint that increases a balance. There
 * is no late fee and no penalty interest in this product, and the Lending
 * Agreement commits to that in the words <i>"even in default, the amount you
 * owe does not increase"</i>.
 */
@RestController
@RequestMapping("/api/v1/admin/loans")
@Tag(name = "Admin — lending", description = "The loan book, reminders and write-offs")
public class AdminLoanController {

    private final AdminLoanService adminLoans;
    private final LoanService loans;
    private final OfflineLoanService offline;

    public AdminLoanController(AdminLoanService adminLoans, LoanService loans, OfflineLoanService offline) {
        this.adminLoans = adminLoans;
        this.loans = loans;
        this.offline = offline;
    }

    @GetMapping
    @Operation(summary = "The lending book, or one status of it")
    public PageResponse<AdminDtos.AdminLoanRow> queue(
            @RequestParam(required = false) LoanStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        return PageResponse.of(
                adminLoans.queue(status, PageRequest.of(page, Math.min(size, 100))),
                row -> row);
    }

    /**
     * Reminds a borrower about what is due.
     *
     * <p>Refused outside the contact window rather than trusting an admin to
     * check the clock. A promise a server enforces is worth more than one a
     * person remembers.
     */
    @PostMapping("/{loanId}/remind")
    @Operation(summary = "Send a repayment reminder. Only between 8am and 8pm Lagos time.")
    public LoanDtos.LoanResponse remind(
            @PathVariable UUID loanId,
            @RequestBody(required = false) AdminDtos.RemindRequest request) {

        return loans.toResponse(adminLoans.remind(loanId, request == null ? null : request.note()));
    }

    /**
     * Writes a loan off.
     *
     * <p>The row is closed rather than deleted and what was repaid is left
     * standing: a write-off says the company will not chase the debt, not that
     * it was never lent. The borrower is told, because a debt that is no longer
     * being pursued is something they are entitled to know rather than guess.
     */
    @PostMapping("/{loanId}/writeoff")
    @Operation(summary = "Write the loan off. The reason is required.")
    public LoanDtos.LoanResponse writeOff(
            @PathVariable UUID loanId,
            @Valid @RequestBody AdminDtos.WriteOffRequest request) {

        return loans.toResponse(adminLoans.writeOff(loanId, request.reason()));
    }

    /**
     * Records a repayment that arrived outside the app — cash, or a transfer
     * straight to the company account. It is applied to the loan as any
     * repayment is, but never touches the wallet: the money did not pass
     * through it. The borrower is notified, and the admin's name is on the
     * audit log beside the reference.
     */
    @PostMapping("/{loanId}/repayments")
    @Operation(summary = "Record a repayment that arrived outside the app")
    public OfflineLoanDtos.LoanRecords recordRepayment(
            @PathVariable UUID loanId,
            @Valid @RequestBody OfflineLoanDtos.OfflineRepaymentRequest request) {

        return offline.recordOnLoan(loanId, request);
    }

    @GetMapping("/{loanId}/records")
    @Operation(summary = "Documents and hand-recorded repayments on a loan")
    public OfflineLoanDtos.LoanRecords records(@PathVariable UUID loanId) {
        return offline.records(loanId);
    }

    @PostMapping(value = "/{loanId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Attach a document to a loan on an account")
    public OfflineLoanDtos.OfflineDocument attach(
            @PathVariable UUID loanId,
            @RequestParam(value = "label", required = false) String label,
            @RequestParam("file") MultipartFile file) {

        return offline.attachToLoan(loanId, label, AdminLoanImportController.upload(file));
    }
}
