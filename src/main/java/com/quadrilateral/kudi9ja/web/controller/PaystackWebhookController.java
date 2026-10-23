package com.quadrilateral.kudi9ja.web.controller;

import com.quadrilateral.kudi9ja.domain.payin.PaystackPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where Paystack tells us what happened: a payment succeeded, a transfer
 * arrived, a transfer failed.
 *
 * <p>Open, because Paystack holds no token — what proves a call is Paystack's
 * is the signature over the raw body, checked before anything in it is read.
 * The body is taken as bytes for exactly that reason: re-serialised JSON would
 * not match the signature. Paystack retries anything that does not answer
 * 200, and every handler here is safe to run twice.
 */
@RestController
@RequestMapping("/api/v1/webhooks/paystack")
@Tag(name = "Webhooks", description = "Calls from payment providers")
public class PaystackWebhookController {

    private final PaystackPaymentService paystack;

    public PaystackWebhookController(PaystackPaymentService paystack) {
        this.paystack = paystack;
    }

    @PostMapping
    @Operation(summary = "Paystack events: charge.success, transfer.success, transfer.failed, transfer.reversed")
    public Map<String, Boolean> receive(
            @RequestBody byte[] body,
            @RequestHeader(value = "x-paystack-signature", required = false) String signature) {
        paystack.webhook(body, signature);
        return Map.of("received", true);
    }
}
