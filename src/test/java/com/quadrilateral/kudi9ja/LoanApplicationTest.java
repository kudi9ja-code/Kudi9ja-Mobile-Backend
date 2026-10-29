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
 * guarantor, the reason on a refusal — is in service of a human being able to
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

    /** A funded, verified customer — the only kind who may apply. */
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
            assertThat(application.get("documentsAttached").asInt()).isEqualTo(5);
            assertThat(application.get("guarantors")).hasSize(1);
            assertThat(balanceOf(customer)).isEqualByComparingTo(before);

            // And no loan exists yet either — not a pending one, not any.
            assertThat(getJson("/api/v1/loans", customer)).isEmpty();
        }

        @Test
        @DisplayName("is refused until weekly or monthly repayment is chosen")
        void frequencyRequired() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            Map<String, Object> form = new LinkedHashMap<>(json.readValue(
                    formPart("200000", 3, BorrowFlow.oneGuarantor()).getBytes(), Map.class));
            form.remove("repaymentFrequency");
            MvcResult refused = mvc.perform(multipart("/api/v1/loans/applications")
                            .file(new MockMultipartFile("form", "form.json", MediaType.APPLICATION_JSON_VALUE,
                                    json.writeValueAsBytes(form)))
                            .file(statement())
                            .file(selfie())
                            .file(photo("front.jpg"))
                            .file(photo("inside.jpg"))
                            .file(photo("stock.jpg"))
                            .header("Authorization", customer.bearer()))
                    .andExpect(status().isBadRequest())
                    .andReturn();
            assertThat(refused.getResponse().getContentAsString()).contains("weekly or monthly");
            assertThat(getJson("/api/v1/loans/applications", customer)).isEmpty();
        }

        @Test
        @DisplayName("is refused without a bank statement")
        void statementRequired() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            mvc.perform(multipart("/api/v1/loans/applications")
                            .file(formPart("200000", 3, BorrowFlow.oneGuarantor()))
                            .file(selfie())
                            .file(photo("front.jpg"))
                            .file(photo("inside.jpg"))
                            .file(photo("stock.jpg"))
                            .header("Authorization", customer.bearer()))
                    .andExpect(status().is4xxClientError());
        }

        /**
         * A street address alone is not enough to find anybody. State, local
         * government and a landmark are what a visit is planned from, so an
         * application without them is not an application.
         */
        @Test
        @DisplayName("is refused when the guarantor has no landmark")
        void guarantorLocationRequired() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            Map<String, Object> incomplete = new java.util.HashMap<>(
                    BorrowFlow.guarantor("Adaeze Nwosu", "08031234567", "22222222222"));
            incomplete.put("landmark", "");

            mvc.perform(multipart("/api/v1/loans/applications")
                            .file(formPart("200000", 3, List.of(incomplete)))
                            .file(statement())
                            .file(selfie())
                            .file(photo("front.jpg"))
                            .file(photo("inside.jpg"))
                            .file(photo("stock.jpg"))
                            .header("Authorization", customer.bearer()))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("is refused without a photograph of the applicant")
        void selfieRequired() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            mvc.perform(multipart("/api/v1/loans/applications")
                            .file(formPart("200000", 3, BorrowFlow.oneGuarantor()))
                            .file(statement())
                            .file(photo("front.jpg"))
                            .file(photo("inside.jpg"))
                            .file(photo("stock.jpg"))
                            .header("Authorization", customer.bearer()))
                    .andExpect(status().is4xxClientError());
        }

        /**
         * The statement may be a PDF. A face may not: there is nothing to look
         * at in a document, and "a picture of the applicant" that is a
         * spreadsheet is a form filled in to get past the form.
         */
        @Test
        @DisplayName("is refused when the photograph of the applicant is not a picture")
        void selfieMustBeAnImage() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            mvc.perform(multipart("/api/v1/loans/applications")
                            .file(formPart("200000", 3, BorrowFlow.oneGuarantor()))
                            .file(statement())
                            .file(new MockMultipartFile(
                                    "selfie", "me.pdf", "application/pdf", "pdf".getBytes()))
                            .file(photo("front.jpg"))
                            .file(photo("inside.jpg"))
                            .file(photo("stock.jpg"))
                            .header("Authorization", customer.bearer()))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("is refused with no guarantor")
        void guarantorRequired() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            List<Map<String, Object>> none = List.of();

            mvc.perform(multipart("/api/v1/loans/applications")
                            .file(formPart("200000", 3, none))
                            .file(statement())
                            .file(selfie())
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
                            .file(formPart("200000", 3, BorrowFlow.oneGuarantor()))
                            .file(statement())
                            .file(selfie())
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

            List<Map<String, Object>> bad =
                    List.of(BorrowFlow.guarantor("Tunde Bakare", "08061234567", "123"));

            mvc.perform(multipart("/api/v1/loans/applications")
                            .file(formPart("200000", 3, bad))
                            .file(statement())
                            .file(selfie())
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
                            .file(formPart("150000", 3, BorrowFlow.oneGuarantor()))
                            .file(statement())
                            .file(selfie())
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

            // 200,000 in; only the 5,000 management fee straight out.
            assertThat(balanceOf(customer))
                    .isEqualByComparingTo(before.add(new BigDecimal("195000")));

            JsonNode loan = getJson("/api/v1/loans", customer).get(0);
            assertThat(loan.get("status").asText()).isEqualTo("ACTIVE");
            // ₦250,000 plus 2% on each of the three repayments.
            assertThat(loan.get("outstanding").decimalValue()).isEqualByComparingTo("255001.00");
        }

        @Test
        @DisplayName("a weekly application becomes a weekly loan: same total, one instalment every seven days")
        void weeklyRepayments() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            JsonNode application = borrowing.apply(customer, "200000", 3, "Stock for the shop", "WEEKLY");
            assertThat(application.get("repaymentFrequency").asText()).isEqualTo("WEEKLY");
            borrowing.approve(admin, application.get("id").asText());

            JsonNode loan = getJson("/api/v1/loans", customer).get(0);
            assertThat(loan.get("repaymentFrequency").asText()).isEqualTo("WEEKLY");
            // The same loan and interest as monthly.
            assertThat(loan.get("loanAndInterest").decimalValue()).isEqualByComparingTo("250000.00");
            // As many weeks as the calendar holds between today and three months on.
            java.time.Instant disbursed = java.time.Instant.parse(loan.get("disbursedAt").asText());
            long days = java.time.temporal.ChronoUnit.DAYS.between(disbursed,
                    com.quadrilateral.kudi9ja.common.util.Dates.addMonths(disbursed, 3));
            int weeks = (int) (days / 7);
            assertThat(loan.get("installmentCount").asInt()).isEqualTo(weeks);
            JsonNode schedule = loan.get("schedule");
            assertThat(schedule).hasSize(weeks);

            // Each instalment: its share rounded up to the naira, plus 2% of it rounded up.
            BigDecimal share = new BigDecimal("250000").divide(BigDecimal.valueOf(weeks), 0, java.math.RoundingMode.CEILING);
            BigDecimal fee = share.multiply(new BigDecimal("0.02")).setScale(0, java.math.RoundingMode.CEILING);
            assertThat(schedule.get(0).get("base").decimalValue()).isEqualByComparingTo(share);
            assertThat(schedule.get(0).get("fee").decimalValue()).isEqualByComparingTo(fee);
            assertThat(schedule.get(0).get("amount").decimalValue()).isEqualByComparingTo(share.add(fee));

            BigDecimal sum = BigDecimal.ZERO;
            BigDecimal fees = BigDecimal.ZERO;
            for (JsonNode row : schedule) {
                sum = sum.add(row.get("amount").decimalValue());
                fees = fees.add(row.get("fee").decimalValue());
            }
            // The schedule adds up to exactly what is owed: the loan, interest and every fee.
            assertThat(sum).isEqualByComparingTo(loan.get("totalRepayable").decimalValue());
            assertThat(fees).isEqualByComparingTo(loan.get("totalRepaymentFees").decimalValue());
            assertThat(loan.get("totalRepayable").decimalValue()).isEqualByComparingTo(new BigDecimal("250000").add(fees));

            java.time.Instant first = java.time.Instant.parse(schedule.get(0).get("dueDate").asText());
            java.time.Instant second = java.time.Instant.parse(schedule.get(1).get("dueDate").asText());
            assertThat(java.time.Duration.between(first, second).toDays()).isEqualTo(7);
            assertThat(loan.get("dueDate").asText()).isEqualTo(schedule.get(weeks - 1).get("dueDate").asText());

            // The quote says the same before anybody applies.
            JsonNode quote = getJson("/api/v1/loans/quote?amount=200000&months=3&frequency=WEEKLY", customer);
            assertThat(quote.get("installmentCount").asInt()).isEqualTo(weeks);
            assertThat(quote.get("installmentAmount").decimalValue()).isEqualByComparingTo(share.add(fee));
            assertThat(quote.get("totalRepayable").decimalValue()).isEqualByComparingTo(loan.get("totalRepayable").decimalValue());
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
    @DisplayName("Changing the terms")
    class ChangingTerms {

        private JsonNode offer(SignUpFlow.Session admin, String id, String body) throws Exception {
            return read(mvc.perform(post("/api/v1/admin/loan-applications/{id}/offer", id)
                            .header("Authorization", admin.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andReturn());
        }

        @Test
        @DisplayName("changed terms wait for the customer, and once agreed the loan is lent on them")
        void agreedTermsAreLentOn() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            BigDecimal before = balanceOf(customer);
            String id = borrowing.apply(customer, "200000", 3, "Stock").get("id").asText();
            JsonNode offered = offer(admin, id, """
                    {"months": 4, "interestRatePct": 30, "managementFee": 8000, "transactionFeePct": 3,
                     "note": "Four months suits your sales better."}
                    """);
            assertThat(offered.get("status").asText()).isEqualTo("OFFERED");

            // Nothing is lent while the customer has not answered.
            mvc.perform(post("/api/v1/admin/loan-applications/{id}/approve", id)
                            .header("Authorization", admin.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());

            JsonNode mine = getJson("/api/v1/loans/applications", customer).get(0);
            assertThat(mine.get("status").asText()).isEqualTo("OFFERED");
            assertThat(mine.get("offer").get("months").asInt()).isEqualTo(4);
            assertThat(mine.get("offer").get("flatRate").decimalValue()).isEqualByComparingTo("0.30");
            // Which admin sent it stays on the admin side.
            assertThat(mine.get("offer").hasNonNull("offeredBy")).isFalse();

            JsonNode agreed = read(mvc.perform(post("/api/v1/loans/applications/{id}/agree", id)
                            .header("Authorization", customer.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"pin\": \"" + SignUpFlow.PIN + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn());
            assertThat(agreed.get("status").asText()).isEqualTo("PENDING");

            borrowing.approve(admin, id);

            // 200,000 in; the changed 8,000 management fee out.
            assertThat(balanceOf(customer)).isEqualByComparingTo(before.add(new BigDecimal("192000")));
            JsonNode loan = getJson("/api/v1/loans", customer).get(0);
            assertThat(loan.get("tenureMonths").asInt()).isEqualTo(4);
            // ₦260,000 in four instalments of ₦65,000, each plus 3% (₦1,950).
            assertThat(loan.get("loanAndInterest").decimalValue()).isEqualByComparingTo("260000.00");
            assertThat(loan.get("outstanding").decimalValue()).isEqualByComparingTo("267800.00");
        }

        @Test
        @DisplayName("turning the changed terms down ends the application and lends nothing")
        void turningDownEndsIt() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            BigDecimal before = balanceOf(customer);
            String id = borrowing.apply(customer, "200000", 3, "Stock").get("id").asText();
            offer(admin, id, "{\"interestRatePct\": 40}");

            JsonNode turned = read(mvc.perform(post("/api/v1/loans/applications/{id}/turn-down", id)
                            .header("Authorization", customer.bearer()))
                    .andExpect(status().isOk())
                    .andReturn());
            assertThat(turned.get("status").asText()).isEqualTo("TERMS_DECLINED");
            assertThat(balanceOf(customer)).isEqualByComparingTo(before);
            assertThat(getJson("/api/v1/loans", customer)).isEmpty();

            // It is closed: neither side can move it on, and a new application is welcome.
            mvc.perform(post("/api/v1/admin/loan-applications/{id}/approve", id)
                            .header("Authorization", admin.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
            assertThat(borrowing.apply(customer, "200000", 3, "Stock").get("status").asText()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("an offer that changes nothing is refused")
        void offerMustChangeSomething() throws Exception {
            SignUpFlow.Session admin = owner();
            SignUpFlow.Session customer = fundedCustomer(admin);

            String id = borrowing.apply(customer, "200000", 3, "Stock").get("id").asText();
            mvc.perform(post("/api/v1/admin/loan-applications/{id}/offer", id)
                            .header("Authorization", admin.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"months\": 3}"))
                    .andExpect(status().isBadRequest());
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
            // The statement is stored as sent, lock and all, so the admin is
            // told what opens it — and the customer's own view is not, because
            // it is theirs and they already know it.
            assertThat(detail.get("statementPassword").asText()).isEqualTo("15081990");
            assertThat(application.has("statementPassword")).isFalse();
            assertThat(detail.get("selfie").get("url").asText()).contains("signature=");
            assertThat(detail.get("businessPhotos")).hasSize(3);
            assertThat(detail.get("guarantors")).hasSize(1);
            JsonNode guarantor = detail.get("guarantors").get(0);
            assertThat(guarantor.get("state").asText()).isEqualTo("Lagos");
            assertThat(guarantor.get("localGovernment").asText()).isEqualTo("Eti-Osa");
            assertThat(guarantor.get("landmark").asText()).isEqualTo("Opposite Ikoyi Club");
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
        form.put("repaymentFrequency", "MONTHLY");
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

    private static MockMultipartFile selfie() {
        return new MockMultipartFile("selfie", "me.jpg", "image/jpeg", "face".getBytes());
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
