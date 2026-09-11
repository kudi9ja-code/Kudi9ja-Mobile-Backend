package com.quadrilateral.kudi9ja;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quadrilateral.kudi9ja.domain.admin.AdminUserRepository;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.support.BorrowFlow;
import com.quadrilateral.kudi9ja.support.CapturingMailer;
import com.quadrilateral.kudi9ja.support.SignUpFlow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

/**
 * Borrowing, now that a person decides it.
 *
 * <p>The property this file exists to hold is the first one: <b>submitting an
 * application moves no money</b>. Everything else — the documents, the
 * guarantors, the reason on a refusal — is in service of a human being able to
 * make that decision well, but the decision itself has to be theirs, and a
 * balance that moved before they made it would mean it never was.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(CapturingMailer.Config.class)
@TestPropertySource(properties = {
        "kudi9ja.jobs.enabled=false",
        "kudi9ja.bootstrap.owner-emails=owner@kudi9ja.test"
})
@DisplayName("Applying to borrow")
class LoanApplicationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private CapturingMailer mailer;

    @Autowired
    private UserRepository users;

    @Autowired
    private AdminUserRepository admins;

    private SignUpFlow flow;
    private BorrowFlow borrowing;

    @BeforeEach
    void freshMailbox() {
        mailer.clear();
        flow = new SignUpFlow(mvc, json, mailer);
        borrowing = new BorrowFlow(mvc, json);
    }

    private SignUpFlow.Session owner() throws Exception {
        String ownerEmail = "owner@kudi9ja.test";
        if (users.findByEmailIgnoreCase(ownerEmail).isPresent()) {
            return flow.signIn(ownerEmail);
        }
        return flow.signUp(ownerEmail);
    }

    /** A customer with savings behind them, so the offer is not the obstacle. */
    private SignUpFlow.Session fundedCustomer(SignUpFlow.Session admin) throws Exception {
        SignUpFlow.Session customer = flow.signUp(SignUpFlow.freshEmail("borrower"));
        fundWallet(customer, admin, "1000000");
        postJson("/api/v1/savings/plans/fixed", customer, """
                {"title": "Collateral", "principal": 500000, "days": 365, "pin": "%s"}
                """.formatted(SignUpFlow.PIN));
        return customer;
    }

    @Nested
    @DisplayName("Submitting")
    class Submitting {

        @Test
        @DisplayName("moves no money, however complete the application")
        void submissionCreditsNothing() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            BigDecimal before = balanceOf(customer);
            JsonNode application = borrowing.apply(customer, "200000", 3, "Stock for the shop");

            assertThat(application.get("status").asText()).isEqualTo("PENDING");
            assertThat(application.get("documentsAttached").asInt()).isEqualTo(4);
            assertThat(application.get("guarantors")).hasSize(2);
            assertThat(balanceOf(customer)).isEqualByComparingTo(before);

            // And no loan exists yet either — not a pending one, not any.
            assertThat(getJson("/api/v1/loans", customer)).isEmpty();
        }

        @Test
        @DisplayName("is refused without a bank statement")
        void statementRequired() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            mvc.perform(multipart("/api/v1/loans/applications")
                            .file(formPart("200000", 3, BorrowFlow.twoGuarantors()))
                            .file(photo("front.jpg"))
                            .file(photo("inside.jpg"))
                            .file(photo("stock.jpg"))
                            .header("Authorization", customer.bearer()))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("is refused with one guarantor")
        void twoGuarantorsRequired() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            List<Map<String, Object>> one =
                    List.of(BorrowFlow.guarantor("Adaeze Nwosu", "08031234567", "22222222222"));

            mvc.perform(multipart("/api/v1/loans/applications")
                            .file(formPart("200000", 3, one))
                            .file(statement())
                            .file(photo("front.jpg"))
                            .file(photo("inside.jpg"))
                            .file(photo("stock.jpg"))
                            .header("Authorization", customer.bearer()))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("is refused with two photographs instead of three")
        void threePhotosRequired() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            mvc.perform(multipart("/api/v1/loans/applications")
                            .file(formPart("200000", 3, BorrowFlow.twoGuarantors()))
                            .file(statement())
                            .file(photo("front.jpg"))
                            .file(photo("inside.jpg"))
                            .header("Authorization", customer.bearer()))
                    .andExpect(status().isBadRequest());
        }

        /**
         * A guarantor's BVN is recorded rather than verified, but a number that
         * is not eleven digits is not a BVN at all and there is no reason to
         * store it as one.
         */
        @Test
        @DisplayName("is refused when a guarantor's BVN is the wrong shape")
        void guarantorBvnShapeChecked() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            List<Map<String, Object>> bad = new ArrayList<>(BorrowFlow.twoGuarantors());
            bad.set(1, BorrowFlow.guarantor("Tunde Bakare", "08061234567", "123"));

            mvc.perform(multipart("/api/v1/loans/applications")
                            .file(formPart("200000", 3, bad))
                            .file(statement())
                            .file(photo("front.jpg"))
                            .file(photo("inside.jpg"))
                            .file(photo("stock.jpg"))
                            .header("Authorization", customer.bearer()))
                    .andExpect(status().isBadRequest());
        }

        /**
         * A second application while the first is unread is the same request
         * sent again because nothing visible happened. It doubles what an admin
         * has to read and adds nothing to it.
         */
        @Test
        @DisplayName("is refused while one is already waiting")
        void oneAtATime() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            borrowing.apply(customer, "200000", 3, "Stock");

            mvc.perform(multipart("/api/v1/loans/applications")
                            .file(formPart("150000", 3, BorrowFlow.twoGuarantors()))
                            .file(statement())
                            .file(photo("front.jpg"))
                            .file(photo("inside.jpg"))
                            .file(photo("stock.jpg"))
                            .header("Authorization", customer.bearer()))
                    .andExpect(status().is4xxClientError());
        }
    }

    @Nested
    @DisplayName("Deciding")
    class Deciding {

        @Test
        @DisplayName("approving disburses, net of the fee, exactly as before")
        void approvalDisburses() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            BigDecimal before = balanceOf(customer);
            JsonNode application = borrowing.apply(customer, "200000", 3, "Stock for the shop");
            JsonNode decided = borrowing.approve(admin, application.get("id").asText());

            assertThat(decided.get("status").asText()).isEqualTo("APPROVED");
            assertThat(decided.get("loanId").isNull()).isFalse();

            // The pricing is the old pricing: 200,000 in, 5,000 fee straight out.
            assertThat(balanceOf(customer))
                    .isEqualByComparingTo(before.add(new BigDecimal("195000")));

            JsonNode loan = getJson("/api/v1/loans", customer).get(0);
            assertThat(loan.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(loan.get("outstanding").decimalValue()).isEqualByComparingTo("250000.00");
        }

        @Test
        @DisplayName("the same application cannot be approved twice")
        void approvalIsOnce() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            JsonNode application = borrowing.apply(customer, "200000", 3, "Stock");
            String id = application.get("id").asText();
            borrowing.approve(admin, id);

            BigDecimal after = balanceOf(customer);
            mvc.perform(post("/api/v1/admin/loan-applications/{id}/approve", id)
                            .header("Authorization", admin.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().is4xxClientError());

            assertThat(balanceOf(customer)).isEqualByComparingTo(after);
        }

        @Test
        @DisplayName("rejecting credits nothing and tells the customer why")
        void rejectionExplainsItself() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            BigDecimal before = balanceOf(customer);
            JsonNode application = borrowing.apply(customer, "200000", 3, "Stock");
            String reason = "The statement you sent covers one month. Send three, "
                    + "and make sure the account name matches your profile.";
            borrowing.reject(admin, application.get("id").asText(), reason);

            assertThat(balanceOf(customer)).isEqualByComparingTo(before);

            // The customer reads the reason on their own application, word for
            // word — which is what makes it something they can act on.
            JsonNode mine = getJson("/api/v1/loans/applications", customer).get(0);
            assertThat(mine.get("status").asText()).isEqualTo("REJECTED");
            assertThat(mine.get("rejectionReason").asText()).isEqualTo(reason);

            JsonNode notifications = getJson("/api/v1/notifications", customer);
            assertThat(notifications.get("items").toString()).contains("declined");
        }

        /**
         * A refusal with nothing in it is worse than useless: the customer
         * cannot act on it, and the panel has taught them nothing.
         */
        @Test
        @DisplayName("a refusal without a real reason is refused")
        void rejectionNeedsAReason() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            JsonNode application = borrowing.apply(customer, "200000", 3, "Stock");

            mvc.perform(post("/api/v1/admin/loan-applications/{id}/reject",
                            application.get("id").asText())
                            .header("Authorization", admin.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"no\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("a customer cannot decide their own application")
        void customersCannotApprove() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            JsonNode application = borrowing.apply(customer, "200000", 3, "Stock");

            mvc.perform(post("/api/v1/admin/loan-applications/{id}/approve",
                            application.get("id").asText())
                            .header("Authorization", customer.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().is4xxClientError());

            assertThat(getJson("/api/v1/loans", customer)).isEmpty();
        }

        /**
         * After a refusal the customer is free to apply again — that is the
         * point of giving them something to fix.
         */
        @Test
        @DisplayName("a refused customer can apply again")
        void reapplyingIsAllowed() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            JsonNode first = borrowing.apply(customer, "200000", 3, "Stock");
            borrowing.reject(admin, first.get("id").asText(),
                    "Send a statement covering three months, not one.");

            JsonNode second = borrowing.apply(customer, "200000", 3, "Stock");
            assertThat(second.get("status").asText()).isEqualTo("PENDING");
            assertThat(getJson("/api/v1/loans/applications", customer)).hasSize(2);
        }
    }

    @Nested
    @DisplayName("The admin's view")
    class AdminView {

        @Test
        @DisplayName("carries every document behind a signed link")
        void documentsAreSignedAndComplete() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            JsonNode application = borrowing.apply(customer, "200000", 3, "Stock");
            JsonNode detail = getJson(
                    "/api/v1/admin/loan-applications/" + application.get("id").asText(), admin);

            assertThat(detail.get("bankStatement").get("url").asText())
                    .contains("/api/v1/admin/receipts/")
                    .contains("signature=");
            assertThat(detail.get("businessPhotos")).hasSize(3);
            assertThat(detail.get("guarantors")).hasSize(2);
            // Everything the decision rests on, in one response.
            assertThat(detail.get("businessName").asText()).isNotBlank();
            assertThat(detail.get("monthlyIncome").decimalValue())
                    .isEqualByComparingTo("450000.00");
        }

        @Test
        @DisplayName("the queue shows what is waiting")
        void queueShowsPending() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            borrowing.apply(customer, "200000", 3, "Stock");

            JsonNode queue = getJson("/api/v1/admin/loan-applications?status=PENDING", admin);
            assertThat(queue.get("items")).isNotEmpty();
            assertThat(getJson("/api/v1/admin/loan-applications/pending-count", admin)
                    .get("pending").asLong()).isPositive();
        }
    }

    // -- Plumbing -----------------------------------------------------------

    private MockMultipartFile formPart(
            String amount, int months, List<Map<String, Object>> guarantors) throws Exception {

        Map<String, Object> form = new LinkedHashMap<>();
        form.put("amount", new BigDecimal(amount));
        form.put("months", months);
        form.put("purpose", "Stock for the shop");
        form.put("businessName", "Chioma Grace Provisions");
        form.put("businessAddress", "14 Adeola Odeku Street, Victoria Island, Lagos");
        form.put("monthlyIncome", new BigDecimal("450000"));
        form.put("guarantors", guarantors);
        form.put("pin", SignUpFlow.PIN);

        return new MockMultipartFile(
                "form", "form.json", MediaType.APPLICATION_JSON_VALUE,
                json.writeValueAsBytes(form));
    }

    private static MockMultipartFile statement() {
        return new MockMultipartFile(
                "bankStatement", "statement.pdf", "application/pdf", "statement".getBytes());
    }

    private static MockMultipartFile photo(String name) {
        return new MockMultipartFile("businessPhotos", name, "image/jpeg", name.getBytes());
    }

    /** Money in, the only way there is: a claim an admin confirms. */
    private void fundWallet(SignUpFlow.Session customer, SignUpFlow.Session admin, String amount)
            throws Exception {

        JsonNode reference = read(mvc.perform(post("/api/v1/payins/reference")
                        .header("Authorization", customer.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": " + amount + "}"))
                .andExpect(status().is2xxSuccessful())
                .andReturn());

        MvcResult claim = mvc.perform(multipart("/api/v1/payins")
                        .file(new MockMultipartFile(
                                "receipt", "receipt.png", "image/png", "receipt".getBytes()))
                        .param("amount", amount)
                        .param("reference", reference.get("reference").asText())
                        .param("senderName", "Chioma Grace Adeyemi")
                        .param("senderBank", "Zenith Bank")
                        .header("Authorization", customer.bearer()))
                .andExpect(status().isCreated())
                .andReturn();

        mvc.perform(post("/api/v1/admin/payins/{id}/confirm", read(claim).get("id").asText())
                        .header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is2xxSuccessful());
    }

    private BigDecimal balanceOf(SignUpFlow.Session session) throws Exception {
        return getJson("/api/v1/wallet", session).get("balance").decimalValue();
    }

    private void postJson(String path, SignUpFlow.Session session, String body) throws Exception {
        mvc.perform(post(path)
                        .header("Authorization", session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is2xxSuccessful());
    }

    private JsonNode getJson(String path, SignUpFlow.Session session) throws Exception {
        return read(mvc.perform(get(path).header("Authorization", session.bearer()))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
