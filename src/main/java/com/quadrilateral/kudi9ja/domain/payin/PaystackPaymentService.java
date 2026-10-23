package com.quadrilateral.kudi9ja.domain.payin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.common.util.Money;
import com.quadrilateral.kudi9ja.domain.admin.AdminAccessService;
import com.quadrilateral.kudi9ja.domain.audit.AuditCategory;
import com.quadrilateral.kudi9ja.domain.audit.AuditService;
import com.quadrilateral.kudi9ja.domain.loan.Loan;
import com.quadrilateral.kudi9ja.domain.loan.LoanRepository;
import com.quadrilateral.kudi9ja.domain.payout.WithdrawalService;
import com.quadrilateral.kudi9ja.domain.settings.PlatformSettings;
import com.quadrilateral.kudi9ja.domain.settings.SettingsService;
import com.quadrilateral.kudi9ja.domain.user.AccountStatus;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.integration.paystack.PaystackGateway;
import com.quadrilateral.kudi9ja.web.dto.PaystackDtos;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Money in, through Paystack checkout.
 *
 * <p>The customer asks to pay; we open a checkout with a reference that is
 * ours and tied to them, and send them to it. When they come back — or when
 * Paystack's webhook arrives, whichever is first — we ask Paystack what really
 * happened and credit the wallet if it succeeded. Nothing is credited on the
 * browser's word, nor on the webhook body alone: the verification call is the
 * authority.
 */
