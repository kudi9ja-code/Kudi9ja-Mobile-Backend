package com.quadrilateral.kudi9ja;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Help and complaints: a complaint gets its promised times, a reply reaches
 * the customer, a resolved conversation reopens when the customer writes
 * again, and nobody reads anybody else's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(CapturingMailer.Config.class)
@TestPropertySource(properties = {
        "kudi9ja.jobs.enabled=false",
        "kudi9ja.bootstrap.owner-emails=owner@kudi9ja.test"
})
@DisplayName("Help and complaints")
class SupportTest {

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
    @DisplayName("a complaint is answered, resolved, and reopened by the customer writing again")
    void complaintLifecycle() throws Exception {
        SignUpFlow.Session customer = flow.signUp(SignUpFlow.freshEmail("complainer"));
        SignUpFlow.Session admin = owner();

        JsonNode opened = postJson("/api/v1/support/conversations", customer, """
                {"kind": "COMPLAINT", "category": "Withdrawals", "subject": "Withdrawal not received",
                 "message": "I withdrew on Monday and nothing has arrived."}
                """);
        String id = opened.at("/conversation/id").asText();
        assertThat(opened.at("/conversation/reference").asText()).startsWith("CMP-");
        assertThat(opened.at("/conversation/resolveBy").isMissingNode()).isFalse();
        assertThat(opened.get("messages")).hasSize(2);

        // The queue has it, unread, and the customer's words as the preview.
        JsonNode queue = getJson("/api/v1/admin/support/conversations?status=OPEN", admin);
        JsonNode row = find(queue.get("items"), id);
        assertThat(row.get("unread").asInt()).isEqualTo(1);
        assertThat(row.get("lastMessagePreview").asText()).contains("withdrew on Monday");

        postJson("/api/v1/admin/support/conversations/" + id + "/messages", admin, """
                {"body": "Sorry about this. It was returned by your bank; it is back in your wallet."}
                """);
        assertThat(getJson("/api/v1/support/unread", customer).get("unread").asLong()).isEqualTo(1);

        JsonNode thread = getJson("/api/v1/support/conversations/" + id, customer);
        assertThat(thread.at("/conversation/status").asText()).isEqualTo("AWAITING_CUSTOMER");
        assertThat(thread.at("/conversation/acknowledgedAt").isMissingNode()).isFalse();
        assertThat(getJson("/api/v1/support/unread", customer).get("unread").asLong()).isZero();

        postJson("/api/v1/admin/support/conversations/" + id + "/resolve", admin, """
                {"note": "Refunded to the wallet."}
                """);
        assertThat(getJson("/api/v1/support/conversations/" + id, customer).at("/conversation/status").asText())
                .isEqualTo("RESOLVED");

        postJson("/api/v1/support/conversations/" + id + "/messages", customer, """
                {"body": "It is still not in my wallet."}
                """);
        assertThat(getJson("/api/v1/support/conversations/" + id, customer).at("/conversation/status").asText())
                .isEqualTo("OPEN");
    }

    @Test
    @DisplayName("one customer cannot read another's conversation")
    void conversationsArePrivate() throws Exception {
        SignUpFlow.Session one = flow.signUp(SignUpFlow.freshEmail("askone"));
        SignUpFlow.Session two = flow.signUp(SignUpFlow.freshEmail("asktwo"));
        String id = postJson("/api/v1/support/conversations", one, """
                {"kind": "ENQUIRY", "category": "Savings", "subject": "How does interest work?",
                 "message": "When is interest paid on a fixed plan?"}
                """).at("/conversation/id").asText();

        mvc.perform(get("/api/v1/support/conversations/" + id).header("Authorization", two.bearer()))
                .andExpect(status().isNotFound());
        assertThat(getJson("/api/v1/support/conversations", two)).isEmpty();
    }

