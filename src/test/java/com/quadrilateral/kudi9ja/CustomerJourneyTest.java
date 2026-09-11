package com.quadrilateral.kudi9ja;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quadrilateral.kudi9ja.domain.admin.AdminRole;
import com.quadrilateral.kudi9ja.domain.admin.AdminUser;
import com.quadrilateral.kudi9ja.domain.admin.AdminUserRepository;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.support.BorrowFlow;
import com.quadrilateral.kudi9ja.support.CapturingMailer;
import com.quadrilateral.kudi9ja.support.SignUpFlow;
import java.math.BigDecimal;
import java.util.UUID;
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

/**
 * One customer, from signing up to borrowing, over the real HTTP surface.
 *
 * <p>The point of running this end to end rather than as unit tests is that
 * most of the rules being checked live in the joins: money that appears only
 * after an admin confirms it, a balance that must be reconstructible from a
 * ledger, a withdrawal that debits before it is approved. None of those can be
 * seen from inside a single service.
 *
 * <p>The scheduled jobs are switched off here. A maturity sweep firing halfway
 * through a test would be a race, and each sweep is exercised directly where it
 * is tested.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(CapturingMailer.Config.class)
@TestPropertySource(properties = {
        "kudi9ja.jobs.enabled=false",
        "kudi9ja.bootstrap.owner-emails=owner@kudi9ja.test"
})
@DisplayName("A customer's journey, over HTTP")
class CustomerJourneyTest {

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
    private String customerEmail;

    @BeforeEach
    void freshMailbox() {
        mailer.clear();
        flow = new SignUpFlow(mvc, json, mailer);
        borrowing = new BorrowFlow(mvc, json);
        customerEmail = SignUpFlow.freshEmail("chioma");
    }

    /**
     * The bootstrap owner.
     *
     * <p>Named in configuration rather than being whoever signed up first — on
     * a server, the first account is a stranger. Created once and signed into
     * thereafter, because these tests share a database.
     */
    private SignUpFlow.Session owner() throws Exception {
        String ownerEmail = "owner@kudi9ja.test";
        if (users.findByEmailIgnoreCase(ownerEmail).isPresent()) {
            return flow.signIn(ownerEmail);
        }
        SignUpFlow.Session session = flow.signUp(ownerEmail);
        assertThat(admins.findByEmailIgnoreCase(ownerEmail))
                .as("the configured bootstrap owner should have been granted the panel")
                .isPresent()
                .get()
                .extracting(AdminUser::getRole)
                .isEqualTo(AdminRole.OWNER);
        return session;
    }

    // ── Signing up ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("the eight steps open an account, and the account starts at zero")
    void signsUpAndStartsAtZero() throws Exception {
        SignUpFlow.Session session = flow.signUp(customerEmail);

        // Nothing is given away. A new account starts at zero with an empty
        // ledger — no sign-up bonus, no free credit.
        JsonNode wallet = getJson("/api/v1/wallet", session);
        assertThat(wallet.get("balance").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);

        JsonNode transactions = getJson("/api/v1/transactions", session);
        assertThat(transactions.get("items")).isEmpty();

        JsonNode profile = getJson("/api/v1/me", session);
        assertThat(profile.get("customerRef").asText()).startsWith("K9-");
        assertThat(profile.get("emailVerified").asBoolean()).isTrue();
        // Never returned, anywhere.
        assertThat(profile.has("bvn")).isFalse();
        assertThat(profile.has("passwordHash")).isFalse();
        assertThat(profile.has("securityAnswer")).isFalse();
        // Masked: asterisks, then the last four digits. The length is the
        // BVN's own, so the shape of the field does not itself say how long
        // the number was.
        String maskedBvn = profile.get("bvnLast4").asText();
        assertThat(maskedBvn).startsWith("*").matches("\\*+\\d{4}");
    }

    @Test
    @DisplayName("a step cannot be skipped")
    void stepsAreGated() throws Exception {
        String draftId = flow.startSignup(customerEmail);

        // Step three, without having verified the email at step two.
        mvc.perform(post("/api/v1/auth/signup/{id}/identity", draftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bvn":"22345678901","nin":"12345678901",
                                 "address":"14 Adeola Odeku Street","state":"Lagos"}
                                """))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("somebody under eighteen is refused")
    void refusesUnderEighteen() throws Exception {
        mvc.perform(post("/api/v1/auth/signup/personal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "fullName", "Too Young",
                                "email", "young@example.com",
                                "phone", "08031234599",
                                "dateOfBirth", java.time.LocalDate.now().minusYears(16).toString(),
                                "gender", "Female"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNDERAGE"));
    }

    // ── Money in ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a claim credits nothing until an admin confirms it")
    void payInNeedsAnAdmin() throws Exception {
        SignUpFlow.Session customer = flow.signUp(customerEmail);
        SignUpFlow.Session admin = owner();

        JsonNode instruction = getJson("/api/v1/payins/reference", customer);
        String reference = instruction.get("reference").asText();
        assertThat(reference).startsWith("K9-").contains("-");

        // Looking at the screen again shows the same reference. A customer who
        // opened the page twice has not made two payments.
        assertThat(getJson("/api/v1/payins/reference", customer).get("reference").asText())
                .isEqualTo(reference);

        // Copying it is what mints the next one — every payment gets its own,
        // so two transfers of the same amount on the same day can be told apart.
        JsonNode next = postJson("/api/v1/payins/reference/copied", customer, null);
        assertThat(next.get("reference").asText()).isNotEqualTo(reference);

        JsonNode claim = submitClaim(customer, reference, "250000");
        assertThat(claim.get("status").asText()).isEqualTo("PENDING");

        // Nothing has moved.
        assertThat(balanceOf(customer)).isEqualByComparingTo(BigDecimal.ZERO);

        // Now the admin confirms it against the statement.
        String claimId = claim.get("id").asText();
        postJson("/api/v1/admin/payins/" + claimId + "/confirm", admin, "{}");

        assertThat(balanceOf(customer)).isEqualByComparingTo("250000.00");
    }

    @Test
    @DisplayName("a claim cannot be submitted without a receipt")
    void receiptIsMandatory() throws Exception {
        SignUpFlow.Session customer = flow.signUp(customerEmail);
        String reference = postJson("/api/v1/payins/reference", customer, null)
                .get("reference").asText();

        mvc.perform(multipart("/api/v1/payins")
                        .param("amount", "50000")
                        .param("reference", reference)
                        .param("senderName", "Chioma Grace Adeyemi")
                        .header("Authorization", "Bearer " + customer.accessToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RECEIPT_REQUIRED"));
    }

    // ── Money out ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a withdrawal debits at request, and declining refunds in full")
    void withdrawalDebitsAtRequestAndRefundsOnDecline() throws Exception {
        SignUpFlow.Session customer = flow.signUp(customerEmail);
        SignUpFlow.Session admin = owner();
        fundWallet(customer, admin, "300000");

        JsonNode withdrawal = postJson("/api/v1/withdrawals", customer, """
                {"amount": 100000, "pin": "5271"}
                """);

        // Debited immediately, so the same money cannot be spent twice while it
        // is under review.
        assertThat(balanceOf(customer)).isEqualByComparingTo("200000.00");
        assertThat(withdrawal.get("status").asText()).isEqualTo("PENDING");

        String id = withdrawal.get("id").asText();
        postJson("/api/v1/admin/withdrawals/" + id + "/decline", admin, """
                {"reason": "The account name did not match."}
                """);

        // Refunded in full. No fee, no partial return.
        assertThat(balanceOf(customer)).isEqualByComparingTo("300000.00");

        // And the ledger is append-only: the original debit is still there,
        // reversed rather than deleted.
        JsonNode ledger = getJson("/api/v1/transactions?filter=WITHDRAWALS", customer);
        assertThat(ledger.get("items")).isNotEmpty();
    }

    /**
     * The withdrawal screen prefills the bank and account from the profile and
     * lets them be edited. If the server simply ignored an edited value, the app
     * would show a confirmation naming one bank and the money would go to
     * another — so a named destination that does not match is refused, with the
     * reason and the real one.
     */
    @Test
    @DisplayName("a withdrawal naming a different destination is refused, not redirected")
    void refusesADestinationThatIsNotThePayoutAccount() throws Exception {
        SignUpFlow.Session customer = flow.signUp(customerEmail);
        SignUpFlow.Session admin = owner();
        fundWallet(customer, admin, "100000");

        mvc.perform(post("/api/v1/withdrawals")
                        .header("Authorization", "Bearer " + customer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 5000, "pin": "%s",
                                 "bank": "Access Bank", "accountNumber": "9999999991"}
                                """.formatted(SignUpFlow.PIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("Zenith Bank")))
                .andExpect(jsonPath("$.details.changeAt").value("PATCH /api/v1/me/payout"));

        // Nothing moved, so the customer can correct it and try again.
        assertThat(balanceOf(customer)).isEqualByComparingTo("100000.00");

        // Naming the real payout account goes through.
        postJson("/api/v1/withdrawals", customer, """
                {"amount": 5000, "pin": "%s",
                 "bank": "Zenith Bank", "accountNumber": "0123456781"}
                """.formatted(SignUpFlow.PIN));

        assertThat(balanceOf(customer)).isEqualByComparingTo("95000.00");
    }

    @Test
    @DisplayName("a withdrawal beyond the balance is refused")
    void cannotOverdraw() throws Exception {
        SignUpFlow.Session customer = flow.signUp(customerEmail);
        SignUpFlow.Session admin = owner();
        fundWallet(customer, admin, "10000");

        mvc.perform(post("/api/v1/withdrawals")
                        .header("Authorization", "Bearer " + customer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 50000, "pin": "5271"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    @DisplayName("the wrong PIN moves no money")
    void wrongPinIsRefused() throws Exception {
        SignUpFlow.Session customer = flow.signUp(customerEmail);
        SignUpFlow.Session admin = owner();
        fundWallet(customer, admin, "100000");

        mvc.perform(post("/api/v1/withdrawals")
                        .header("Authorization", "Bearer " + customer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 5000, "pin": "9182"}
                                """))
                .andExpect(status().isForbidden());

        assertThat(balanceOf(customer)).isEqualByComparingTo("100000.00");
    }

    // ── Savings ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a Fixed plan pays its return upfront and cannot be broken")
    void fixedPaysUpfrontAndCannotBreak() throws Exception {
        SignUpFlow.Session customer = flow.signUp(customerEmail);
        SignUpFlow.Session admin = owner();
        fundWallet(customer, admin, "200000");

        JsonNode plan = postJson("/api/v1/savings/plans/fixed", customer, """
                {"title": "Rainy day", "principal": 100000, "days": 365, "pin": "5271"}
                """);

        // ₦100,000 locked, ₦17,000 back in the wallet the same moment.
        assertThat(balanceOf(customer)).isEqualByComparingTo("117000.00");
        assertThat(plan.get("interestPaid").decimalValue()).isEqualByComparingTo("17000.00");

        // And it cannot be broken, with the reason rather than silence.
        mvc.perform(post("/api/v1/savings/plans/{id}/break", plan.get("id").asText())
                        .header("Authorization", "Bearer " + customer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"pin": "5271"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PLAN_CANNOT_BREAK"));
    }

    @Test
    @DisplayName("breaking a Target plan returns every naira and forfeits only the bonus")
    void targetBreakReturnsPrincipalInFull() throws Exception {
        SignUpFlow.Session customer = flow.signUp(customerEmail);
        SignUpFlow.Session admin = owner();
        fundWallet(customer, admin, "500000");

        JsonNode plan = postJson("/api/v1/savings/plans/target", customer, """
                {"title": "New laptop", "goal": 300000, "frequency": "MONTHLY",
                 "months": 6, "emoji": "💻", "pin": "5271"}
                """);
        String planId = plan.get("id").asText();

        postJson("/api/v1/savings/plans/" + planId + "/topup", customer, """
                {"amount": 120000, "pin": "5271"}
                """);
        BigDecimal afterSaving = balanceOf(customer);

        JsonNode released = postJson("/api/v1/savings/plans/" + planId + "/break", customer, """
                {"pin": "5271"}
                """);

        // Every naira saved comes back. No break fee, no cut of the principal.
        assertThat(released.get("principalReturned").decimalValue())
                .isEqualByComparingTo("120000.00");
        // And the bonus is forfeited, which is the whole incentive the plan
        // was built on.
        assertThat(released.get("bonusPaid").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balanceOf(customer)).isEqualByComparingTo(afterSaving.add(new BigDecimal("120000")));
    }

    // ── Borrowing ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a loan books gross and nets the fee, so both legs show in the ledger")
    void loanNetsTheFeeVisibly() throws Exception {
        SignUpFlow.Session customer = flow.signUp(customerEmail);
        SignUpFlow.Session admin = owner();
        // Savings build the offer up to where ₦200,000 is inside it.
        fundWallet(customer, admin, "1000000");
        postJson("/api/v1/savings/plans/fixed", customer, """
                {"title": "Collateral", "principal": 500000, "days": 365, "pin": "5271"}
                """);

        JsonNode quote = getJson("/api/v1/loans/quote?amount=200000&months=3", customer);
        assertThat(quote.get("processingFee").decimalValue()).isEqualByComparingTo("5000.00");
        assertThat(quote.get("totalInterest").decimalValue()).isEqualByComparingTo("50000.00");
        assertThat(quote.get("totalRepayable").decimalValue()).isEqualByComparingTo("250000.00");

        BigDecimal before = balanceOf(customer);
        JsonNode application = borrowing.apply(customer, "200000", 3, "Stock for the shop");
        // Submitting moves nothing. The money arrives when a person approves.
        assertThat(balanceOf(customer)).isEqualByComparingTo(before);
        borrowing.approve(admin, application.get("id").asText());
        JsonNode loan = getJson("/api/v1/loans", customer).get(0);

        // ₦200,000 in, ₦5,000 straight out. The fee never joins the debt.
        assertThat(balanceOf(customer)).isEqualByComparingTo(before.add(new BigDecimal("195000")));
        assertThat(loan.get("principal").decimalValue()).isEqualByComparingTo("200000.00");
        assertThat(loan.get("outstanding").decimalValue()).isEqualByComparingTo("250000.00");

        JsonNode ledger = getJson("/api/v1/transactions?filter=LOANS", customer);
        assertThat(ledger.get("items")).isNotEmpty();
        JsonNode fees = getJson("/api/v1/transactions?filter=FEES", customer);
        assertThat(fees.get("items")).isNotEmpty();
    }

    @Test
    @DisplayName("the client is never asked to price a loan")
    void quoteComesFromTheServer() throws Exception {
        SignUpFlow.Session customer = flow.signUp(customerEmail);

        JsonNode quote = getJson("/api/v1/loans/quote?amount=1000000&months=12", customer);

        // 78% flat at twelve months, on the whole principal.
        assertThat(quote.get("totalInterest").decimalValue()).isEqualByComparingTo("780000.00");
        assertThat(quote.get("totalRepayable").decimalValue()).isEqualByComparingTo("1780000.00");
        // 1% of the whole principal above the threshold.
        assertThat(quote.get("processingFee").decimalValue()).isEqualByComparingTo("10000.00");
        assertThat(quote.get("netDisbursed").decimalValue()).isEqualByComparingTo("990000.00");
    }

    // ── The ledger ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("the balance is reconstructible from the ledger after a busy day")
    void balanceAlwaysMatchesTheLedger() throws Exception {
        SignUpFlow.Session customer = flow.signUp(customerEmail);
        SignUpFlow.Session admin = owner();

        fundWallet(customer, admin, "800000");
        postJson("/api/v1/savings/plans/fixed", customer, """
                {"title": "Locked", "principal": 200000, "days": 90, "pin": "5271"}
                """);
        postJson("/api/v1/withdrawals", customer, """
                {"amount": 50000, "pin": "5271"}
                """);
        borrowing.borrow(customer, admin, "100000", 6, "Working capital");

        JsonNode reconciliation = getJson("/api/v1/wallet/reconciliation", customer);

        assertThat(reconciliation.get("balanced").asBoolean())
                .as("stored balance %s vs ledger %s",
                        reconciliation.get("storedBalance"), reconciliation.get("ledgerBalance"))
                .isTrue();
        assertThat(reconciliation.get("difference").decimalValue())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── Admin access ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a customer without a grant cannot reach the panel")
    void panelNeedsAGrant() throws Exception {
        SignUpFlow.Session customer = flow.signUp(customerEmail);

        mvc.perform(get("/api/v1/admin/overview")
                        .header("Authorization", "Bearer " + customer.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an owner cannot revoke their own access")
    void selfLockoutIsImpossible() throws Exception {
        SignUpFlow.Session owner = owner();
        AdminUser grant = admins.findByEmailIgnoreCase("owner@kudi9ja.test").orElseThrow();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/admin/team/{id}/role", grant.getId())
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "VIEWER"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_LOCKOUT"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/admin/team/{id}", grant.getId())
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a viewer may look but may not confirm a payment")
    void viewersCannotMoveMoney() throws Exception {
        SignUpFlow.Session owner = owner();
        SignUpFlow.Session customer = flow.signUp(customerEmail);

        // The owner makes the customer a viewer.
        postJson("/api/v1/admin/team", owner, json.writeValueAsString(java.util.Map.of(
                "email", customerEmail, "role", "VIEWER")));

        // A viewer can read the overview.
        SignUpFlow.Session viewer = flow.signIn(customerEmail);
        getJson("/api/v1/admin/overview", viewer);

        // But not confirm anything.
        String reference = postJson("/api/v1/payins/reference", viewer, null)
                .get("reference").asText();
        String claimId = submitClaim(viewer, reference, "10000").get("id").asText();

        mvc.perform(post("/api/v1/admin/payins/{id}/confirm", claimId)
                        .header("Authorization", "Bearer " + viewer.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a suspended grant stops working on the next request")
    void accessIsRecheckedEveryRequest() throws Exception {
        SignUpFlow.Session owner = owner();
        SignUpFlow.Session customer = flow.signUp(customerEmail);

        postJson("/api/v1/admin/team", owner, json.writeValueAsString(java.util.Map.of(
                "email", customerEmail, "role", "ADMIN")));

        SignUpFlow.Session newAdmin = flow.signIn(customerEmail);
        getJson("/api/v1/admin/overview", newAdmin);

        AdminUser grant = admins.findByEmailIgnoreCase(customerEmail).orElseThrow();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/admin/team/{id}/active", grant.getId())
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"active": false, "reason": "Left the company"}
                                """))
                .andExpect(status().isOk());

        // Same token, still unexpired. The grant is gone, so the panel is gone.
        mvc.perform(get("/api/v1/admin/overview")
                        .header("Authorization", "Bearer " + newAdmin.accessToken()))
                .andExpect(status().isForbidden());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** The whole money-in route: reference, claim with a receipt, admin confirms. */
    private void fundWallet(SignUpFlow.Session customer, SignUpFlow.Session admin, String amount) throws Exception {
        String reference = postJson("/api/v1/payins/reference", customer, null)
                .get("reference").asText();
        String claimId = submitClaim(customer, reference, amount).get("id").asText();
        postJson("/api/v1/admin/payins/" + claimId + "/confirm", admin, "{}");
    }

    private JsonNode submitClaim(SignUpFlow.Session session, String reference, String amount) throws Exception {
        MockMultipartFile receipt = new MockMultipartFile(
                "receipt",
                "transfer.png",
                MediaType.IMAGE_PNG_VALUE,
                // Not a real PNG, and it does not need to be — nothing decodes
                // it, an admin looks at it.
                "receipt-bytes".getBytes());

        MvcResult result = mvc.perform(multipart("/api/v1/payins")
                        .file(receipt)
                        .param("amount", amount)
                        .param("reference", reference)
                        .param("senderName", "Chioma Grace Adeyemi")
                        .param("senderBank", "Zenith Bank")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isCreated())
                .andReturn();

        return read(result);
    }

    private BigDecimal balanceOf(SignUpFlow.Session session) throws Exception {
        return getJson("/api/v1/wallet", session).get("balance").decimalValue();
    }

    // ── What the app is told about the panel ───────────────────────────────

    @Test
    @DisplayName("signing in says what the panel allows, not merely that it exists")
    void signInCarriesTheRole() throws Exception {
        owner();

        MvcResult result = mvc.perform(post("/api/v1/auth/signin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "email", "owner@kudi9ja.test",
                                "password", SignUpFlow.PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode profile = read(result).get("profile");
        assertThat(read(result).get("admin").asBoolean()).isTrue();
        // The flag on its own was all the app got, so it assumed the narrowest
        // role until the panel had been opened once and told an owner on their
        // own dashboard that they were signed in as a Viewer.
        assertThat(profile.get("adminRole").asText()).isEqualTo("OWNER");
    }

    @Test
    @DisplayName("an account with no grant is given no role, which is not the same as Viewer")
    void aCustomerHasNoRole() throws Exception {
        SignUpFlow.Session customer = flow.signUp(customerEmail);

        JsonNode profile = getJson("/api/v1/me", customer);
        assertThat(profile.get("admin").asBoolean()).isFalse();
        // Absent rather than null: nulls are dropped from every response here.
        // Either way it is not the string "VIEWER", which is the distinction
        // that matters — no grant at all is not the narrowest grant there is.
        assertThat(profile.hasNonNull("adminRole")).isFalse();
    }

    @Test
    @DisplayName("the audit log answers with every filter set at once")
    void auditSearchAcceptsEveryFilter() throws Exception {
        SignUpFlow.Session admin = owner();

        // Every optional filter supplied together, which is the shape that took
        // the whole panel's history out: a nullable instant compared against
        // null gives Postgres a parameter with no type to infer, and it refuses
        // the statement rather than the parameter. H2 infers it, so this guards
        // the query still runs and returns — the type itself has to be said out
        // loud in the query for the deployed database to accept it.
        JsonNode page = getJson("/api/v1/admin/audit"
                + "?category=CUSTOMER"
                + "&from=2020-01-01T00:00:00Z"
                + "&to=2100-01-01T00:00:00Z"
                + "&q=account"
                + "&size=5", admin);

        assertThat(page.get("items")).isNotNull();
    }

    @Test
    @DisplayName("the audit log answers with no filters at all")
    void auditSearchAcceptsNoFilters() throws Exception {
        SignUpFlow.Session admin = owner();

        JsonNode page = getJson("/api/v1/admin/audit?size=5", admin);

        assertThat(page.get("items")).isNotNull();
    }

    private JsonNode getJson(String path, SignUpFlow.Session session) throws Exception {
        return read(mvc.perform(get(path)
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode postJson(String path, SignUpFlow.Session session, String body) throws Exception {
        var request = post(path).header("Authorization", "Bearer " + session.accessToken());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return read(mvc.perform(request).andExpect(status().is2xxSuccessful()).andReturn());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
