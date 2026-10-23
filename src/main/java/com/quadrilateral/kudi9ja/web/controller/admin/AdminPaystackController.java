package com.quadrilateral.kudi9ja.web.controller.admin;

import com.quadrilateral.kudi9ja.common.api.PageResponse;
import com.quadrilateral.kudi9ja.domain.payin.PaystackPayment;
import com.quadrilateral.kudi9ja.domain.payin.PaystackPaymentService;
import com.quadrilateral.kudi9ja.web.dto.PaystackDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Every payment taken through Paystack, for the panel's money-in view. Read-only: Paystack decides. */
@RestController
@RequestMapping("/api/v1/admin/payins/paystack")
@Tag(name = "Admin — money in", description = "Payments taken through Paystack")
public class AdminPaystackController {

    private final PaystackPaymentService paystack;

    public AdminPaystackController(PaystackPaymentService paystack) {
        this.paystack = paystack;
    }

    @GetMapping
    @Operation(summary = "Payments through Paystack, newest first, optionally by status or customer")
    public PageResponse<PaystackDtos.AdminPaystackPayment> list(
            @RequestParam(required = false) PaystackPayment.Status status,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(
                paystack.queue(status, customerId, PageRequest.of(page, Math.min(size, 100))),
                PaystackDtos.AdminPaystackPayment::from);
    }
}
