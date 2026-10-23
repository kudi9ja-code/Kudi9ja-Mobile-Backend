package com.quadrilateral.kudi9ja;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.support.CapturingMailer;
import com.quadrilateral.kudi9ja.support.SignUpFlow;
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
 * The signature drawn when an account is opened.
 *
 * <p>Stored with the acceptance, in the transaction that opens the account,
 * and readable afterwards by the customer — their copy — and by the panel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(CapturingMailer.Config.class)
@TestPropertySource(properties = {
        "kudi9ja.jobs.enabled=false",
        "kudi9ja.bootstrap.owner-emails=owner@kudi9ja.test"
})
@DisplayName("Signed agreements")
class SignatureTest {

    /** A one-pixel PNG: a real image, as small as one gets. */
    private static final String PNG =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    private static final Map<String, String> VERSIONS = Map.of("TERMS", "1.1", "PRIVACY", "1.1", "LENDING", "1.0");

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

    @Test
    @DisplayName("the signature is kept with the account, and both the customer and the panel can read it")
    void storedAndReadable() throws Exception {
        String email = SignUpFlow.freshEmail("signer");
        String draftId = flow.prepareDraft(email, SignUpFlow.nextIdentityNumber(51));

        MvcResult completed = complete(draftId, Map.of("image", PNG, "typedName", "chioma grace ADEYEMI"));
        assertThat(completed.getResponse().getStatus()).isEqualTo(201);
        SignUpFlow.Session customer = flow.sessionFrom(flow.read(completed));

        JsonNode mine = getJson("/api/v1/me/legal/signatures", customer);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("typedName").asText()).isEqualTo("chioma grace ADEYEMI");
        assertThat(mine.get(0).get("documents").asText()).isEqualTo("terms 1.1, privacy 1.1, lending 1.0");
        assertThat(mine.get(0).get("image").asText()).isEqualTo(PNG);
        assertThat(mine.get(0).get("device").asText()).isEqualTo("Integration test");

        SignUpFlow.Session admin = owner();
        JsonNode theirs = getJson("/api/v1/admin/customers/" + customer.userId() + "/signatures", admin);
        assertThat(theirs).hasSize(1);
        assertThat(theirs.get(0).get("url").asText()).startsWith("/api/v1/admin/receipts/");
        assertThat(theirs.get(0).hasNonNull("image")).isFalse();
    }

    @Test
    @DisplayName("a signature in another name, or one that is not an image, is refused and opens nothing")
    void refused() throws Exception {
        String email = SignUpFlow.freshEmail("wrongname");
        String draftId = flow.prepareDraft(email, SignUpFlow.nextIdentityNumber(52));

        assertThat(complete(draftId, Map.of("image", PNG, "typedName", "Somebody Else")).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(complete(draftId, Map.of("image", "data:image/png;base64,bm90IGFuIGltYWdl", "typedName", "Chioma Grace Adeyemi"))
                .getResponse().getStatus())
                .isEqualTo(400);
        assertThat(users.findByEmailIgnoreCase(email)).isEmpty();

        // Redrawn properly, the same draft completes.
        assertThat(complete(draftId, Map.of("image", PNG, "typedName", "Chioma Grace Adeyemi")).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(users.findByEmailIgnoreCase(email)).isPresent();
    }

    @Test
    @DisplayName("no account opens without a signature")
    void signatureRequired() throws Exception {
        String email = SignUpFlow.freshEmail("nosig");
        String draftId = flow.prepareDraft(email, SignUpFlow.nextIdentityNumber(77));
        MvcResult refused = mvc.perform(post("/api/v1/auth/signup/{id}/complete", draftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Device", "Integration test")
                        .content(json.writeValueAsString(Map.of("accepted", true, "acceptedVersions", VERSIONS))))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("signature");
        assertThat(users.findByEmailIgnoreCase(email)).isEmpty();

        // Every account that does open has its signature on record.
        SignUpFlow.Session customer = flow.signUp(SignUpFlow.freshEmail("withsig"));
        assertThat(getJson("/api/v1/me/legal/signatures", customer)).hasSize(1);
    }

    // -- Plumbing -----------------------------------------------------------

    private MvcResult complete(String draftId, Map<String, String> signature) throws Exception {
        return mvc.perform(post("/api/v1/auth/signup/{id}/complete", draftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Device", "Integration test")
                        .content(json.writeValueAsString(Map.of(
                                "accepted", true,
                                "acceptedVersions", VERSIONS,
                                "signature", signature))))
                .andReturn();
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
}
