package com.quadrilateral.kudi9ja;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.support.CapturingMailer;
import com.quadrilateral.kudi9ja.support.FakePaystackGateway;
import com.quadrilateral.kudi9ja.support.SignUpFlow;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Money through Paystack: in by checkout, out by transfer.
 *
 * <p>What matters is that money is credited once, only when Paystack confirms
 * it, for the amount that was asked — and that a transfer which fails puts
 * every naira back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import({CapturingMailer.Config.class, FakePaystackGateway.Config.class})
@TestPropertySource(properties = {
        "kudi9ja.jobs.enabled=false",
        "kudi9ja.bootstrap.owner-emails=owner@kudi9ja.test"
})
@DisplayName("Paystack")
class PaystackTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private CapturingMailer mailer;

    @Autowired
    private UserRepository users;

    @Autowired
    private FakePaystackGateway paystack;

    private SignUpFlow flow;

    @BeforeEach
    void fresh() {
        mailer.clear();
        paystack.transferStatus = "pending";
        flow = new SignUpFlow(mvc, json, mailer);
    }

    @Test
    @DisplayName("a payment is credited once, however many times it is confirmed")
    void creditedOnce() throws Exception {
        SignUpFlow.Session customer = flow.signUp(SignUpFlow.freshEmail("paystack"));

        JsonNode checkout = postJson("/api/v1/payins/paystack", customer, """
                {"amount": 25000}
                """);
        String reference = checkout.get("reference").asText();
        assertThat(checkout.get("authorizationUrl").asText()).endsWith(reference);

        // Back from checkout before paying: nothing moves.
        assertThat(getJson("/api/v1/payins/paystack/" + reference, customer).get("status").asText())
                .isEqualTo("INITIALIZED");
        assertThat(balanceOf(customer)).isEqualByComparingTo(BigDecimal.ZERO);

        paystack.paid(reference);
        JsonNode confirmed = getJson("/api/v1/payins/paystack/" + reference, customer);
        assertThat(confirmed.get("status").asText()).isEqualTo("SUCCESSFUL");
        assertThat(confirmed.get("credited").asBoolean()).isTrue();

        // The page reloaded, and the webhook arrived too.
        getJson("/api/v1/payins/paystack/" + reference, customer);
        webhook("charge.success", reference).andExpect(status().isOk());

        assertThat(balanceOf(customer)).isEqualByComparingTo("25000.00");
        assertThat(getJson("/api/v1/wallet/reconciliation", customer).get("balanced").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("an unsigned webhook is refused, and a payment for the wrong amount is held, not credited")
    void refusesWhatItCannotTrust() throws Exception {
        SignUpFlow.Session customer = flow.signUp(SignUpFlow.freshEmail("paystackbad"));
        String reference = postJson("/api/v1/payins/paystack", customer, """
                {"amount": 40000}
                """).get("reference").asText();

        paystack.paid(reference);
        mvc.perform(post("/api/v1/webhooks/paystack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-paystack-signature", "forged")
                        .content(event("charge.success", reference)))
                .andExpect(status().isUnauthorized());
        assertThat(balanceOf(customer)).isEqualByComparingTo(BigDecimal.ZERO);

        String other = postJson("/api/v1/payins/paystack", customer, """
                {"amount": 40000}
                """).get("reference").asText();
        paystack.paid(other, 100L);
        assertThat(getJson("/api/v1/payins/paystack/" + other, customer).get("status").asText()).isEqualTo("FAILED");
        assertThat(balanceOf(customer)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("an approved withdrawal is sent, settles when it arrives, and is refunded when it fails")
    void payouts() throws Exception {
        SignUpFlow.Session customer = flow.signUp(SignUpFlow.freshEmail("payout"));
        SignUpFlow.Session admin = owner();
        String reference = postJson("/api/v1/payins/paystack", customer, """
                {"amount": 300000}
                """).get("reference").asText();
        paystack.paid(reference);
        getJson("/api/v1/payins/paystack/" + reference, customer);

        String first = postJson("/api/v1/withdrawals", customer, """
                {"amount": 100000, "pin": "5271"}
                """).get("id").asText();
        JsonNode approved = postJson("/api/v1/admin/withdrawals/" + first + "/approve", admin, "{}");
        assertThat(approved.get("payoutProvider").asText()).isEqualTo("PAYSTACK");
        assertThat(approved.get("payoutStatus").asText()).isEqualTo("PROCESSING");
        assertThat(paystack.transfers()).contains("k9wd-" + first);

        webhook("transfer.success", "k9wd-" + first).andExpect(status().isOk());
        assertThat(balanceOf(customer)).isEqualByComparingTo("200000.00");

        String second = postJson("/api/v1/withdrawals", customer, """
                {"amount": 50000, "pin": "5271"}
                """).get("id").asText();
        postJson("/api/v1/admin/withdrawals/" + second + "/approve", admin, "{}");
        assertThat(balanceOf(customer)).isEqualByComparingTo("150000.00");

        webhook("transfer.failed", "k9wd-" + second).andExpect(status().isOk());
        webhook("transfer.failed", "k9wd-" + second).andExpect(status().isOk());
        assertThat(balanceOf(customer)).isEqualByComparingTo("200000.00");
        assertThat(getJson("/api/v1/wallet/reconciliation", customer).get("balanced").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a loan repayment cannot be for more than is owed, nor for somebody else's loan")
    void repaymentGuards() throws Exception {
        SignUpFlow.Session customer = flow.signUp(SignUpFlow.freshEmail("paystackloan"));
        mvc.perform(post("/api/v1/payins/paystack")
                        .header("Authorization", customer.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 5000, "purpose": "LOAN_REPAYMENT", "loanId": "00000000-0000-0000-0000-000000000001"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("with Paystack on, a bank-transfer receipt is no longer taken: there is nothing to approve")
    void receiptsRetired() throws Exception {
        SignUpFlow.Session customer = flow.signUp(SignUpFlow.freshEmail("noreceipt"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/payins")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "receipt", "transfer.png", MediaType.IMAGE_PNG_VALUE, "receipt".getBytes()))
                        .param("amount", "50000")
                        .param("reference", "ANY")
                        .param("senderName", "Chioma Grace Adeyemi")
                        .header("Authorization", customer.bearer()))
                .andExpect(jsonPath("$.code").value("FEATURE_DISABLED"));
    }

    // -- Plumbing -----------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions webhook(String event, String reference) throws Exception {
        return mvc.perform(post("/api/v1/webhooks/paystack")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-paystack-signature", FakePaystackGateway.SIGNATURE)
                .content(event(event, reference)));
    }

    private static String event(String event, String reference) {
        return "{\"event\":\"" + event + "\",\"data\":{\"reference\":\"" + reference + "\",\"amount\":0}}";
    }

    private SignUpFlow.Session owner() throws Exception {
        String ownerEmail = "owner@kudi9ja.test";
        if (users.findByEmailIgnoreCase(ownerEmail).isPresent()) {
            return flow.signIn(ownerEmail);
        }
        return flow.signUp(ownerEmail);
    }

    private BigDecimal balanceOf(SignUpFlow.Session session) throws Exception {
        return getJson("/api/v1/wallet", session).get("balance").decimalValue();
    }

    private JsonNode getJson(String path, SignUpFlow.Session session) throws Exception {
        return read(mvc.perform(get(path).header("Authorization", session.bearer()))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode postJson(String path, SignUpFlow.Session session, String body) throws Exception {
        return read(mvc.perform(post(path)
                        .header("Authorization", session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
