package com.quadrilateral.kudi9ja;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.quadrilateral.kudi9ja.support.CapturingMailer;
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

/**
 * One address, too many requests, told to wait.
 *
 * <p>The per-account lockout stops somebody attacking one customer. It does
 * nothing against one machine trying a thousand <i>different</i> emails, and
 * from each account's point of view that attack is invisible. This is the
 * layer that sees it.
 *
 * <p>Low limits here, so the test can trip them in a handful of requests. The
 * numbers in the dev profile are set far above anything the rest of the suite
 * reaches, for exactly the opposite reason.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Import(CapturingMailer.Config.class)
@TestPropertySource(properties = {
        "kudi9ja.jobs.enabled=false",
        "kudi9ja.rate-limit.auth-per-minute=5",
        "kudi9ja.rate-limit.general-per-minute=12",
        "kudi9ja.rate-limit.trusted-proxy-hops=1"
})
@DisplayName("Rate limiting by address")
class RateLimitTest {

    @Autowired
    private MockMvc mvc;

    /** A sign-in that will be refused, which is all this test needs of it. */
    private static final String SIGN_IN = "{\"email\":\"nobody@example.com\",\"password\":\"wrong\"}";

    @Test
    @DisplayName("the sixth sign-in attempt from one address in a minute is refused")
    void authTierTrips() throws Exception {
        String address = "203.0.113.10";

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/signin")
                            .header("X-Forwarded-For", address)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SIGN_IN))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/v1/auth/signin")
                        .header("X-Forwarded-For", address)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SIGN_IN))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.details.retryAfterSeconds").isNumber());
    }

    /**
     * The whole point. A limiter that keyed on the account would let this
     * through; each email is seen once.
     */
    @Test
    @DisplayName("rotating the email does not get round it")
    void differentEmailsSameAddress() throws Exception {
        String address = "203.0.113.11";

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/signin")
                            .header("X-Forwarded-For", address)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"user" + i + "@example.com\",\"password\":\"x\"}"))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/v1/auth/signin")
                        .header("X-Forwarded-For", address)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user99@example.com\",\"password\":\"x\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("a different address is counted separately")
    void addressesAreIndependent() throws Exception {
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/signin")
                            .header("X-Forwarded-For", "203.0.113.20")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SIGN_IN))
                    .andExpect(status().isUnauthorized());
        }

        // The neighbour is untouched.
        mvc.perform(post("/api/v1/auth/signin")
                        .header("X-Forwarded-For", "203.0.113.21")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SIGN_IN))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The left of X-Forwarded-For is whatever the client typed. An attacker
     * who could rotate it would never be limited, so the address is read from
     * the end — the entry our own proxy appended.
     */
    @Test
    @DisplayName("a forged address at the front of the header does not dodge it")
    void forgedForwardedForIgnored() throws Exception {
        String realAddress = "203.0.113.30";

        for (int i = 0; i < 6; i++) {
            // A fresh fake in front every time; the same real address behind.
            mvc.perform(post("/api/v1/auth/signin")
                            .header("X-Forwarded-For", "10.0.0." + i + ", " + realAddress)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(SIGN_IN))
                    .andExpect(i < 5 ? status().isUnauthorized() : status().isTooManyRequests());
        }
    }

    @Test
    @DisplayName("the health check is never limited")
    void healthIsExempt() throws Exception {
        for (int i = 0; i < 20; i++) {
            mvc.perform(get("/actuator/health").header("X-Forwarded-For", "203.0.113.40"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("the general tier backstops everything else")
    void generalTierTrips() throws Exception {
        String address = "203.0.113.50";

        // Twelve allowed. These need no token to answer, so they count without
        // anything else getting in the way.
        for (int i = 0; i < 12; i++) {
            mvc.perform(get("/api/v1/banks").header("X-Forwarded-For", address))
                    .andExpect(status().isOk());
        }

        mvc.perform(get("/api/v1/banks").header("X-Forwarded-For", address))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("the refusal is written for the customer, not the attacker")
    void refusalMessage() throws Exception {
        String address = "203.0.113.60";
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/auth/signin")
                    .header("X-Forwarded-For", address)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(SIGN_IN));
        }
        String body = mvc.perform(post("/api/v1/auth/signin")
                        .header("X-Forwarded-For", address)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SIGN_IN))
                .andReturn().getResponse().getContentAsString();

        // Says what to do, not what was detected.
        assertThat(body).contains("Wait a minute").doesNotContain("attack").doesNotContain("limit");
    }
}