    @Test
    @DisplayName("a photo can be sent in a conversation, and opened by both sides")
    void photos() throws Exception {
        SignUpFlow.Session customer = flow.signUp(SignUpFlow.freshEmail("photo"));
        SignUpFlow.Session admin = owner();
        String id = postJson("/api/v1/support/conversations", customer, """
                {"kind": "COMPLAINT", "category": "Paying in", "subject": "Card was charged twice",
                 "message": "Screenshot attached."}
                """).at("/conversation/id").asText();

        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
        JsonNode sent = json.readTree(mvc.perform(multipart("/api/v1/support/conversations/" + id + "/attachments")
                        .file(new MockMultipartFile("file", "debit.png", "image/png", png))
                        .param("body", "Here it is")
                        .header("Authorization", customer.bearer()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        assertThat(sent.get("attachmentType").asText()).isEqualTo("image/png");
        assertThat(sent.get("body").asText()).isEqualTo("Here it is");

        String messageId = sent.get("id").asText();
        byte[] mine = mvc.perform(get("/api/v1/support/attachments/" + messageId).header("Authorization", customer.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(mine).isEqualTo(png);
        mvc.perform(get("/api/v1/admin/support/attachments/" + messageId).header("Authorization", admin.bearer()))
                .andExpect(status().isOk());

        // An executable is not a photo.
        mvc.perform(multipart("/api/v1/support/conversations/" + id + "/attachments")
                        .file(new MockMultipartFile("file", "x.exe", "application/x-msdownload", new byte[] {1, 2}))
                        .header("Authorization", customer.bearer()))
                .andExpect(status().isBadRequest());
    }

    // -- Plumbing -----------------------------------------------------------

    private static JsonNode find(JsonNode items, String id) {
        for (JsonNode item : items) {
            if (item.get("id").asText().equals(id)) {
                return item;
            }
        }
        throw new AssertionError("Conversation " + id + " is not in the queue: " + items);
    }

    @Test
    @DisplayName("anyone on the panel can answer, even a read-only viewer")
    void viewersCanReply() throws Exception {
        SignUpFlow.Session owner = owner();
        String viewerEmail = SignUpFlow.freshEmail("supportviewer");
        flow.signUp(viewerEmail);
        postJson("/api/v1/admin/team", owner, """
                {"email": "%s", "role": "VIEWER"}
                """.formatted(viewerEmail));
        SignUpFlow.Session viewer = flow.signIn(viewerEmail);

        SignUpFlow.Session customer = flow.signUp(SignUpFlow.freshEmail("askviewer"));
        String id = postJson("/api/v1/support/conversations", customer, """
                {"kind": "ENQUIRY", "category": "Savings", "subject": "When is interest paid?",
                 "message": "On a fixed plan, when does the interest arrive?"}
                """).at("/conversation/id").asText();

        postJson("/api/v1/admin/support/conversations/" + id + "/assign", viewer, "{}");
        postJson("/api/v1/admin/support/conversations/" + id + "/messages", viewer, """
                {"body": "The day the plan starts. It is paid upfront."}
                """);
        JsonNode thread = getJson("/api/v1/support/conversations/" + id, customer);
        assertThat(thread.get("messages").toString()).contains("paid upfront");
        postJson("/api/v1/admin/support/conversations/" + id + "/resolve", viewer, "{}");
        assertThat(getJson("/api/v1/support/conversations/" + id, customer).at("/conversation/status").asText())
                .isEqualTo("RESOLVED");
    }

    private SignUpFlow.Session owner() throws Exception {
        String ownerEmail = "owner@kudi9ja.test";
        if (users.findByEmailIgnoreCase(ownerEmail).isPresent()) {
            return flow.signIn(ownerEmail);
        }
        return flow.signUp(ownerEmail);
    }

    private JsonNode getJson(String path, SignUpFlow.Session session) throws Exception {
        return json.readTree(mvc.perform(get(path).header("Authorization", session.bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode postJson(String path, SignUpFlow.Session session, String body) throws Exception {
        String out = mvc.perform(post(path)
                        .header("Authorization", session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return out.isBlank() ? json.createObjectNode() : json.readTree(out);
    }
}
