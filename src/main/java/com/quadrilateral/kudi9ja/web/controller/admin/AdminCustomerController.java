package com.quadrilateral.kudi9ja.web.controller.admin;

import com.quadrilateral.kudi9ja.common.api.PageResponse;
import com.quadrilateral.kudi9ja.domain.admin.AdminCustomerService;
import com.quadrilateral.kudi9ja.domain.loan.LoanService;
import com.quadrilateral.kudi9ja.domain.loan.OfflineLoanService;
import com.quadrilateral.kudi9ja.domain.legal.SignatureService;
import com.quadrilateral.kudi9ja.web.dto.SignatureDtos;
import com.quadrilateral.kudi9ja.domain.payin.PayInService;
import com.quadrilateral.kudi9ja.domain.payout.WithdrawalService;
import com.quadrilateral.kudi9ja.domain.user.AccountStatus;
import com.quadrilateral.kudi9ja.domain.wallet.LedgerService;
import com.quadrilateral.kudi9ja.domain.wallet.TxFilter;
import com.quadrilateral.kudi9ja.web.dto.AdminDtos;
import com.quadrilateral.kudi9ja.web.dto.LoanDtos;
import com.quadrilateral.kudi9ja.web.dto.OfflineLoanDtos;
import com.quadrilateral.kudi9ja.web.dto.PayInDtos;
import com.quadrilateral.kudi9ja.web.dto.SavingsDtos;
import com.quadrilateral.kudi9ja.web.dto.WalletDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customers, as the panel sees them.
 *
 * <p>Reading is open to every admin; changing a customer's standing needs the
 * customer permission, which viewers do not hold. <b>Opening a record is itself
 * written to the audit log</b> — a customer record holds an address, a date of
 * birth and a payout account, and the Privacy Policy commits to being able to
 * say who looked at it.
 *
 * <p>There is deliberately no endpoint for editing a customer's identity. Name,
 * date of birth, BVN and NIN were checked against the issuing institutions; an
 * admin who could retype them could undo that check, and no amount of audit
 * logging makes that a good idea. They change through support, on evidence,
 * outside this panel.
 *
 * <p>There is also no endpoint here that moves money. Pay-ins, withdrawals and
 * loans each have their own controller and their own permission, so approving a
 * payment is never something that happens as a side effect of looking at
 * somebody's record.
 */
@RestController
@RequestMapping("/api/v1/admin/customers")
@Tag(name = "Admin — customers", description = "The customer list, full records, and standing")
public class AdminCustomerController {

    private final AdminCustomerService customers;
    private final PayInService payIns;
    private final WithdrawalService withdrawals;
    private final LoanService loans;
    private final OfflineLoanService offline;
    private final SignatureService signatures;

    public AdminCustomerController(
            AdminCustomerService customers,
            PayInService payIns,
            WithdrawalService withdrawals,
            LoanService loans,
            OfflineLoanService offline,
            SignatureService signatures) {
        this.customers = customers;
        this.payIns = payIns;
        this.withdrawals = withdrawals;
        this.loans = loans;
        this.offline = offline;
        this.signatures = signatures;
    }

    /** The signatures the customer gave when accepting their agreements. */
    @GetMapping("/{customerId}/signatures")
    @Operation(summary = "A customer's signed agreements, with signed links to the signatures")
    public List<SignatureDtos.SignatureRecord> signatures(@PathVariable UUID customerId) {
        customers.detail(customerId);
        return signatures.forUser(customerId).stream()
                .map(s -> SignatureDtos.SignatureRecord.forAdmin(s, signatures.signedUrl(s)))
                .toList();
    }

    /**
     * Everything recorded by hand about this customer: the loans entered from
     * the paper records, the documents attached to any of their loans, and the
     * repayments that came in outside the app.
     */
    @GetMapping("/{customerId}/loan-records")
    @Operation(summary = "Imported loans, attached documents and hand-recorded repayments for a customer")
    public OfflineLoanDtos.CustomerLoanRecords loanRecords(@PathVariable UUID customerId) {
        customers.detail(customerId);
        return offline.forCustomer(customerId);
    }

