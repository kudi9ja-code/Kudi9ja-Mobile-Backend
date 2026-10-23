package com.quadrilateral.kudi9ja.web.controller.admin;

import com.quadrilateral.kudi9ja.domain.officer.LoanOfficerService;
import com.quadrilateral.kudi9ja.web.dto.LoanOfficerDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Loan officers: at least one on every borrower, as many as are needed, and
 * one of them leading. Borrowers who have not signed up are reached through
 * their paper loan.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin — loan officers", description = "Who on the team looks after which borrower")
public class AdminLoanOfficerController {

    private final LoanOfficerService officers;

    public AdminLoanOfficerController(LoanOfficerService officers) {
        this.officers = officers;
    }

    @GetMapping("/officers")
    @Operation(summary = "Everyone who can be made a loan officer, with how many borrowers each has")
    public List<LoanOfficerDtos.OfficerOption> options() {
        return officers.options();
    }

    @GetMapping("/officers/{officerId}/borrowers")
    @Operation(summary = "One officer's borrowers")
    public List<LoanOfficerDtos.OfficerBorrower> book(@PathVariable UUID officerId) {
        return officers.book(officerId);
    }

    @GetMapping("/customers/{customerId}/officers")
    @Operation(summary = "A customer's loan officers, lead first")
    public List<LoanOfficerDtos.AssignedOfficer> forCustomer(@PathVariable UUID customerId) {
        return officers.forCustomer(customerId);
    }

    @PostMapping("/customers/{customerId}/officers")
    @Operation(summary = "Add loan officers to a customer")
    public List<LoanOfficerDtos.AssignedOfficer> assignToCustomer(
            @PathVariable UUID customerId, @Valid @RequestBody LoanOfficerDtos.AssignOfficers request) {
        return officers.assignToCustomer(customerId, request.officerIds(), request.lead());
    }

    @GetMapping("/loans/imports/{importId}/officers")
    @Operation(summary = "A paper loan's officers, before its borrower signs up")
    public List<LoanOfficerDtos.AssignedOfficer> forImport(@PathVariable UUID importId) {
        return officers.forImport(importId);
    }

    @PostMapping("/loans/imports/{importId}/officers")
    @Operation(summary = "Add loan officers to a paper loan's borrower")
    public List<LoanOfficerDtos.AssignedOfficer> assignToImport(
            @PathVariable UUID importId, @Valid @RequestBody LoanOfficerDtos.AssignOfficers request) {
        return officers.assignToImport(importId, request.officerIds(), request.lead());
    }

    @PostMapping("/officers/assignments/{assignmentId}/lead")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Make this officer the lead on their borrower")
    public void makeLead(@PathVariable UUID assignmentId) {
        officers.makeLead(assignmentId);
    }

    @DeleteMapping("/officers/assignments/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Take an officer off a borrower. Refused for the last one.")
    public void remove(@PathVariable UUID assignmentId) {
        officers.remove(assignmentId);
    }
}
