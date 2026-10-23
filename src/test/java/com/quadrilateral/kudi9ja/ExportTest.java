package com.quadrilateral.kudi9ja;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.support.BorrowFlow;
import com.quadrilateral.kudi9ja.support.CapturingMailer;
import com.quadrilateral.kudi9ja.support.SignUpFlow;
import java.time.LocalDate;
import java.time.ZoneId;
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

/** Reports for Excel and PDF: owners and administrators only, every download logged. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(CapturingMailer.Config.class)
@TestPropertySource(properties = {
        "kudi9ja.jobs.enabled=false",
        "kudi9ja.bootstrap.owner-emails=owner@kudi9ja.test"
})
@DisplayName("Reports to download")
class ExportTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private CapturingMailer mailer;

    @Autowired
    private UserRepository users;

    private SignUpFlow flow;
    private BorrowFlow borrowing;

    @BeforeEach
    void fresh() {
        mailer.clear();
        flow = new SignUpFlow(mvc, json, mailer);
        borrowing = new BorrowFlow(mvc, json);
    }

    @Test
    @DisplayName("an owner downloads every report; the rows carry the figures; a viewer cannot")
    void reports() throws Exception {
        SignUpFlow.Session owner = owner();
        SignUpFlow.Session customer = flow.signUp(SignUpFlow.freshEmail("exported"));
        borrowing.borrow(customer, owner, "200000", 3, "Stock for the shop");
        String ref = users.findById(customer.userId()).orElseThrow().getCustomerRef();
        String today = LocalDate.now(ZoneId.of("Africa/Lagos")).toString();

        for (String report : new String[] {"customers", "loans", "repayments", "transactions", "paystack", "withdrawals", "applications"}) {
            JsonNode r = send(owner, get("/api/v1/admin/exports/" + report), 200);
            assertThat(r.get("title").asText()).isNotBlank();
            assertThat(r.get("columns")).isNotEmpty();
            assertThat(r.get("generatedBy").asText()).isNotBlank();
            for (JsonNode row : r.get("rows")) {
                assertThat(row).hasSameSizeAs(r.get("columns"));
            }
        }

        JsonNode customers = send(owner, get("/api/v1/admin/exports/customers?from=" + today + "&to=" + today), 200);
        assertThat(customers.get("period").asText()).isEqualTo(today + " to " + today);
        assertThat(customers.get("rows").toString()).contains(ref);

        // The loan, with what was lent, paid out and owed.
        JsonNode loans = send(owner, get("/api/v1/admin/exports/loans"), 200);
        JsonNode loan = null;
        for (JsonNode row : loans.get("rows")) {
            if (row.get(1).asText().equals(ref)) {
                loan = row;
            }
        }
        assertThat(loan).as("the loan is in the report").isNotNull();
        assertThat(loan.get(4).decimalValue()).isEqualByComparingTo("200000");
        assertThat(loan.get(10).decimalValue()).isEqualByComparingTo("195000");

        // An application is in its report too.
        assertThat(send(owner, get("/api/v1/admin/exports/applications"), 200).get("rows").toString()).contains(ref);

        // A period that ends before it starts is refused; an unknown report is not found.
        send(owner, get("/api/v1/admin/exports/loans?from=2026-10-10&to=2026-10-01"), 400);
        send(owner, get("/api/v1/admin/exports/everything"), 404);

        // A viewer can see the panel but cannot take the data away in bulk.
        String viewerEmail = SignUpFlow.freshEmail("exportviewer");
        flow.signUp(viewerEmail);
        send(owner, post("/api/v1/admin/team"), Map.of("email", viewerEmail, "role", "VIEWER"), 201);
        send(flow.signIn(viewerEmail), get("/api/v1/admin/exports/customers"), 403);

        // And the download is on record.
        assertThat(send(owner, get("/api/v1/admin/audit?category=DATA_ACCESS"), 200).toString()).contains("Report downloaded");
    }

    // -- Plumbing -----------------------------------------------------------

    private SignUpFlow.Session owner() throws Exception {
        String ownerEmail = "owner@kudi9ja.test";
        if (users.findByEmailIgnoreCase(ownerEmail).isPresent()) {
            return flow.signIn(ownerEmail);
        }
        return flow.signUp(ownerEmail);
    }

    private JsonNode send(SignUpFlow.Session session, MockHttpServletRequestBuilder request, int expected) throws Exception {
        return send(session, request, null, expected);
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
