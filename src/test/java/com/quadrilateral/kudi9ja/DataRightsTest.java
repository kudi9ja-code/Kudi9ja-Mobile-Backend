package com.quadrilateral.kudi9ja;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quadrilateral.kudi9ja.domain.compliance.DataRightsService;
import com.quadrilateral.kudi9ja.domain.user.AccountStatus;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.domain.wallet.WalletTransactionRepository;
import com.quadrilateral.kudi9ja.support.BorrowFlow;
import com.quadrilateral.kudi9ja.support.CapturingMailer;
import com.quadrilateral.kudi9ja.support.SignUpFlow;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * What a customer may do with their own data, and what happens when they leave.
 *
 * <p>These are obligations under the NDPA 2023 and the Privacy Policy rather
 * than features, which is exactly why they need tests: nobody files a bug
 * saying the export was incomplete, and nothing goes wrong on the day an
 * erasure quietly fails to happen.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(CapturingMailer.Config.class)
@TestPropertySource(properties = {
        "kudi9ja.jobs.enabled=false",
        "kudi9ja.bootstrap.owner-emails=owner@kudi9ja.test"
})
@DisplayName("Data rights: access, portability and erasure")
class DataRightsTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private CapturingMailer mailer;

    @Autowired
    private UserRepository users;

    @Autowired
    private WalletTransactionRepository transactions;

    @Autowired
    private DataRightsService dataRights;

    private SignUpFlow flow;
    private BorrowFlow borrowing;
    private String email;

    @BeforeEach
    void setUp() {
        mailer.clear();
        flow = new SignUpFlow(mvc, json, mailer);
        borrowing = new BorrowFlow(mvc, json);
        email = SignUpFlow.freshEmail("subject");
    }

    // ── Access and portability ─────────────────────────────────────────────

    @Nested
    @DisplayName("The export")
    class Export {

        @Test
        @DisplayName("answers the subject access request in full, and says what the rights are")
        void isCompleteAndExplainsItself() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);

            JsonNode export = exportFor(customer);
            JsonNode about = export.get("about");

            assertThat(about.get("subjectId").asText()).isEqualTo(customer.userId().toString());
            assertThat(about.get("controllerLegalName").asText())
                    .isEqualTo("Quadrilateral Technologies Limited");
            assertThat(about.get("controllerRcNumber").asText()).isEqualTo("RC 1657731");
            assertThat(about.get("dataProtectionContact").asText()).isEqualTo("privacy@kudi9ja.com");
            assertThat(about.get("regulator").asText()).contains("Nigeria Data Protection Commission");

            // The rights are listed, including the one that is easiest to
            // forget: a person can demand that a person looks at an automated
            // decision again.
            assertThat(about.get("yourRights")).hasSize(8);
            assertThat(about.get("yourRights").toString())
                    .contains("Portability")
                    .contains("Human review");

            // And the retention position is stated rather than left to be
            // discovered on the way out.
            assertThat(about.get("retentionNote").asText())
                    .contains("5 years")
                    .contains("deletion request cannot override");

            // Every section is present, even where it is empty.
            assertThat(export.has("profile")).isTrue();
            assertThat(export.has("wallet")).isTrue();
            assertThat(export.has("transactions")).isTrue();
            assertThat(export.has("savingsPlans")).isTrue();
            assertThat(export.has("loans")).isTrue();
            assertThat(export.has("legalAcceptances")).isTrue();
        }

        @Test
        @DisplayName("carries no hash, no security answer and no full BVN or NIN")
        void carriesNoCredentials() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);

            MvcResult result = mvc.perform(get("/api/v1/me/data-export")
                            .header(HttpHeaders.AUTHORIZATION, customer.bearer()))
                    .andExpect(status().isOk())
                    .andReturn();

            String raw = result.getResponse().getContentAsString();

            // Nothing that could be attacked offline, and nothing that could be
            // replayed.
            assertThat(raw)
                    .doesNotContain("passwordHash")
                    .doesNotContain("passcodeHash")
                    .doesNotContain("pinHash")
                    .doesNotContain("securityAnswer")
                    .doesNotContain(SignUpFlow.PASSWORD)
                    .doesNotContain(SignUpFlow.PIN);

            JsonNode profile = json.readTree(raw).get("profile");

            // The security question, which tells the customer what we ask; not
            // the answer, which is a credential.
            assertThat(profile.get("securityQuestion").asText()).isNotBlank();

            // Masked exactly as everywhere else in the API.
            assertThat(profile.get("bvnLast4").asText()).matches("\\*+\\d{4}");
            assertThat(profile.get("ninLast4").asText()).matches("\\*+\\d{4}");

            // But the fact that we hold them, verified, is information the
            // customer is entitled to.
            assertThat(profile.get("bvnVerified").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("includes the ledger, the plans and what an automated decision rested on")
        void includesTheRecordBehindADecision() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);
            SignUpFlow.Session admin = owner();

            fundWallet(customer, admin, "600000");
            postJson(customer, "/api/v1/savings/plans/fixed", """
                    {"title": "Rainy day", "principal": 200000, "days": 365, "pin": "%s"}
                    """.formatted(SignUpFlow.PIN));
            borrowing.borrow(customer, admin, "100000", 6, "Stock");

            JsonNode export = exportFor(customer);

            assertThat(export.get("transactions")).isNotEmpty();
            assertThat(export.get("payIns")).hasSize(1);
            assertThat(export.get("savingsPlans")).hasSize(1);
            assertThat(export.get("loans")).hasSize(1);

            // The plan carries the rate it was opened on, not today's.
            assertThat(export.get("savingsPlans").get(0).get("annualRate").decimalValue())
                    .isEqualByComparingTo("0.170000");

            // The loan carries the score it was decided on, without which the
            // right to a human review cannot be used.
            JsonNode loan = export.get("loans").get(0);
            assertThat(loan.get("scoreAtDecision").isNull()).isFalse();
            assertThat(loan.get("flatRate").decimalValue()).isEqualByComparingTo("0.450000");

            // The acceptance record the Terms rely on as evidence.
            assertThat(export.get("legalAcceptances")).hasSize(3);
            assertThat(export.get("legalAcceptances").get(0).get("device").asText())
                    .isEqualTo("Integration test");
        }

        @Test
        @DisplayName("arrives as a file the customer can keep")
        void isADownload() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);

            mvc.perform(get("/api/v1/me/data-export")
                            .header(HttpHeaders.AUTHORIZATION, customer.bearer()))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .header().string(HttpHeaders.CONTENT_DISPOSITION,
                                    org.hamcrest.Matchers.containsString("attachment")))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .header().string(HttpHeaders.CACHE_CONTROL,
                                    org.hamcrest.Matchers.containsString("no-store")));
        }

        @Test
        @DisplayName("cannot be fetched without a session")
        void needsASession() throws Exception {
            mvc.perform(get("/api/v1/me/data-export"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── Erasure ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Closing an account")
    class Closure {

        @Test
        @DisplayName("is refused while the wallet still holds money")
        void refusedWithABalance() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);
            SignUpFlow.Session admin = owner();
            fundWallet(customer, admin, "50000");

            JsonNode eligibility = getJson(customer, "/api/v1/me/closure");

            assertThat(eligibility.get("canClose").asBoolean()).isFalse();
            assertThat(eligibility.get("walletBalance").decimalValue())
                    .isEqualByComparingTo("50000.00");
            // Phrased as something the customer can act on, not as a refusal.
            assertThat(eligibility.get("blockers").toString())
                    .contains("withdraw it to your bank account first");

            attemptClose(customer).andExpect(status().isConflict());
        }

        @Test
        @DisplayName("is refused while a loan is outstanding")
        void refusedWithAnOpenLoan() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);
            SignUpFlow.Session admin = owner();

            fundWallet(customer, admin, "600000");
            postJson(customer, "/api/v1/savings/plans/fixed", """
                    {"title": "Collateral", "principal": 300000, "days": 365, "pin": "%s"}
                    """.formatted(SignUpFlow.PIN));
            borrowing.borrow(customer, admin, "100000", 6, "Stock");

            JsonNode eligibility = getJson(customer, "/api/v1/me/closure");

            assertThat(eligibility.get("canClose").asBoolean()).isFalse();
            assertThat(eligibility.get("blockers").toString()).contains("still owe");
            assertThat(eligibility.get("outstandingOnLoans").decimalValue())
                    .isEqualByComparingTo("145000.00");
            // The locked savings are named too, so the customer sees everything
            // in the way at once rather than one blocker at a time.
            assertThat(eligibility.get("lockedInSavings").decimalValue())
                    .isEqualByComparingTo("300000.00");
        }

        @Test
        @DisplayName("needs the right password as well as the code")
        void needsThePassword() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);
            postJson(customer, "/api/v1/me/closure/code", null);

            mvc.perform(delete("/api/v1/account")
                            .header(HttpHeaders.AUTHORIZATION, customer.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(java.util.Map.of(
                                    "code", mailer.requireCodeFor(email),
                                    "password", "not-the-right-password"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));

            assertThat(users.findById(customer.userId()).orElseThrow().getAccountStatus())
                    .isEqualTo(AccountStatus.ACTIVE);
        }

        @Test
        @DisplayName("needs a code, and refuses a wrong one")
        void needsTheCode() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);
            postJson(customer, "/api/v1/me/closure/code", null);

            mvc.perform(delete("/api/v1/account")
                            .header(HttpHeaders.AUTHORIZATION, customer.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code": "000000", "password": "%s"}
                                    """.formatted(SignUpFlow.PASSWORD)))
                    .andExpect(status().isBadRequest());

            assertThat(users.findById(customer.userId()).orElseThrow().getAccountStatus())
                    .isEqualTo(AccountStatus.ACTIVE);
        }

        /**
         * The heart of it: what closing actually does, and what it does not.
         */
        @Test
        @DisplayName("destroys the credentials, keeps the record, and says which is which")
        void redactsRatherThanDeletes() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);
            long ledgerRowsBefore = transactions.countByUserId(customer.userId());

            JsonNode closed = json.readTree(
                    attemptClose(customer).andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString());

            assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
            assertThat(closed.get("retainUntil").isNull()).isFalse();
            // Both halves are reported, in the customer's own words.
            assertThat(closed.get("redacted").toString()).contains("transaction PIN");
            assertThat(closed.get("retained").toString()).contains("BVN");
            assertThat(closed.get("message").asText())
                    .contains("anti-money-laundering rules require it");

            User after = users.findById(customer.userId()).orElseThrow();

            assertThat(after.getAccountStatus()).isEqualTo(AccountStatus.CLOSED);
            assertThat(after.getClosedAt()).isNotNull();

            // Retained for five years, not for ever and not for no time at all.
            assertThat(after.getRetainUntil())
                    .isAfter(Instant.now().plus(4 * 365L, ChronoUnit.DAYS))
                    .isBefore(Instant.now().plus(6 * 365L, ChronoUnit.DAYS));

            // Credentials gone.
            assertThat(after.getPasscodeHash()).isNull();
            assertThat(after.getPinHash()).isNull();
            assertThat(after.getSecurityAnswerHash()).isNull();
            assertThat(after.getSecurityQuestion()).isNull();

            // Identity and ledger kept, because the AML rules require them.
            assertThat(after.getBvn()).isNotNull();
            assertThat(after.getFullName()).isEqualTo("Chioma Grace Adeyemi");
            assertThat(transactions.countByUserId(customer.userId())).isEqualTo(ledgerRowsBefore);
        }

        @Test
        @DisplayName("signs the customer out everywhere and refuses them back in")
        void endsEveryWayBackIn() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);
            String stillUnexpiredToken = customer.accessToken();

            attemptClose(customer).andExpect(status().isOk());

            // The old access token has not expired, but the session it names is
            // revoked and the account cannot sign in.
            mvc.perform(get("/api/v1/me")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + stillUnexpiredToken))
                    .andExpect(status().isUnauthorized());

            // And the password no longer opens anything — it was replaced with
            // a value no password hashes to.
            mvc.perform(post("/api/v1/auth/signin")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(java.util.Map.of(
                                    "email", email, "password", SignUpFlow.PASSWORD))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_CLOSED"));
        }

        @Test
        @DisplayName("cannot be done twice")
        void isNotRepeatable() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);
            attemptClose(customer).andExpect(status().isOk());

            // The session is gone, so a second attempt cannot even authenticate.
            mvc.perform(delete("/api/v1/account")
                            .header(HttpHeaders.AUTHORIZATION, customer.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"code": "000000", "password": "%s"}
                                    """.formatted(SignUpFlow.PASSWORD)))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── The other half of the promise ──────────────────────────────────────

    @Nested
    @DisplayName("Erasure when the retention period ends")
    class Erasure {

        @Test
        @DisplayName("leaves a closed account alone until its retention has run out")
        void waitsForTheRetentionPeriod() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);
            attemptClose(customer).andExpect(status().isOk());

            assertThat(dataRights.erase(Instant.now())).isZero();

            assertThat(users.findById(customer.userId()).orElseThrow().getBvn()).isNotNull();
        }

        /**
         * Without this, "retained for five years" quietly means "kept for
         * ever", and a customer who asked to be forgotten never is.
         */
        @Test
        @DisplayName("erases the identity once it has, and leaves the ledger standing")
        void erasesTheIdentityButKeepsTheLedger() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);
            attemptClose(customer).andExpect(status().isOk());

            long ledgerRows = transactions.countByUserId(customer.userId());

            // Wind the clock forward by moving the retention date into the past.
            User closed = users.findById(customer.userId()).orElseThrow();
            closed.setRetainUntil(Instant.now().minus(1, ChronoUnit.DAYS));
            users.save(closed);

            assertThat(dataRights.erase(Instant.now())).isEqualTo(1);

            User erased = users.findById(customer.userId()).orElseThrow();

            assertThat(erased.getBvn()).isNull();
            assertThat(erased.getNin()).isNull();
            assertThat(erased.getPhone()).isNull();
            assertThat(erased.getDateOfBirth()).isNull();
            assertThat(erased.getAddress()).isNull();
            assertThat(erased.getPayoutAccountNumber()).isNull();
            assertThat(erased.getFullName()).isEqualTo("Erased");
            assertThat(erased.getEmail()).doesNotContain("subject");
            assertThat(erased.getRetainUntil()).isNull();

            // The transaction record survives. It is what the AML rules are
            // about, and it no longer names anybody.
            assertThat(transactions.countByUserId(customer.userId())).isEqualTo(ledgerRows);
        }

        @Test
        @DisplayName("does not touch an account that is merely dormant")
        void onlyTouchesClosedAccounts() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);

            User user = users.findById(customer.userId()).orElseThrow();
            user.setAccountStatus(AccountStatus.DORMANT);
            user.setRetainUntil(Instant.now().minus(1, ChronoUnit.DAYS));
            users.save(user);

            assertThat(dataRights.erase(Instant.now())).isZero();
            assertThat(users.findById(customer.userId()).orElseThrow().getBvn()).isNotNull();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions attemptClose(
            SignUpFlow.Session customer) throws Exception {

        postJson(customer, "/api/v1/me/closure/code", null);

        return mvc.perform(delete("/api/v1/account")
                .header(HttpHeaders.AUTHORIZATION, customer.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of(
                        "code", mailer.requireCodeFor(customer.email()),
                        "password", SignUpFlow.PASSWORD,
                        "reason", "Moving to another provider"))));
    }

    private SignUpFlow.Session owner() throws Exception {
        String ownerEmail = "owner@kudi9ja.test";
        return users.findByEmailIgnoreCase(ownerEmail).isPresent()
                ? flow.signIn(ownerEmail)
                : flow.signUp(ownerEmail);
    }

    private void fundWallet(SignUpFlow.Session customer, SignUpFlow.Session admin, String amount)
            throws Exception {

        String reference = postJson(customer, "/api/v1/payins/reference", null)
                .get("reference").asText();

        MockMultipartFile receipt = new MockMultipartFile(
                "receipt", "transfer.png", MediaType.IMAGE_PNG_VALUE, "receipt-bytes".getBytes());

        MvcResult claim = mvc.perform(multipart("/api/v1/payins")
                        .file(receipt)
                        .param("amount", amount)
                        .param("reference", reference)
                        .param("senderName", "Chioma Grace Adeyemi")
                        .param("senderBank", "Zenith Bank")
                        .header(HttpHeaders.AUTHORIZATION, customer.bearer()))
                .andExpect(status().isCreated())
                .andReturn();

        String claimId = json.readTree(claim.getResponse().getContentAsString()).get("id").asText();
        postJson(admin, "/api/v1/admin/payins/" + claimId + "/confirm", "{}");
    }

    private JsonNode exportFor(SignUpFlow.Session customer) throws Exception {
        return getJson(customer, "/api/v1/me/data-export");
    }

    private JsonNode getJson(SignUpFlow.Session session, String path) throws Exception {
        MvcResult result = mvc.perform(get(path)
                        .header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode postJson(SignUpFlow.Session session, String path, String body) throws Exception {
        var request = post(path).header(HttpHeaders.AUTHORIZATION, session.bearer());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        MvcResult result = mvc.perform(request).andExpect(status().is2xxSuccessful()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
}
