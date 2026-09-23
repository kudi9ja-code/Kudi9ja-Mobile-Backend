package com.quadrilateral.kudi9ja;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.support.CapturingMailer;
import com.quadrilateral.kudi9ja.support.SignUpFlow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
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
 * Loans written on paper before the app existed.
 *
 * <p>An admin enters them against a BVN. The customer signs up as anybody
 * does, and the loans are on the account before they see their first screen.
 * The wallet stays at zero throughout: the money moved outside the app.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(CapturingMailer.Config.class)
@TestPropertySource(properties = {
        "kudi9ja.jobs.enabled=false",
        "kudi9ja.bootstrap.owner-emails=owner@kudi9ja.test"
})
@DisplayName("Loans entered from the paper records")
class LoanImportTest {

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

    @Test
    @DisplayName("a loan entered before signup is on the account when the customer arrives")
    void waitingLoanLandsAtSignup() throws Exception {
        SignUpFlow.Session admin = owner();
        String bvn = SignUpFlow.nextIdentityNumber(33);
        String email = SignUpFlow.freshEmail("paper");

        // Lent two months ago over six months; one instalment paid so far.
        Instant disbursed = Instant.now().minus(61, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        JsonNode entered = importLoan(admin, Map.ofEntries(
                Map.entry("bvn", bvn),
                Map.entry("fullName", "Chioma Grace Adeyemi"),
                Map.entry("email", email),
                Map.entry("phone", "08031234567"),
                Map.entry("principal", 120000),
                Map.entry("tenureMonths", 6),
                Map.entry("flatRate", 0.12),
                Map.entry("processingFee", 2400),
                Map.entry("purpose", "Shop restocking"),
                Map.entry("disbursedAt", disbursed.toString()),
                Map.entry("amountRepaid", 22400)));

        assertThat(entered.get("claimed").asBoolean()).isFalse();
        assertThat(entered.get("bvnLast4").asText()).endsWith(bvn.substring(7)).doesNotContain(bvn);
        assertThat(entered.get("totalRepayable").decimalValue()).isEqualByComparingTo("134400.00");
        assertThat(entered.get("outstanding").decimalValue()).isEqualByComparingTo("112000.00");

        // It is waiting, and the admin can see it waiting.
        assertThat(listJson(admin, "?claimed=false").toString()).contains(entered.get("id").asText());

        // The customer signs up the ordinary way, with that BVN.
        SignUpFlow.Session customer = flow.signUp(email, bvn);

        JsonNode loans = getJson("/api/v1/loans", customer);
        assertThat(loans).hasSize(1);
        JsonNode loan = loans.get(0);
        assertThat(loan.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(loan.get("principal").decimalValue()).isEqualByComparingTo("120000.00");
        assertThat(loan.get("flatRate").decimalValue()).isEqualByComparingTo("0.12");
        assertThat(loan.get("amountRepaid").decimalValue()).isEqualByComparingTo("22400.00");
        assertThat(loan.get("outstanding").decimalValue()).isEqualByComparingTo("112000.00");
        assertThat(loan.get("installmentsPaid").asInt()).isEqualTo(1);
        assertThat(loan.get("schedule")).hasSize(6);
        assertThat(loan.get("disbursedAt").asText()).isEqualTo(disbursed.toString());

        // The money moved outside the app: the wallet is untouched.
        JsonNode wallet = getJson("/api/v1/wallet", customer);
        assertThat(wallet.get("balance").decimalValue()).isEqualByComparingTo("0.00");

        // They are told, and the admin's list now shows it claimed.
        JsonNode feed = getJson("/api/v1/notifications", customer);
        assertThat(feed.get("items").toString()).contains("Your loan is here");

        JsonNode claimed = listJson(admin, "?claimed=true");
        JsonNode row = null;
        for (JsonNode item : claimed) {
            if (item.get("id").asText().equals(entered.get("id").asText())) {
                row = item;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("customerId").asText()).isEqualTo(customer.userId().toString());
        assertThat(row.get("loanId").asText()).isEqualTo(loan.get("id").asText());
        // The BVN went with the claim; the account holds it now.
        assertThat(row.hasNonNull("bvnLast4")).isFalse();

        // A claimed entry cannot be un-entered.
        mvc.perform(delete("/api/v1/admin/loans/imports/" + entered.get("id").asText())
                        .header("Authorization", admin.bearer()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("a loan entered after signup lands at once, and a cleared one arrives repaid")
    void existingCustomerAndRepaidLoan() throws Exception {
        SignUpFlow.Session admin = owner();
        String bvn = SignUpFlow.nextIdentityNumber(34);
        SignUpFlow.Session customer = flow.signUp(SignUpFlow.freshEmail("early"), bvn);

        Instant disbursed = Instant.now().minus(200, ChronoUnit.DAYS);
        JsonNode entered = importLoan(admin, Map.of(
                "bvn", bvn,
                "fullName", "Chioma Grace Adeyemi",
                "principal", 50000,
                "tenureMonths", 3,
                "flatRate", 0.10,
                "purpose", "School fees",
                "disbursedAt", disbursed.toString(),
                "amountRepaid", 55000));

        assertThat(entered.get("claimed").asBoolean()).isTrue();
        assertThat(entered.get("customerId").asText()).isEqualTo(customer.userId().toString());

        JsonNode loans = getJson("/api/v1/loans", customer);
        assertThat(loans).hasSize(1);
        assertThat(loans.get(0).get("status").asText()).isEqualTo("REPAID");
        assertThat(loans.get(0).get("outstanding").decimalValue()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("the lending book lists an imported loan, repaid ones included")
    void bookListsImportedLoans() throws Exception {
        SignUpFlow.Session admin = owner();
        String bvn = SignUpFlow.nextIdentityNumber(37);
        SignUpFlow.Session customer = flow.signUp(SignUpFlow.freshEmail("book"), bvn);

        importLoan(admin, Map.of(
                "bvn", bvn,
                "fullName", "Chioma Grace Adeyemi",
                "principal", 80000,
                "tenureMonths", 2,
                "flatRate", 0.10,
                "processingFee", 1600,
                "purpose", "Equipment",
                "disbursedAt", Instant.now().minus(300, ChronoUnit.DAYS).toString(),
                "amountRepaid", 88000));

        String customerId = customer.userId().toString();

        // The book carries what the panel totals: repaid and the fee taken.
        JsonNode book = getJson("/api/v1/admin/loans", admin);
        JsonNode row = null;
        for (JsonNode item : book.get("items")) {
            if (item.get("customerId").asText().equals(customerId)) {
                row = item;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("status").asText()).isEqualTo("REPAID");
        assertThat(row.get("amountRepaid").decimalValue()).isEqualByComparingTo("88000.00");
        assertThat(row.get("processingFee").decimalValue()).isEqualByComparingTo("1600.00");
        assertThat(row.get("outstanding").decimalValue()).isEqualByComparingTo("0.00");

        // A closed status is a filter that answers, not an empty list.
        assertThat(getJson("/api/v1/admin/loans?status=REPAID", admin).get("items").toString())
                .contains(customerId);
    }

    @Test
    @DisplayName("a loan past its due date with a balance arrives overdue")
    void overdueOnArrival() throws Exception {
        SignUpFlow.Session admin = owner();
        String bvn = SignUpFlow.nextIdentityNumber(35);
        SignUpFlow.Session customer = flow.signUp(SignUpFlow.freshEmail("late"), bvn);

        importLoan(admin, Map.of(
                "bvn", bvn,
                "fullName", "Chioma Grace Adeyemi",
                "principal", 30000,
                "tenureMonths", 1,
                "flatRate", 0.05,
                "purpose", "Rent",
                "disbursedAt", Instant.now().minus(45, ChronoUnit.DAYS).toString(),
                "amountRepaid", 10000));

        JsonNode loans = getJson("/api/v1/loans", customer);
        assertThat(loans.get(0).get("status").asText()).isEqualTo("OVERDUE");
        assertThat(loans.get(0).get("outstanding").decimalValue()).isEqualByComparingTo("21500.00");
    }

    @Test
    @DisplayName("the book reports the interest the live loans are charged")
    void interestChargedOnTheOpenBook() throws Exception {
        SignUpFlow.Session admin = owner();
        String bvn = SignUpFlow.nextIdentityNumber(38);
        SignUpFlow.Session customer = flow.signUp(SignUpFlow.freshEmail("interest"), bvn);

        JsonNode bookBefore = getJson("/api/v1/admin/overview", admin).get("book");
        BigDecimal before = bookBefore.get("totalInterestCharged").decimalValue();
        BigDecimal paidOutBefore = bookBefore.get("totalInterestPaid").decimalValue();

        // 500,000 at 20% is 100,000 of interest on a running loan.
        importLoan(admin, Map.of(
                "bvn", bvn,
                "fullName", "Chioma Grace Adeyemi",
                "principal", 500000,
                "tenureMonths", 4,
                "flatRate", 0.20,
                "purpose", "Stock",
                "disbursedAt", Instant.now().minus(5, ChronoUnit.DAYS).toString(),
                "amountRepaid", 0));

        assertThat(getJson("/api/v1/loans", customer)).hasSize(1);

        JsonNode book = getJson("/api/v1/admin/overview", admin).get("book");
        assertThat(book.get("totalInterestCharged").decimalValue())
                .isEqualByComparingTo(before.add(new BigDecimal("100000.00")));

        // Interest paid out is the savers' side. Lending does not touch it: the
        // two figures are opposite sides of the rate card, not one number.
        assertThat(book.get("totalInterestPaid").decimalValue())
                .isEqualByComparingTo(paidOutBefore);
    }

    @Test
    @DisplayName("figures that contradict each other are refused, and a waiting entry can be removed")
    void refusedAndRemoved() throws Exception {
        SignUpFlow.Session admin = owner();
        String bvn = SignUpFlow.nextIdentityNumber(36);
        Map<String, Object> good = Map.of(
                "bvn", bvn,
                "fullName", "Chioma Grace Adeyemi",
                "principal", 100000,
                "tenureMonths", 4,
                "flatRate", 0.10,
                "purpose", "Stock",
                "disbursedAt", Instant.now().minus(10, ChronoUnit.DAYS).toString());

        // More repaid than was ever owed.
        postExpecting(admin, with(good, "amountRepaid", 110001), 400);
        // A rate typed as a percentage rather than a fraction.
        postExpecting(admin, with(good, "flatRate", 10), 400);
        // A fee that swallows the loan.
        postExpecting(admin, with(good, "processingFee", 100000), 400);
        // Money sent tomorrow.
        postExpecting(admin, with(good, "disbursedAt", Instant.now().plus(1, ChronoUnit.DAYS).toString()), 400);

        JsonNode entered = importLoan(admin, good);
        String id = entered.get("id").asText();

        mvc.perform(delete("/api/v1/admin/loans/imports/" + id).header("Authorization", admin.bearer()))
                .andExpect(status().isOk());
        assertThat(listJson(admin, "").toString()).doesNotContain(id);

        JsonNode audit = getJson("/api/v1/admin/audit?size=10", admin);
        assertThat(audit.get("items").toString())
                .contains("Paper loan entered")
                .contains("Paper loan removed before claim");

        // Somebody who signs up with that BVN now finds nothing waiting.
        SignUpFlow.Session customer = flow.signUp(SignUpFlow.freshEmail("nothing"), bvn);
        assertThat(getJson("/api/v1/loans", customer)).isEmpty();

        // A customer cannot reach the admin's endpoint.
        postExpecting(customer, good, 403);
    }

    // -- Plumbing -----------------------------------------------------------

    private JsonNode importLoan(SignUpFlow.Session admin, Map<String, Object> body) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/admin/loans/imports")
                        .header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void postExpecting(SignUpFlow.Session session, Map<String, Object> body, int status)
            throws Exception {
        assertThat(mvc.perform(post("/api/v1/admin/loans/imports")
                        .header("Authorization", session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn().getResponse().getStatus())
                .as("entering %s", body)
                .isEqualTo(status);
    }

    private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> copy = new java.util.HashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    private JsonNode listJson(SignUpFlow.Session admin, String query) throws Exception {
        return getJson("/api/v1/admin/loans/imports" + query, admin);
    }

    private JsonNode getJson(String path, SignUpFlow.Session session) throws Exception {
        return json.readTree(mvc.perform(get(path).header("Authorization", session.bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
