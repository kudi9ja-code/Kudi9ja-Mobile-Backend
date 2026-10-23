package com.quadrilateral.kudi9ja;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Group loans: a numbered, named group with one loan officer, run by that
 * officer, whose borrowers take the officer as their lead and stay in the
 * group when they sign up.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(CapturingMailer.Config.class)
@TestPropertySource(properties = {
        "kudi9ja.jobs.enabled=false",
        "kudi9ja.bootstrap.owner-emails=owner@kudi9ja.test"
})
@DisplayName("Loan groups")
class LoanGroupTest {

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
    @DisplayName("any panel member can be a group's officer and run it; its borrowers get that officer and stay in it when they sign up")
    void lifecycle() throws Exception {
        SignUpFlow.Session owner = owner();
        String officerEmail = SignUpFlow.freshEmail("groupofficer");
        String otherEmail = SignUpFlow.freshEmail("othersupport");
        SignUpFlow.Session officer = flow.signUp(officerEmail);
        SignUpFlow.Session other = flow.signUp(otherEmail);
        // Any panel member can run a group — even a read-only viewer.
        send(owner, post("/api/v1/admin/team"), Map.of("email", officerEmail, "role", "VIEWER"), 201);
        send(owner, post("/api/v1/admin/team"), Map.of("email", otherEmail, "role", "SUPPORT"), 201);
        officer = flow.signIn(officerEmail);
        other = flow.signIn(otherEmail);
        String officerAdminId = idOf(send(owner, get("/api/v1/admin/officers"), null, 200), officerEmail);

        int number = send(owner, get("/api/v1/admin/groups/next-number"), null, 200).get("number").asInt();
        JsonNode group = send(owner, post("/api/v1/admin/groups"),
                Map.of("number", number, "name", "Oja Oba market", "area", "Akure", "officerId", officerAdminId), 201).get("group");
        String groupId = group.get("id").asText();
        assertThat(group.get("officerName").asText()).isNotBlank();

        // The same number cannot be opened twice.
        send(owner, post("/api/v1/admin/groups"), Map.of("number", number, "name", "Again", "officerId", officerAdminId), 409);

        // Another support admin who does not run it cannot add to it; its officer can.
        String bvn = SignUpFlow.nextIdentityNumber(71);
        String email = SignUpFlow.freshEmail("groupborrower");
        send(other, post("/api/v1/admin/loans/imports"), borrower(bvn, email, groupId), 403);
        String importId = send(officer, post("/api/v1/admin/loans/imports"), borrower(bvn, email, groupId), 201).get("id").asText();

        JsonNode detail = send(owner, get("/api/v1/admin/groups/" + groupId), null, 200);
        assertThat(detail.get("members")).hasSize(1);
        // A member is a real customer at once: an account, not yet activated.
        assertThat(detail.get("members").get(0).get("customerId").asText()).isNotBlank();

        JsonNode onFile = send(owner, get("/api/v1/admin/loans/imports/" + importId + "/officers"), null, 200);
        assertThat(onFile.get(0).get("officerId").asText()).isEqualTo(officerAdminId);
        assertThat(onFile.get(0).get("lead").asBoolean()).isTrue();

        // They sign up: still in the group, now with their account.
        SignUpFlow.Session borrower = flow.signUp(email, bvn);
        detail = send(owner, get("/api/v1/admin/groups/" + groupId), null, 200);
        assertThat(detail.get("members").get(0).get("signedUp").asBoolean()).isTrue();
        assertThat(detail.get("members").get(0).get("customerId").asText()).isEqualTo(borrower.userId().toString());
        assertThat(send(owner, get("/api/v1/admin/customers/" + borrower.userId() + "/groups"), null, 200)).hasSize(1);

        // The customers list can label them: same group, by account and by paper loan.
        boolean labelled = false;
        for (JsonNode label : send(owner, get("/api/v1/admin/groups/labels"), null, 200)) {
            if (label.path("customerId").asText().equals(borrower.userId().toString())) {
                assertThat(label.get("importedLoanId").asText()).isEqualTo(importId);
                assertThat(label.get("number").asInt()).isEqualTo(number);
                labelled = true;
            }
        }
        assertThat(labelled).isTrue();

        // Somebody who already has an account joins by account number.
        SignUpFlow.Session existing = flow.signUp(SignUpFlow.freshEmail("groupexisting"));
        String ref = users.findById(existing.userId()).orElseThrow().getCustomerRef();
        detail = send(officer, post("/api/v1/admin/groups/" + groupId + "/customers"), Map.of("customerRef", ref), 200);
        assertThat(detail.get("members")).hasSize(2);
    }

    // -- Plumbing -----------------------------------------------------------

    private static String idOf(JsonNode options, String email) {
        for (JsonNode o : options) {
            if (o.get("email").asText().equalsIgnoreCase(email)) {
                return o.get("id").asText();
            }
        }
        throw new AssertionError(email + " is not offered: " + options);
    }

    private Map<String, Object> borrower(String bvn, String email, String groupId) {
        Map<String, Object> body = new HashMap<>();
        body.put("bvn", bvn);
        body.put("fullName", "Folake Adunni Ojo");
        body.put("email", email);
        body.put("phone", SignUpFlow.freshPhone());
        body.put("principal", 100000);
        body.put("tenureMonths", 4);
        body.put("flatRate", 0.2);
        body.put("purpose", "Business");
        body.put("disbursedAt", Instant.now().minus(5, ChronoUnit.DAYS).toString());
        body.put("address", "Stall 14, Oja Oba");
        body.put("guarantors", List.of(Map.of("fullName", "Tunde Ojo", "phone", "08021234567")));
        body.put("groupId", groupId);
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
