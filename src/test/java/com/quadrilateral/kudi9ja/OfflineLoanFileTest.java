package com.quadrilateral.kudi9ja;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.support.CapturingMailer;
import com.quadrilateral.kudi9ja.support.SignUpFlow;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The whole file on a loan made outside the app.
 *
 * <p>An admin enters a borrower who has not signed up, with their details, a
 * guarantor and documents, and records the repayments that come in by hand.
 * When the borrower signs up, the loan arrives on their account already
 * carrying every naira recorded — and the file stays visible on their record.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(CapturingMailer.Config.class)
@TestPropertySource(properties = {
        "kudi9ja.jobs.enabled=false",
        "kudi9ja.bootstrap.owner-emails=owner@kudi9ja.test"
})
@DisplayName("The file on a loan made outside the app")
class OfflineLoanFileTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private CapturingMailer mailer;

    @Autowired
    private UserRepository users;

    private SignUpFlow flow;

    @BeforeEach
    void freshMailbox() {
        mailer.clear();
        flow = new SignUpFlow(mvc, json, mailer);
    }

    private SignUpFlow.Session owner() throws Exception {
        String ownerEmail = "owner@kudi9ja.test";
        if (users.findByEmailIgnoreCase(ownerEmail).isPresent()) {
            return flow.signIn(ownerEmail);
        }
        return flow.signUp(ownerEmail);
    }

    /** A NIN as unique as the BVN it is paired with; no generator starts with 9. */
    private static String ninFor(String bvn) {
        return "9" + bvn.substring(1);
    }

    private Map<String, Object> borrower(String bvn, String email) {
        Map<String, Object> body = new HashMap<>();
        body.put("bvn", bvn);
        body.put("fullName", "Chioma Grace Adeyemi");
        body.put("email", email);
        body.put("phone", SignUpFlow.freshPhone());
        body.put("principal", 200000);
        body.put("tenureMonths", 4);
        body.put("flatRate", 0.32);
        body.put("processingFee", 5000);
        body.put("transactionFee", 2000);
        body.put("purpose", "Shop restocking");
        body.put("disbursedAt", Instant.now().minus(40, ChronoUnit.DAYS).toString());
        body.put("amountRepaid", 0);
        body.put("nin", ninFor(bvn));
        body.put("dateOfBirth", "1990-04-12");
        body.put("gender", "Female");
        body.put("address", "14 Allen Avenue, Ikeja");
        body.put("state", "Lagos");
        body.put("occupation", "Trader");
        body.put("businessName", "Grace Provisions");
        body.put("businessAddress", "Shop 22, Computer Village, Ikeja");
        body.put("monthlyIncome", 350000);
        body.put("payoutBank", "Zenith Bank");
        body.put("payoutAccountNumber", "0123456789");
        body.put("notes", "Paid the first month in cash at the shop.");
        body.put("guarantors", List.of(Map.of(
                "fullName", "Emeka Obi",
                "phone", "08021234567",
                "relationship", "Brother",
                "address", "3 Bode Thomas, Surulere",
                "state", "Lagos")));
        return body;
    }

    @Test
    @DisplayName("details, documents and hand-recorded repayments follow the borrower onto their account")
    void fileFollowsTheBorrower() throws Exception {
        SignUpFlow.Session admin = owner();
        String bvn = SignUpFlow.nextIdentityNumber(41);
        String email = SignUpFlow.freshEmail("offline");

        JsonNode entered = send(admin, post("/api/v1/admin/loans/imports"), borrower(bvn, email), 201);
        String id = entered.get("id").asText();
        assertThat(entered.get("businessName").asText()).isEqualTo("Grace Provisions");
        // Masked like every other BVN and NIN the API returns: only the last four show.
        assertThat(entered.get("ninLast4").asText()).matches("\\*+" + ninFor(bvn).substring(7));
        assertThat(entered.get("guarantors").get(0).get("fullName").asText()).isEqualTo("Emeka Obi");
        // 200,000 at 32% over four months.
        assertThat(entered.get("totalRepayable").decimalValue()).isEqualByComparingTo("264000.00");
        // Both fees on their own lines, and what was actually paid out.
        assertThat(entered.get("processingFee").decimalValue()).isEqualByComparingTo("5000.00");
        assertThat(entered.get("transactionFee").decimalValue()).isEqualByComparingTo("2000.00");
        assertThat(entered.get("netDisbursed").decimalValue()).isEqualByComparingTo("193000.00");

        // A document goes on the file and comes back with a signed link.
        JsonNode doc = json.readTree(mvc.perform(multipart("/api/v1/admin/loans/imports/" + id + "/documents")
                        .file(new MockMultipartFile("file", "statement.pdf", "application/pdf",
                                "%PDF-1.4 statement".getBytes(StandardCharsets.UTF_8)))
                        .param("label", "Bank statement")
                        .header("Authorization", admin.bearer()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        assertThat(doc.get("label").asText()).isEqualTo("Bank statement");
        assertThat(doc.get("url").asText()).startsWith("/api/v1/admin/receipts/");

        // Something that is not a document is refused.
        mvc.perform(multipart("/api/v1/admin/loans/imports/" + id + "/documents")
                        .file(new MockMultipartFile("file", "virus.exe", "application/x-msdownload", new byte[] {1, 2, 3}))
                        .header("Authorization", admin.bearer()))
                .andExpect(status().isBadRequest());

        // A repayment by hand moves what is owed before they have signed up.
        JsonNode file = send(admin, post("/api/v1/admin/loans/imports/" + id + "/repayments"),
                Map.of("amount", 66000, "method", "CASH", "reference", "Receipt book 0012"), 200);
        assertThat(file.get("loan").get("amountRepaid").decimalValue()).isEqualByComparingTo("66000.00");
        assertThat(file.get("loan").get("outstanding").decimalValue()).isEqualByComparingTo("198000.00");
        assertThat(file.get("repayments")).hasSize(1);
        assertThat(file.get("documents")).hasSize(1);

        // More than is owed is a typo, not a payment.
        send(admin, post("/api/v1/admin/loans/imports/" + id + "/repayments"),
                Map.of("amount", 500000, "method", "CASH"), 400);

        // The borrower signs up. The loan arrives carrying the cash repayment.
        SignUpFlow.Session customer = flow.signUp(email, bvn, ninFor(bvn));
        JsonNode loans = getJson("/api/v1/loans", customer);
        assertThat(loans).hasSize(1);
        String loanId = loans.get(0).get("id").asText();
        assertThat(loans.get(0).get("amountRepaid").decimalValue()).isEqualByComparingTo("66000.00");

        // Who they are is on the account now; the loan's file still edits.
        send(admin, patch("/api/v1/admin/loans/imports/" + id), Map.of("phone", "08099999999"), 409);
        JsonNode updated = send(admin, patch("/api/v1/admin/loans/imports/" + id),
                Map.of("notes", "Moved the shop to Alaba in March."), 200);
        assertThat(updated.get("loan").get("notes").asText()).contains("Alaba");

        // A second cash payment, recorded after the claim, reaches the live loan.
        send(admin, post("/api/v1/admin/loans/imports/" + id + "/repayments"),
                Map.of("amount", 30000, "method", "BANK_TRANSFER", "reference", "ZEN-88213"), 200);
        // And one recorded against the loan itself.
        JsonNode records = send(admin, post("/api/v1/admin/loans/" + loanId + "/repayments"),
                Map.of("amount", 4000, "method", "POS"), 200);
        assertThat(records.get("repayments")).hasSize(3);
        assertThat(records.get("documents")).hasSize(1);

        JsonNode loan = getJson("/api/v1/loans/" + loanId, customer);
        assertThat(loan.get("amountRepaid").decimalValue()).isEqualByComparingTo("100000.00");
        assertThat(loan.get("outstanding").decimalValue()).isEqualByComparingTo("164000.00");

        // Never through the wallet.
        assertThat(getJson("/api/v1/wallet", customer).get("balance").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(getJson("/api/v1/notifications", customer).get("items").toString()).contains("Repayment received");

        // The customer's record in the panel shows the lot.
        JsonNode onRecord = getJson("/api/v1/admin/customers/" + customer.userId() + "/loan-records", admin);
        assertThat(onRecord.get("importedLoans")).hasSize(1);
        assertThat(onRecord.get("importedLoans").get(0).get("businessName").asText()).isEqualTo("Grace Provisions");
        assertThat(onRecord.get("documents")).hasSize(1);
        assertThat(onRecord.get("repayments")).hasSize(3);

        // Every step is on the audit log.
        assertThat(getJson("/api/v1/admin/audit?size=30", admin).get("items").toString())
                .contains("Document attached to a loan file")
                .contains("Repayment recorded by hand")
                .contains("Offline loan file updated");
    }

    @Test
    @DisplayName("a long tenure priced above 100% can be entered, a percentage typed as a fraction cannot")
    void longTenureRates() throws Exception {
        SignUpFlow.Session admin = owner();
        Map<String, Object> body = borrower(SignUpFlow.nextIdentityNumber(42), SignUpFlow.freshEmail("long"));
        body.put("tenureMonths", 24);
        body.put("flatRate", 1.34);
        JsonNode entered = send(admin, post("/api/v1/admin/loans/imports"), body, 201);
        assertThat(entered.get("totalRepayable").decimalValue()).isEqualByComparingTo("468000.00");

        body.put("flatRate", 32);
        send(admin, post("/api/v1/admin/loans/imports"), body, 400);
    }

    @Test
    @DisplayName("a customer cannot read or change anybody's loan file")
    void customersAreRefused() throws Exception {
        SignUpFlow.Session admin = owner();
        JsonNode entered = send(admin, post("/api/v1/admin/loans/imports"),
                borrower(SignUpFlow.nextIdentityNumber(43), SignUpFlow.freshEmail("private")), 201);
        SignUpFlow.Session customer = flow.signUp(SignUpFlow.freshEmail("nosy"));

        assertThat(mvc.perform(get("/api/v1/admin/loans/imports/" + entered.get("id").asText())
                        .header("Authorization", customer.bearer()))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        send(customer, post("/api/v1/admin/loans/imports/" + entered.get("id").asText() + "/repayments"),
                Map.of("amount", 1000, "method", "CASH"), 403);
    }

    // -- Plumbing -----------------------------------------------------------

    private JsonNode send(SignUpFlow.Session session, MockHttpServletRequestBuilder request,
            Map<String, Object> body, int expected) throws Exception {
        MvcResult result = mvc.perform(request
                        .header("Authorization", session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as("%s %s → %s", request, body, result.getResponse().getContentAsString())
                .isEqualTo(expected);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }

    private JsonNode getJson(String path, SignUpFlow.Session session) throws Exception {
        return json.readTree(mvc.perform(get(path).header("Authorization", session.bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
