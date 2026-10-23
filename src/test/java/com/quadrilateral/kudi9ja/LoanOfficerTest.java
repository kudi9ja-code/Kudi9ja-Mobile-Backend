package com.quadrilateral.kudi9ja;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.support.CapturingMailer;
import com.quadrilateral.kudi9ja.support.SignUpFlow;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Loan officers: every borrower has at least one, may have several, and
 * keeps them when their paper loan attaches to the account they open.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(CapturingMailer.Config.class)
@TestPropertySource(properties = {
        "kudi9ja.jobs.enabled=false",
        "kudi9ja.bootstrap.owner-emails=owner@kudi9ja.test"
})
@DisplayName("Loan officers")
class LoanOfficerTest {

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
    void fresh() {
        mailer.clear();
        flow = new SignUpFlow(mvc, json, mailer);
    }

    @Test
    @DisplayName("a paper loan gets its officers, keeps at least one, and they follow the borrower to their account")
    void lifecycle() throws Exception {
        SignUpFlow.Session owner = owner();
        String secondEmail = SignUpFlow.freshEmail("officer");
        flow.signUp(secondEmail);
        send(owner, post("/api/v1/admin/team"), Map.of("email", secondEmail, "role", "SUPPORT"), 201);

        JsonNode options = send(owner, get("/api/v1/admin/officers"), null, 200);
        String ownerOfficer = idOf(options, "owner@kudi9ja.test");
        String second = idOf(options, secondEmail);

        // Entered with no officer chosen: whoever entered it leads.
        String bvn = SignUpFlow.nextIdentityNumber(61);
        String email = SignUpFlow.freshEmail("borrower");
        String importId = send(owner, post("/api/v1/admin/loans/imports"), borrower(bvn, email), 201).get("id").asText();
        JsonNode onFile = send(owner, get("/api/v1/admin/loans/imports/" + importId + "/officers"), null, 200);
        assertThat(onFile).hasSize(1);
        assertThat(onFile.get(0).get("officerId").asText()).isEqualTo(ownerOfficer);
        assertThat(onFile.get(0).get("lead").asBoolean()).isTrue();

        // The last one cannot be removed.
        send(owner, delete("/api/v1/admin/officers/assignments/" + onFile.get(0).get("id").asText()), null, 409);

        // A second, made lead.
        JsonNode both = send(owner, post("/api/v1/admin/loans/imports/" + importId + "/officers"),
                Map.of("officerIds", List.of(second), "lead", true), 200);
        assertThat(both).hasSize(2);
        assertThat(both.get(0).get("officerId").asText()).isEqualTo(second);
        assertThat(both.get(0).get("lead").asBoolean()).isTrue();

        // The borrower signs up; the loan attaches, and so do both officers.
        SignUpFlow.Session borrower = flow.signUp(email, bvn);
        JsonNode onAccount = send(owner, get("/api/v1/admin/customers/" + borrower.userId() + "/officers"), null, 200);
        assertThat(onAccount).hasSize(2);
        assertThat(onAccount.get(0).get("lead").asBoolean()).isTrue();

        JsonNode mine = send(borrower, get("/api/v1/me/officers"), null, 200);
        assertThat(mine).hasSize(2);
        assertThat(mine.get(0).has("email")).isTrue();

        // Now one may go, and the other stays.
        send(owner, delete("/api/v1/admin/officers/assignments/" + onAccount.get(1).get("id").asText()), null, 204);
        assertThat(send(owner, get("/api/v1/admin/customers/" + borrower.userId() + "/officers"), null, 200)).hasSize(1);
    }

    // -- Plumbing -----------------------------------------------------------

    private static String idOf(JsonNode options, String email) {
        for (JsonNode o : options) {
            if (o.get("email").asText().equalsIgnoreCase(email)) {
                return o.get("id").asText();
            }
        }
        throw new AssertionError(email + " is not offered as an officer: " + options);
    }

    private Map<String, Object> borrower(String bvn, String email) {
        Map<String, Object> body = new HashMap<>();
        body.put("bvn", bvn);
        body.put("fullName", "Chioma Grace Adeyemi");
        body.put("email", email);
        body.put("principal", 150000);
        body.put("tenureMonths", 3);
        body.put("repaymentFrequency", "MONTHLY");
        body.put("flatRate", 0.25);
        body.put("purpose", "Shop restocking");
        body.put("disbursedAt", Instant.now().minus(10, ChronoUnit.DAYS).toString());
        return body;
    }

    private SignUpFlow.Session owner() throws Exception {
        String ownerEmail = "owner@kudi9ja.test";
        if (users.findByEmailIgnoreCase(ownerEmail).isPresent()) {
            return flow.signIn(ownerEmail);
        }
        return flow.signUp(ownerEmail);
    }

    private JsonNode send(SignUpFlow.Session session, MockHttpServletRequestBuilder request, Object body, int expected)
            throws Exception {
        request.header("Authorization", session.bearer());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("%s → %s", request, result.getResponse().getContentAsString())
                .isEqualTo(expected);
        String content = result.getResponse().getContentAsString();
        return content.isBlank() ? null : json.readTree(content);
    }
}
