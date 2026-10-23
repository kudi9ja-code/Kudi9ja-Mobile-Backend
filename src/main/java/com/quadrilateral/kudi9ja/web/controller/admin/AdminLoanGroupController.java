package com.quadrilateral.kudi9ja.web.controller.admin;

import com.quadrilateral.kudi9ja.domain.group.LoanGroupService;
import com.quadrilateral.kudi9ja.web.dto.LoanGroupDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Group loans. A new borrower is entered into a group through the paper-loan
 * form, {@code POST /api/v1/admin/loans/imports} with a {@code groupId}.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin — loan groups", description = "Numbered, named groups of borrowers, each run by one loan officer")
public class AdminLoanGroupController {

    private final LoanGroupService groups;

    public AdminLoanGroupController(LoanGroupService groups) {
        this.groups = groups;
    }

    @GetMapping("/groups")
    @Operation(summary = "Every group, by number, with its totals")
    public List<LoanGroupDtos.LoanGroupView> list() {
        return groups.list();
    }

    @GetMapping("/groups/labels")
    @Operation(summary = "Who is in which group, for labelling the customers list")
    public List<LoanGroupDtos.GroupLabel> labels() {
        return groups.labels();
    }

    @GetMapping("/groups/next-number")
    @Operation(summary = "The next free group number")
    public Map<String, Integer> nextNumber() {
        return Map.of("number", groups.nextNumber());
    }

    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open a group and name its loan officer")
    public LoanGroupDtos.LoanGroupDetail create(@Valid @RequestBody LoanGroupDtos.CreateLoanGroup request) {
        return groups.create(request);
    }

    @GetMapping("/groups/{groupId}")
    @Operation(summary = "A group and every borrower in it")
    public LoanGroupDtos.LoanGroupDetail detail(@PathVariable UUID groupId) {
        return groups.detail(groupId);
    }

    @PatchMapping("/groups/{groupId}")
    @Operation(summary = "Rename, describe, close, or hand to another officer. The group's officer, an owner or an administrator.")
    public LoanGroupDtos.LoanGroupDetail update(@PathVariable UUID groupId, @Valid @RequestBody LoanGroupDtos.UpdateLoanGroup request) {
        return groups.update(groupId, request);
    }

    @PostMapping("/groups/{groupId}/customers")
    @Operation(summary = "Add a customer who already has an account, by account number")
    public LoanGroupDtos.LoanGroupDetail addCustomer(@PathVariable UUID groupId, @Valid @RequestBody LoanGroupDtos.AddGroupCustomer request) {
        return groups.addCustomer(groupId, request.customerRef());
    }

    @DeleteMapping("/groups/{groupId}/members/{memberId}")
    @Operation(summary = "Take a borrower out of the group. Their loan is unchanged.")
    public LoanGroupDtos.LoanGroupDetail remove(@PathVariable UUID groupId, @PathVariable UUID memberId) {
        return groups.removeMember(groupId, memberId);
    }

    @GetMapping("/customers/{customerId}/groups")
    @Operation(summary = "The groups a customer belongs to")
    public List<LoanGroupDtos.LoanGroupView> forCustomer(@PathVariable UUID customerId) {
        return groups.forCustomer(customerId);
    }
}
