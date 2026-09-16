package com.quadrilateral.kudi9ja;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quadrilateral.kudi9ja.domain.review.ReviewService;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.support.CapturingMailer;
import com.quadrilateral.kudi9ja.support.SignUpFlow;
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
 * Ratings and reviews of the app.
 *
 * <p>One review per customer, theirs to rewrite or withdraw; every customer
 * reads every review and the average; an admin may remove one, on the record.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(CapturingMailer.Config.class)
@TestPropertySource(properties = {
        "kudi9ja.jobs.enabled=false",
        "kudi9ja.bootstrap.owner-emails=owner@kudi9ja.test"
})
@DisplayName("Reviews of the app")
class ReviewTest {

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
    @DisplayName("a review is written once, read by everybody, and rewritten in place")
    void writtenReadAndRewritten() throws Exception {
        SignUpFlow.Session author = flow.signUp(SignUpFlow.freshEmail("reviewer"));
        SignUpFlow.Session reader = flow.signUp(SignUpFlow.freshEmail("reader"));

        // Nothing yet, and the average says so rather than showing 0.0.
        assertThat(mvc.perform(get("/api/v1/reviews/mine").header("Authorization", author.bearer()))
                .andReturn().getResponse().getStatus()).isEqualTo(204);

        JsonNode written = putJson(author, """
                {"rating": 5, "comment": "Saving is easy and the loan came through in a day."}
                """);
        assertThat(written.get("rating").asInt()).isEqualTo(5);
        assertThat(written.get("mine").asBoolean()).isTrue();
        assertThat(written.get("edited").asBoolean()).isFalse();
        // A first name and an initial, never the whole name.
        assertThat(written.get("displayName").asText()).isEqualTo("Chioma A.");

        // Somebody else sees it, knows it is not theirs, and sees the average.
        JsonNode page = getJson("/api/v1/reviews", reader);
        JsonNode theirs = null;
        for (JsonNode item : page.get("items")) {
            if (item.get("id").asText().equals(written.get("id").asText())) {
                theirs = item;
            }
        }
        assertThat(theirs).isNotNull();
        assertThat(theirs.get("mine").asBoolean()).isFalse();
        assertThat(theirs.get("comment").asText()).contains("came through in a day");

        JsonNode summary = getJson("/api/v1/reviews/summary", reader);
        assertThat(summary.get("count").asLong()).isPositive();
        assertThat(summary.get("average").asDouble()).isBetween(1.0, 5.0);

        // A change of mind replaces the review rather than adding a second.
        long before = getJson("/api/v1/reviews/summary", reader).get("count").asLong();
        JsonNode rewritten = putJson(author, """
                {"rating": 3, "comment": "Still good, but the pay-in took a while to be confirmed."}
                """);
        assertThat(rewritten.get("id").asText()).isEqualTo(written.get("id").asText());
        assertThat(rewritten.get("rating").asInt()).isEqualTo(3);
        assertThat(rewritten.get("edited").asBoolean()).isTrue();
        assertThat(getJson("/api/v1/reviews/summary", reader).get("count").asLong()).isEqualTo(before);

        // And it can be taken back.
        mvc.perform(delete("/api/v1/reviews/mine").header("Authorization", author.bearer()))
                .andExpect(status().isOk());
        assertThat(mvc.perform(get("/api/v1/reviews/mine").header("Authorization", author.bearer()))
                .andReturn().getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    @DisplayName("stars alone are not a review, and neither is a novel")
    void validated() throws Exception {
        SignUpFlow.Session author = flow.signUp(SignUpFlow.freshEmail("terse"));

        mvc.perform(put("/api/v1/reviews/mine")
                        .header("Authorization", author.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\": 4, \"comment\": \"  \"}"))
                .andExpect(status().is4xxClientError());

        mvc.perform(put("/api/v1/reviews/mine")
                        .header("Authorization", author.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\": 6, \"comment\": \"Too many stars.\"}"))
                .andExpect(status().is4xxClientError());

        mvc.perform(put("/api/v1/reviews/mine")
                        .header("Authorization", author.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\": 4, \"comment\": \"" + "x".repeat(501) + "\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("an admin may remove a review, with a reason, and it is audited")
    void adminRemoves() throws Exception {
        SignUpFlow.Session admin = owner();
        SignUpFlow.Session author = flow.signUp(SignUpFlow.freshEmail("rude"));

        JsonNode written = putJson(author, """
                {"rating": 1, "comment": "Call me on 0803 000 0000 for a better rate."}
                """);
        String id = written.get("id").asText();

        // No reason, no removal.
        mvc.perform(delete("/api/v1/admin/reviews/" + id)
                        .header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"\"}"))
                .andExpect(status().is4xxClientError());

        mvc.perform(delete("/api/v1/admin/reviews/" + id)
                        .header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Publishes a phone number.\"}"))
                .andExpect(status().isOk());

        assertThat(mvc.perform(get("/api/v1/reviews/mine").header("Authorization", author.bearer()))
                .andReturn().getResponse().getStatus()).isEqualTo(204);

        JsonNode audit = getJson("/api/v1/admin/audit?size=5", admin);
        assertThat(audit.get("items").toString()).contains("App review removed");

        // A customer cannot reach the admin's endpoint.
        mvc.perform(delete("/api/v1/admin/reviews/" + id)
                        .header("Authorization", author.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Trying it on.\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("the shown name is a first name and an initial")
    void displayName() {
        assertThat(ReviewService.displayNameFor("Chioma Grace Adeyemi")).isEqualTo("Chioma A.");
        assertThat(ReviewService.displayNameFor("Tunde")).isEqualTo("Tunde");
        assertThat(ReviewService.displayNameFor("  ")).isEqualTo("A customer");
    }

    // -- Plumbing -----------------------------------------------------------

    private JsonNode putJson(SignUpFlow.Session session, String body) throws Exception {
        MvcResult result = mvc.perform(put("/api/v1/reviews/mine")
                        .header("Authorization", session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode getJson(String path, SignUpFlow.Session session) throws Exception {
        return json.readTree(mvc.perform(get(path).header("Authorization", session.bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
