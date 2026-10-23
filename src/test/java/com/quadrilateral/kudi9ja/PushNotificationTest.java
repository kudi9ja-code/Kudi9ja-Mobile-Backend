package com.quadrilateral.kudi9ja;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.domain.notification.DeviceTokenRepository;
import com.quadrilateral.kudi9ja.domain.notification.NotificationService;
import com.quadrilateral.kudi9ja.domain.notification.NotifyKind;
import com.quadrilateral.kudi9ja.support.CapturingMailer;
import com.quadrilateral.kudi9ja.support.InlineAsync;
import com.quadrilateral.kudi9ja.support.RecordingPushSender;
import com.quadrilateral.kudi9ja.support.SignUpFlow;
import java.util.Map;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Push notifications: who gets buzzed, about what, and what a locked phone is
 * allowed to say.
 *
 * <p>The delivery itself is somebody else's network, so what is worth testing
 * here is everything around it — the privacy rule on the lock screen, the two
 * groups that cannot be silenced, and the guarantee that nothing is sent about
 * a transaction that rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import({CapturingMailer.Config.class, RecordingPushSender.Config.class, InlineAsync.Config.class})
@TestPropertySource(properties = {
        "kudi9ja.jobs.enabled=false",
        "kudi9ja.bootstrap.owner-emails=owner@kudi9ja.test"
})
@DisplayName("Push notifications")
class PushNotificationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private CapturingMailer mailer;
    @Autowired private RecordingPushSender pushes;
    @Autowired private NotificationService notifications;
    @Autowired private DeviceTokenRepository devices;
    @Autowired private com.quadrilateral.kudi9ja.domain.user.UserRepository users;

    private SignUpFlow flow;
    private String email;

    @BeforeEach
    void setUp() {
        mailer.clear();
        pushes.clear();
        flow = new SignUpFlow(mvc, json, mailer);
        email = SignUpFlow.freshEmail("push");
    }

    // ── What a locked phone may say ────────────────────────────────────────

    @Nested
    @DisplayName("What reaches the lock screen")
    class LockScreen {

        /**
         * The rule that matters most. A push notification travels through
         * Google and lands on a screen anybody nearby can read.
         */
        @Test
        @DisplayName("never carries the amount")
        void amountsNeverReachTheLockScreen() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);
            registerPhone(customer, "token-lock-1");

            notifications.push(
                    customer.userId(),
                    NotifyKind.GENERAL,
                    "Payment confirmed",
                    "₦250,000 has been added to your wallet.",
                    new java.math.BigDecimal("250000"));

            RecordingPushSender.Sent sent = pushes.only();

            assertThat(sent.title()).isEqualTo("Payment confirmed");
            assertThat(sent.body())
                    .doesNotContain("250")
                    .doesNotContain("₦")
                    .isEqualTo("Open Kudi9ja to see the details.");

            // The figure still travels in the data payload, which the app reads
            // after it opens — behind the phone's own lock.
            assertThat(sent.data()).containsEntry("amount", "250000");
            assertThat(sent.data()).containsEntry("kind", "GENERAL");
        }

        /**
         * The exception, and it earns it: a customer who reads "Something
         * changed" and taps later has already lost the minutes that mattered.
         */
        @Test
        @DisplayName("says everything for a security alert")
        void securityAlertsSayWhatHappened() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);
            registerPhone(customer, "token-lock-2");

            notifications.push(
                    customer.userId(),
                    NotifyKind.SECURITY,
                    "Your payout account changed",
                    "Withdrawals will now go to Access Bank ******4455. "
                            + "If this was not you, contact support immediately.");

            assertThat(pushes.only().body())
                    .contains("Access Bank")
                    .contains("If this was not you");
        }

        @Test
        @DisplayName("the full text is still in the app")
        void theAppStillHasEverything() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);
            registerPhone(customer, "token-lock-3");

            notifications.push(
                    customer.userId(), NotifyKind.INTEREST, "Bonus paid",
                    "You finished \"New laptop\" and earned ₦12,000.",
                    new java.math.BigDecimal("12000"));

            // Found by title rather than by position: the welcome notification
            // from sign-up can share a timestamp with this one, and two rows
            // with the same createdAt have no guaranteed order.
            JsonNode feed = getJson(customer, "/api/v1/notifications");
            JsonNode bonus = null;
            for (JsonNode item : feed.get("items")) {
                if ("Bonus paid".equals(item.get("title").asText())) {
                    bonus = item;
                }
            }
            assertThat(bonus).as("the notification should be in the feed").isNotNull();
            assertThat(bonus.get("body").asText())
                    .as("the app keeps the figure the lock screen withheld")
                    .contains("12,000");
        }
    }

    // ── Who gets buzzed ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Devices")
    class Devices {

        @Test
        @DisplayName("every phone the customer registered is sent to")
        void allDevices() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);
            registerPhone(customer, "token-a");
            registerPhone(customer, "token-b");

            notifications.push(customer.userId(), NotifyKind.GENERAL, "Payment confirmed", "body");

            assertThat(pushes.only().tokens()).containsExactlyInAnyOrder("token-a", "token-b");
        }

        @Test
        @DisplayName("a customer with no phone registered is simply not pushed to")
        void noDevices() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);

            notifications.push(customer.userId(), NotifyKind.GENERAL, "Payment confirmed", "body");

            assertThat(pushes.all()).isEmpty();
        }

        /**
         * A handset that changed hands arrives carrying a token already on file
         * against whoever had it before. The row moves rather than duplicating,
         * so the previous owner stops being told about somebody else's money.
         */
        @Test
        @DisplayName("a re-used handset moves to whoever signed in last")
        void handsetChangesHands() throws Exception {
            SignUpFlow.Session first = flow.signUp(email);
            SignUpFlow.Session second = flow.signUp(SignUpFlow.freshEmail("push2"));

            registerPhone(first, "shared-handset");
            registerPhone(second, "shared-handset");

            assertThat(devices.findByUserId(first.userId())).isEmpty();
            assertThat(devices.findByUserId(second.userId())).hasSize(1);

            pushes.clear();
            notifications.push(first.userId(), NotifyKind.GENERAL, "Payment confirmed", "body");
            assertThat(pushes.all()).as("the previous owner is no longer reachable").isEmpty();
        }

        @Test
        @DisplayName("signing out drops the phone")
        void unregister() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);
            registerPhone(customer, "token-goodbye");

            mvc.perform(delete("/api/v1/notifications/devices/{t}", "token-goodbye")
                            .header(HttpHeaders.AUTHORIZATION, customer.bearer()))
                    .andExpect(status().isOk());

            notifications.push(customer.userId(), NotifyKind.GENERAL, "Payment confirmed", "body");
            assertThat(pushes.all()).isEmpty();
        }

        @Test
        @DisplayName("the register never hands the token back")
        void tokensAreNotReturned() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);
            registerPhone(customer, "token-secret-value");

            MvcResult result = mvc.perform(get("/api/v1/notifications/devices")
                            .header(HttpHeaders.AUTHORIZATION, customer.bearer()))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("token-secret-value");
        }
    }

    // ── What can be switched off ───────────────────────────────────────────

    @Nested
    @DisplayName("Preferences")
    class Preferences {

        @Test
        @DisplayName("a customer who has changed nothing hears about everything")
        void defaultsToOn() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);

            JsonNode prefs = getJson(customer, "/api/v1/notifications/preferences");
            assertThat(prefs).hasSize(NotifyKind.values().length);
            for (JsonNode p : prefs) {
                assertThat(p.get("enabled").asBoolean()).isTrue();
            }
        }

        @Test
        @DisplayName("switching a group off stops the push but keeps the record")
        void mutingStopsPush() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);
            registerPhone(customer, "token-muted");

            mvc.perform(patch("/api/v1/notifications/preferences")
                            .header(HttpHeaders.AUTHORIZATION, customer.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"kind": "AUTO_SAVE", "enabled": false}
                                    """))
                    .andExpect(status().isOk());

            notifications.push(customer.userId(), NotifyKind.AUTO_SAVE, "Auto-save skipped", "body");

            assertThat(pushes.all()).as("no push").isEmpty();
            // But it is still in the feed — muting is about the buzz, not the record.
            assertThat(getJson(customer, "/api/v1/notifications").get("items")).isNotEmpty();
        }

        /**
         * An attacker who could silence the alert that says the payout account
         * moved would silence it first.
         */
        @Test
        @DisplayName("security alerts and money movements cannot be switched off")
        void theTwoThatCannotBeSilenced() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);

            for (String kind : new String[] {"SECURITY", "GENERAL"}) {
                mvc.perform(patch("/api/v1/notifications/preferences")
                                .header(HttpHeaders.AUTHORIZATION, customer.bearer())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"kind": "%s", "enabled": false}
                                        """.formatted(kind)))
                        .andExpect(status().isBadRequest());
            }

            assertThatThrownBy(() ->
                    notifications.setPreference(customer.userId(), NotifyKind.SECURITY, false))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("cannot be switched off");
        }

        @Test
        @DisplayName("the settings screen is told which groups are fixed, and why")
        void explainsTheFixedOnes() throws Exception {
            SignUpFlow.Session customer = flow.signUp(email);

            JsonNode prefs = getJson(customer, "/api/v1/notifications/preferences");
            JsonNode security = byKind(prefs, "SECURITY");

            assertThat(security.get("canBeSwitchedOff").asBoolean()).isFalse();
            assertThat(security.get("whyNot").asText()).contains("somebody else is in your account");
            assertThat(byKind(prefs, "AUTO_SAVE").get("canBeSwitchedOff").asBoolean()).isTrue();
        }
    }

    // ── The guarantee that matters ─────────────────────────────────────────

    @Test
    @DisplayName("a real money movement pushes once, after it has actually happened")
    void pushesOnRealMoneyMovement() throws Exception {
        SignUpFlow.Session customer = flow.signUp(email);
        SignUpFlow.Session admin = owner();
        registerPhone(customer, "token-journey");
        pushes.clear();

        // The whole money-in route: reference, claim with receipt, admin confirms.
        String reference = postJson(customer, "/api/v1/payins/reference", null)
                .get("reference").asText();

        MvcResult claim = mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .multipart("/api/v1/payins")
                                .file(new org.springframework.mock.web.MockMultipartFile(
                                        "receipt", "t.png", MediaType.IMAGE_PNG_VALUE, "bytes".getBytes()))
                                .param("amount", "250000")
                                .param("reference", reference)
                                .param("senderName", "Chioma Grace Adeyemi")
                                .header(HttpHeaders.AUTHORIZATION, customer.bearer()))
                .andExpect(status().isCreated())
                .andReturn();

        // Submitting the claim tells the customer it is with the team.
        assertThat(pushes.all()).hasSize(1);
        assertThat(pushes.all().get(0).title()).isEqualTo("Payment submitted");

        String claimId = json.readTree(claim.getResponse().getContentAsString()).get("id").asText();
        pushes.clear();
        postJson(admin, "/api/v1/admin/payins/" + claimId + "/confirm", "{}");

        // And confirming tells them the money arrived — without saying how much.
        assertThat(pushes.all()).isNotEmpty();
        RecordingPushSender.Sent confirmed = pushes.all().get(0);
        assertThat(confirmed.title()).contains("Payment");
        assertThat(confirmed.body()).isEqualTo("Open Kudi9ja to see the details.");
        assertThat(confirmed.tokens()).containsExactly("token-journey");
    }

    /**
     * Nothing is sent about money that did not move. The push is registered as
     * an after-commit callback, so a rolled-back transaction never fires it.
     */
    @Test
    @DisplayName("nothing is pushed for a transaction that was refused")
    void nothingPushedOnRollback() throws Exception {
        SignUpFlow.Session customer = flow.signUp(email);
        registerPhone(customer, "token-rollback");
        pushes.clear();

        // A withdrawal with nothing in the wallet: refused, and rolled back.
        mvc.perform(post("/api/v1/withdrawals")
                        .header(HttpHeaders.AUTHORIZATION, customer.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 5000, "pin": "%s"}
                                """.formatted(SignUpFlow.PIN)))
                .andExpect(status().isUnprocessableEntity());

        assertThat(pushes.all()).isEmpty();
    }

    // ── Announcements ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("An announcement")
    class Announcements {

        private static final String PICTURE = "https://res.cloudinary.com/kudi9ja/image/upload/promo.jpg";

        @Test
        @DisplayName("reaches every registered phone, with its picture")
        void reachesEveryPhone() throws Exception {
            SignUpFlow.Session first = flow.signUp(email);
            SignUpFlow.Session second = flow.signUp(SignUpFlow.freshEmail("push-two"));
            registerPhone(first, "token-announce-1");
            registerPhone(second, "token-announce-2");
            SignUpFlow.Session admin = owner();

            JsonNode sent = announce(admin, PICTURE, status().isOk());

            assertThat(sent.get("devices").asInt()).isGreaterThanOrEqualTo(2);
            assertThat(pushes.all()).allSatisfy(push -> {
                assertThat(push.title()).isEqualTo("Happy holidays");
                assertThat(push.imageUrl()).isEqualTo(PICTURE);
                assertThat(push.data()).containsEntry("kind", "ANNOUNCEMENT");
                assertThat(push.tokens()).hasSizeLessThanOrEqualTo(12);
            });
            assertThat(pushes.all().stream().flatMap(p -> p.tokens().stream()))
                    .contains("token-announce-1", "token-announce-2");
        }

        @Test
        @DisplayName("goes without a picture when none is given")
        void pictureIsOptional() throws Exception {
            registerPhone(flow.signUp(email), "token-announce-3");

            announce(owner(), "", status().isOk());

            assertThat(pushes.all()).isNotEmpty().allSatisfy(push -> assertThat(push.imageUrl()).isNull());
        }

        @Test
        @DisplayName("refuses a picture that is not a public https link")
        void refusesAPlainHttpPicture() throws Exception {
            announce(owner(), "http://example.com/promo.jpg", status().isBadRequest());
            assertThat(pushes.all()).isEmpty();
        }

        @Test
        @DisplayName("cannot be sent by a customer")
        void customersCannotSend() throws Exception {
            announce(flow.signUp(email), PICTURE, status().isForbidden());
            assertThat(pushes.all()).isEmpty();
        }

        private JsonNode announce(
                SignUpFlow.Session session,
                String imageUrl,
                org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {

            pushes.clear();
            MvcResult result = mvc.perform(post("/api/v1/admin/announcements")
                            .header(HttpHeaders.AUTHORIZATION, session.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "title", "Happy holidays",
                                    "body", "From all of us at Kudi9ja.",
                                    "imageUrl", imageUrl))))
                    .andExpect(expected)
                    .andReturn();
            return json.readTree(result.getResponse().getContentAsString());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void registerPhone(SignUpFlow.Session session, String token) throws Exception {
        mvc.perform(post("/api/v1/notifications/devices")
                        .header(HttpHeaders.AUTHORIZATION, session.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "token", token,
                                "platform", "ANDROID",
                                "deviceLabel", "Test handset"))))
                .andExpect(status().isOk());
        pushes.clear();
    }

    private static JsonNode byKind(JsonNode prefs, String kind) {
        for (JsonNode p : prefs) {
            if (p.get("kind").asText().equals(kind)) {
                return p;
            }
        }
        throw new AssertionError("no preference for " + kind);
    }

    private SignUpFlow.Session owner() throws Exception {
        String ownerEmail = "owner@kudi9ja.test";
        return users.findByEmailIgnoreCase(ownerEmail).isPresent()
                ? flow.signIn(ownerEmail)
                : flow.signUp(ownerEmail);
    }

    private JsonNode getJson(SignUpFlow.Session session, String path) throws Exception {
        return json.readTree(mvc.perform(get(path)
                        .header(HttpHeaders.AUTHORIZATION, session.bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode postJson(SignUpFlow.Session session, String path, String body) throws Exception {
        var request = post(path).header(HttpHeaders.AUTHORIZATION, session.bearer());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return json.readTree(mvc.perform(request).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString());
    }
}
