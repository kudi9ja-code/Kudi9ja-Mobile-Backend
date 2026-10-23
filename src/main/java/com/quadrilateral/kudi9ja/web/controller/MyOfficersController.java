package com.quadrilateral.kudi9ja.web.controller;

import com.quadrilateral.kudi9ja.domain.officer.LoanOfficerService;
import com.quadrilateral.kudi9ja.security.auth.CurrentUser;
import com.quadrilateral.kudi9ja.web.dto.LoanOfficerDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The customer's loan officers: who at Kudi9ja to talk to about their loan. */
@RestController
@RequestMapping("/api/v1/me/officers")
@Tag(name = "Me", description = "The signed-in customer")
public class MyOfficersController {

    private final LoanOfficerService officers;
    private final CurrentUser currentUser;

    public MyOfficersController(LoanOfficerService officers, CurrentUser currentUser) {
        this.officers = officers;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "Your loan officers, lead first")
    public List<LoanOfficerDtos.MyOfficer> mine() {
        return officers.mine(currentUser.requireId());
    }
}