    /**
     * The customer list.
     *
     * <p>The search matches on what an admin actually has in front of them: a
     * name, an email, a phone number, or the customer reference off a bank
     * narration.
     */
    @GetMapping
    @Operation(summary = "Search customers by name, email, phone or customer reference")
    public PageResponse<AdminDtos.CustomerRow> list(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) AccountStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        return PageResponse.of(
                customers.search(query, status, PageRequest.of(page, Math.min(size, 100))),
                row -> row);
    }

    @GetMapping("/{customerId}")
    @Operation(summary = "A customer's full record. Logged as a data access.")
    public AdminDtos.CustomerDetail detail(@PathVariable UUID customerId) {
        return customers.detail(customerId);
    }

    @GetMapping("/{customerId}/transactions")
    @Operation(summary = "A customer's ledger")
    public PageResponse<WalletDtos.TransactionResponse> transactions(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "ALL") TxFilter filter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        return PageResponse.of(
                customers.transactions(customerId, filter, PageRequest.of(page, Math.min(size, 100))),
                WalletDtos.TransactionResponse::from);
    }

    @GetMapping("/{customerId}/payins")
    @Operation(summary = "A customer's pay-in claims")
    public PageResponse<PayInDtos.AdminClaimResponse> payins(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        return PageResponse.of(
                payIns.listForCustomer(customerId, PageRequest.of(page, Math.min(size, 100))),
                payIns::toAdminResponse);
    }

    @GetMapping("/{customerId}/withdrawals")
    @Operation(summary = "A customer's withdrawal requests")
    public PageResponse<WalletDtos.AdminWithdrawalResponse> withdrawals(
            @PathVariable UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {

        return PageResponse.of(
                withdrawals.listForCustomer(customerId, PageRequest.of(page, Math.min(size, 100))),
                withdrawals::toAdminResponse);
    }

    /**
     * Every payment reference this customer has copied, newest first.
     *
     * <p>What an admin compares a bank narration against. A customer copies a
     * reference, transfers with it, and uploads the receipt — this is the list
     * that says we issued exactly that reference, to exactly this person, at
     * exactly that time.
     *
     * <p>Also shows which have been claimed, so a reference that was taken away
     * and never used stands out — and a narration matching nothing on this list
     * is worth a second look before anything is credited.
     */
    @GetMapping("/{customerId}/references")
    @Operation(summary = "Payment references this customer copied, to match against a receipt")
    public List<PayInDtos.IssuedReferenceResponse> references(@PathVariable UUID customerId) {
        customers.detail(customerId);
        return payIns.copiedReferences(customerId).stream()
                .map(PayInDtos.IssuedReferenceResponse::from)
                .toList();
    }

    @GetMapping("/{customerId}/plans")
    @Operation(summary = "A customer's savings plans")
    public List<SavingsDtos.PlanResponse> plans(@PathVariable UUID customerId) {
        Instant now = Instant.now();
        return customers.plans(customerId).stream()
                .map(plan -> SavingsDtos.PlanResponse.from(plan, now))
                .toList();
    }

    @GetMapping("/{customerId}/loans")
    @Operation(summary = "A customer's loans")
    public List<LoanDtos.LoanResponse> loans(@PathVariable UUID customerId) {
        return customers.loans(customerId).stream().map(loans::toResponse).toList();
    }

    /**
     * Flags a customer for review.
     *
     * <p>A flag records a concern and nothing else. The customer keeps
     * transacting and their app looks exactly as it did — it is the step before
     * a freeze rather than a quiet version of one.
     */
    @PostMapping("/{customerId}/flag")
    @Operation(summary = "Flag a customer for review. Does not stop them transacting.")
    public AdminDtos.CustomerDetail flag(
            @PathVariable UUID customerId,
            @Valid @RequestBody AdminDtos.FlagCustomerRequest request) {

        customers.flag(customerId, request.reason());
        return customers.detail(customerId);
    }

    /**
     * Freezes or releases an account.
     *
     * <p>A freeze stops money moving, and the customer is <b>told, with the
     * reason</b>. Finding a frozen wallet and no explanation is the worst
     * version of this, and the Terms promise better.
     */
    @PostMapping("/{customerId}/status")
    @Operation(summary = "Freeze or release a customer. The reason is mandatory and is sent to them.")
    public AdminDtos.CustomerDetail setStatus(
            @PathVariable UUID customerId,
            @Valid @RequestBody AdminDtos.SetCustomerStatusRequest request) {

        customers.setStatus(customerId, request.status(), request.reason());
        return customers.detail(customerId);
    }

    /**
     * Whether a customer's balance still matches their ledger.
     *
     * <p>Support's first question when somebody says their balance is wrong.
     * The balance must be reconstructible from the ledger, so a divergence here
     * is a defect to be chased rather than a number to be corrected by hand —
     * and there is deliberately no endpoint for correcting it by hand.
     */
    @GetMapping("/{customerId}/reconciliation")
    @Operation(summary = "Check a customer's balance against their ledger")
    public LedgerService.Reconciliation reconcile(@PathVariable UUID customerId) {
        return customers.reconcile(customerId);
    }
}
