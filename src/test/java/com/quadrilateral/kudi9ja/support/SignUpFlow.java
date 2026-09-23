package com.quadrilateral.kudi9ja.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Walks a test customer through the eight steps of signing up.
 *
 * <p>Shared because more than one test class needs a real account to work with,
 * and because the fixtures are not arbitrary — several of them look it until
 * you know what the sandbox is doing, and getting one wrong fails a test for a
 * reason that has nothing to do with what it was checking.
 */
public final class SignUpFlow {

    /** The password every test account is opened with. */
    public static final String PASSWORD = "Correct-Horse-9ja!";

    /** Six digits, and not a run: {@code 123456} is refused as too easy. */
    public static final String PASSCODE = "246810";

    /** Four digits, and not a run: {@code 1234} is refused for the same reason. */
    public static final String PIN = "5271";

    /**
     * A counter behind the identity numbers and the phone.
     *
     * <p>A BVN and an NIN each belong to one account and one only, so every
     * customer needs their own. Sequential rather than random, because the
     * sandbox verifier reads the <b>final digit</b> as an instruction — ending
     * in 0 means "not found", ending in 9 means "a different person" — and a
     * random number would fail one run in five for a reason that had nothing to
     * do with the test.
     */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final MockMvc mvc;
    private final ObjectMapper json;
    private final CapturingMailer mailer;

    public SignUpFlow(MockMvc mvc, ObjectMapper json, CapturingMailer mailer) {
        this.mvc = mvc;
        this.json = json;
        this.mailer = mailer;
    }

    /** An email nobody else in this run will use. */
    public static String freshEmail(String prefix) {
        return prefix + "+" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    /** Eleven digits, unique per call, never ending in 0 or 9. */
    public static String nextIdentityNumber(int prefix) {
        int serial = SEQUENCE.incrementAndGet();
        return String.format("%d%08d%d", prefix, serial, 1 + (serial % 8));
    }

    /** Step one only, for tests about the gating rather than the account. */
    public String startSignup(String email) throws Exception {
        int serial = SEQUENCE.incrementAndGet();

        MvcResult result = mvc.perform(post("/api/v1/auth/signup/personal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Device", "Integration test")
                        .content(json.writeValueAsString(Map.of(
                                "fullName", "Chioma Grace Adeyemi",
                                "email", email,
                                "phone", String.format("080%08d", serial),
                                "dateOfBirth", "1994-04-12",
                                "gender", "Female"))))
                .andExpect(status().isCreated())
                .andReturn();

        return read(result).get("draftId").asText();
    }

    /** All eight steps, ending with a live session. */
    public Session signUp(String email) throws Exception {
        return signUp(email, nextIdentityNumber(22));
    }

    /** All eight steps with a BVN the test chose, for what is matched on it. */
    public Session signUp(String email, String bvn) throws Exception {
        String draftId = startSignup(email);

        step(draftId, "email", Map.of("code", mailer.requireCodeFor(email)));

        step(draftId, "identity", Map.of(
                "bvn", bvn,
                "nin", nextIdentityNumber(12),
                "address", "14 Adeola Odeku Street, Victoria Island",
                "state", "Lagos"));

        // The sandbox refuses an account number ending in 0 and resolves one
        // ending in 9 to somebody else. This one is neither.
        step(draftId, "payout", Map.of(
                "bank", "Zenith Bank",
                "accountNumber", "0123456781"));

        step(draftId, "password", Map.of(
                "password", PASSWORD,
                "securityQuestion", "What was your first school?",
                "securityAnswer", "Command Secondary"));

        step(draftId, "passcode", Map.of("passcode", PASSCODE, "confirmPasscode", PASSCODE));
        step(draftId, "pin", Map.of("pin", PIN, "confirmPin", PIN));

        MvcResult completed = mvc.perform(post("/api/v1/auth/signup/{id}/complete", draftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Device", "Integration test")
                        .content(json.writeValueAsString(Map.of(
                                "accepted", true,
                                // The versions in force, the same ones the app
                                // carries. A stale number here is refused.
                                "acceptedVersions", Map.of(
                                        "TERMS", "1.1", "PRIVACY", "1.1", "LENDING", "1.0")))))
                .andExpect(status().isCreated())
                .andReturn();

        return sessionFrom(read(completed));
    }

    public Session signIn(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/signin")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email,
                                "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();

        return sessionFrom(read(result));
    }

    private void step(String draftId, String name, Map<String, Object> body) throws Exception {
        mvc.perform(post("/api/v1/auth/signup/{id}/{step}", draftId, name)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private Session sessionFrom(JsonNode body) {
        return new Session(
                body.get("accessToken").asText(),
                body.get("refreshToken").asText(),
                UUID.fromString(body.get("profile").get("id").asText()),
                body.get("profile").get("email").asText());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** A signed-in customer, as a test holds one. */
    public record Session(String accessToken, String refreshToken, UUID userId, String email) {

        public String bearer() {
            return "Bearer " + accessToken;
        }
    }
}
