package com.quadrilateral.kudi9ja.web.controller;

import com.quadrilateral.kudi9ja.common.api.PageResponse;
import com.quadrilateral.kudi9ja.domain.payin.PaystackPaymentService;
import com.quadrilateral.kudi9ja.security.auth.CurrentUser;
import com.quadrilateral.kudi9ja.web.dto.PaystackDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Paying in through Paystack: fund the wallet, or repay a loan, by card, bank
 * transfer or USSD. Credited the moment Paystack confirms it.
 */
@RestController
@RequestMapping("/api/v1/payins/paystack")
@Tag(name = "Money in", description = "Paying in through Paystack")
public class PaystackController {

    private final PaystackPaymentService paystack;
    private final CurrentUser currentUser;

    public PaystackController(PaystackPaymentService paystack, CurrentUser currentUser) {
        this.paystack = paystack;
        this.currentUser = currentUser;
    }

    @GetMapping("/enabled")
    @Operation(summary = "Whether paying in through Paystack is switched on")
    public Map<String, Boolean> enabled() {
        return Map.of("enabled", paystack.enabled());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open a Paystack checkout. Moves no money until Paystack confirms the payment.")
    public PaystackDtos.PaystackCheckout start(@Valid @RequestBody PaystackDtos.StartPaystackPayment request) {
        return paystack.start(currentUser.requireId(), request);
    }

    @GetMapping("/{reference}")
    @Operation(summary = "Back from checkout: confirm with Paystack and credit the wallet if it succeeded")
    public PaystackDtos.PaystackPaymentView confirm(@PathVariable String reference) {
        return paystack.confirm(currentUser.requireId(), reference);
    }

    @GetMapping
    @Operation(summary = "Your payments through Paystack, newest first")
    public PageResponse<PaystackDtos.PaystackPaymentView> mine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return PageResponse.of(
                paystack.mine(currentUser.requireId(), PageRequest.of(page, Math.min(size, 100))),
                PaystackDtos.PaystackPaymentView::from);
    }
}