@Service
public class PaystackPaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaystackPaymentService.class);
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectProvider<PaystackGateway> gateway;
    private final PaystackSettlement settlement;
    private final PaystackPaymentRepository payments;
    private final UserRepository users;
    private final LoanRepository loans;
    private final SettingsService settings;
    private final WithdrawalService withdrawals;
    private final AdminAccessService access;
    private final AuditService audit;
    private final ObjectMapper json;
    private final String callbackUrl;

    public PaystackPaymentService(
            ObjectProvider<PaystackGateway> gateway,
            PaystackSettlement settlement,
            PaystackPaymentRepository payments,
            UserRepository users,
            LoanRepository loans,
            SettingsService settings,
            WithdrawalService withdrawals,
            AdminAccessService access,
            AuditService audit,
            ObjectMapper json,
            @Value("${kudi9ja.paystack.callback-url:}") String callbackUrl) {
        this.gateway = gateway;
        this.settlement = settlement;
        this.payments = payments;
        this.users = users;
        this.loans = loans;
        this.settings = settings;
        this.withdrawals = withdrawals;
        this.access = access;
        this.audit = audit;
        this.json = json;
        this.callbackUrl = callbackUrl == null ? "" : callbackUrl.trim();
    }

    public boolean enabled() {
        return gateway.getIfAvailable() != null;
    }

    /** Opens a checkout for the signed-in customer. Moves no money. */
    public PaystackDtos.PaystackCheckout start(UUID userId, PaystackDtos.StartPaystackPayment request) {
        PaystackGateway paystack = require();
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("Your account"));
        if (user.getAccountStatus() == AccountStatus.FROZEN || user.getAccountStatus() == AccountStatus.CLOSED) {
            throw new ApiException(ErrorCode.ACCOUNT_FROZEN,
                    "This account cannot take payments right now. Contact support@kudi9ja.com.");
        }

        PlatformSettings s = settings.currentReadOnly();
        BigDecimal amount = Money.of(request.amount());
        if (Money.lt(amount, s.getMinDepositAmount())) {
            throw new ApiException(ErrorCode.AMOUNT_TOO_SMALL,
                    "The smallest payment is " + Money.naira(s.getMinDepositAmount()) + ".");
        }

        DepositPurpose purpose = request.purpose() == null ? DepositPurpose.WALLET : request.purpose();
        UUID loanId = null;
        if (purpose == DepositPurpose.LOAN_REPAYMENT) {
            if (request.loanId() == null) {
                throw ApiException.validation("Choose the loan you are repaying.");
            }
            Loan loan = loans.findByIdAndUserId(request.loanId(), userId)
                    .orElseThrow(() -> ApiException.notFound("That loan"));
            if (!loan.isOpen()) {
                throw new ApiException(ErrorCode.LOAN_CLOSED, "That loan is already closed. There is nothing to repay.");
            }
            if (Money.gt(amount, loan.outstanding())) {
                throw new ApiException(ErrorCode.AMOUNT_TOO_LARGE,
                        "That is more than the " + Money.naira(loan.outstanding()) + " still owed on this loan.");
            }
            loanId = loan.getId();
        }

        String reference = user.getCustomerRef() + "-" + random(8);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("customer_ref", user.getCustomerRef());
        metadata.put("purpose", purpose.name());
        if (loanId != null) {
            metadata.put("loan_id", loanId.toString());
        }

        PaystackGateway.Initialized opened;
        try {
            opened = paystack.initialize(user.getEmail(), amount.movePointRight(2).longValueExact(), reference,
                    callbackFor(request.callbackUrl()), metadata);
        } catch (PaystackGateway.PaystackException e) {
            throw new ApiException(ErrorCode.INTERNAL, "Paystack could not open a checkout. Try again in a moment.");
        }

        PaystackPayment payment = new PaystackPayment();
        payment.setId(UUID.randomUUID());
        payment.setUserId(userId);
        payment.setCustomerName(user.getFullName());
        payment.setCustomerRef(user.getCustomerRef());
        payment.setReference(reference);
        payment.setPurpose(purpose);
        payment.setLoanId(loanId);
        payment.setAmount(amount);
        payment.setStatus(PaystackPayment.Status.INITIALIZED);
        payment.setAuthorizationUrl(opened.authorizationUrl());
        payment.setCreatedAt(Instant.now());
        payments.save(payment);

        return new PaystackDtos.PaystackCheckout(reference, opened.authorizationUrl(), opened.accessCode(), amount);
    }

    /**
     * The customer is back from checkout. Asks Paystack what happened and
     * credits the wallet if it succeeded — once, however often this is asked.
     */
    public PaystackDtos.PaystackPaymentView confirm(UUID userId, String reference) {
        PaystackPayment payment = payments.findByReferenceAndUserId(reference, userId)
                .orElseThrow(() -> ApiException.notFound("That payment"));
        if (!payment.isCredited() && payment.getStatus() == PaystackPayment.Status.INITIALIZED) {
            PaystackGateway paystack = require();
            try {
                PaystackPayment settled = settlement.apply(reference, paystack.verify(reference));
                if (settled != null) {
                    payment = settled;
                }
            } catch (PaystackGateway.PaystackException e) {
                // Paystack did not answer; the payment stays waiting and the
                // webhook will settle it. Say so rather than failing.
                log.warn("Could not verify {} with Paystack: {}", reference, e.getMessage());
            }
        }
        return PaystackDtos.PaystackPaymentView.from(payment);
    }

    /**
     * Paystack's webhook. The signature is checked against the raw body before
     * anything is read from it; a charge is then verified with Paystack again,
     * so even a correctly signed body is not taken as the last word on money.
     */
    public void webhook(byte[] body, String signature) {
        PaystackGateway paystack = require();
        if (!paystack.signatureValid(body, signature)) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "That webhook is not signed by Paystack.");
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (IOException e) {
            throw ApiException.validation("That webhook body is not JSON.");
        }
        String event = root.path("event").asText("");
        JsonNode data = root.path("data");
        String reference = data.path("reference").asText(null);

        switch (event) {
            case "charge.success" -> {
                if (reference == null) {
                    return;
                }
                if (payments.findByReference(reference).isEmpty()) {
                    audit.recordSystem(
                            AuditCategory.COMPLIANCE,
                            "Paystack payment with an unknown reference",
                            "Paystack reported a successful payment of " + data.path("amount").asLong(0)
                                    + " kobo under reference " + reference + ", which Kudi9ja did not issue. "
                                    + "Nothing was credited. Trace it on the Paystack dashboard.");
                    return;
                }
                settlement.apply(reference, paystack.verify(reference));
            }
            case "transfer.success" -> withdrawals.payoutOutcome(reference, true, null);
            case "transfer.failed", "transfer.reversed" -> withdrawals.payoutOutcome(
                    reference,
                    false,
                    data.hasNonNull("reason") ? data.path("reason").asText()
                            : "transfer.reversed".equals(event) ? "the transfer was reversed" : "the transfer failed");
            default -> log.debug("Ignoring Paystack event {}", event);
        }
    }

    public Page<PaystackPayment> mine(UUID userId, Pageable pageable) {
        return payments.findByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    public Page<PaystackPayment> queue(PaystackPayment.Status status, UUID customerId, Pageable pageable) {
        access.requireCanView();
        if (customerId != null) {
            return payments.findByUserIdOrderByCreatedAtDesc(customerId, pageable);
        }
        return status == null
                ? payments.findAllByOrderByCreatedAtDesc(pageable)
                : payments.findByStatusOrderByCreatedAtDesc(status, pageable);
    }

    // ── Plumbing ──────────────────────────────────────────────────────────

    private PaystackGateway require() {
        PaystackGateway paystack = gateway.getIfAvailable();
        if (paystack == null) {
            throw new ApiException(ErrorCode.FEATURE_DISABLED,
                    "Paying in through Paystack is not switched on yet. Contact support@kudi9ja.com.");
        }
        return paystack;
    }

    /**
     * Where Paystack sends the customer back to. The configured address wins;
     * without one, the website's own address is accepted only if it is
     * https or a local development server, so a checkout cannot be made to
     * return somewhere else.
     */
    private String callbackFor(String requested) {
        if (!callbackUrl.isBlank()) {
            return callbackUrl;
        }
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String r = requested.trim();
        boolean local = r.startsWith("http://localhost:") || r.startsWith("http://127.0.0.1:");
        return r.startsWith("https://") || local ? r : null;
    }

    private static String random(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }
}
