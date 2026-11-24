package com.quadrilateral.kudi9ja.integration.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.domain.kyc.KycService;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How Dojah's answers to a BVN lookup are read. */
@DisplayName("BVN check with Dojah")
class DojahIdentityVerifierTest {

    private final DojahIdentityVerifier dojah =
            new DojahIdentityVerifier("app", "test_sk_x", "https://sandbox.dojah.io", new ObjectMapper());

    private static final String FOUND = """
            {"entity":{"bvn":"22222222222","first_name":"JOHN","last_name":"DOE",
             "middle_name":"ANON","date_of_birth":"1997-05-16","phone_number1":"08012345678"}}
            """;

    @Test
    @DisplayName("a found BVN carries the bank's name and date of birth")
    void found() {
        IdentityVerifier.Verification v = dojah.read(200, FOUND);
        assertThat(v.verified()).isTrue();
        assertThat(v.fullName()).isEqualTo("JOHN ANON DOE");
        assertThat(v.dateOfBirth()).isEqualTo(LocalDate.of(1997, 5, 16));
    }

    @Test
    @DisplayName("the customer's own BVN passes the name and birthday check")
    void matchesCustomer() {
        KycService kyc = new KycService(new FixedAnswer(dojah.read(200, FOUND)), null);
        kyc.verifyBvn("22222222222", "John Doe", LocalDate.of(1997, 5, 16));
    }

    @Test
    @DisplayName("somebody else's BVN is refused")
    void somebodyElses() {
        KycService kyc = new KycService(new FixedAnswer(dojah.read(200, FOUND)), null);
        assertThatThrownBy(() -> kyc.verifyBvn("22222222222", "Chioma Adeyemi", LocalDate.of(1997, 5, 16)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("does not match");
        assertThatThrownBy(() -> kyc.verifyBvn("22222222222", "John Doe", LocalDate.of(1990, 1, 1)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("date of birth");
    }

    @Test
    @DisplayName("a BVN Dojah does not know is a plain failure")
    void notFound() {
        IdentityVerifier.Verification v = dojah.read(400, "{\"error\":\"BVN not found\"}");
        assertThat(v.verified()).isFalse();
        assertThat(v.reason()).contains("not found");
    }

    @Test
    @DisplayName("a wrong key or empty wallet never passes, and asks the customer to try later")
    void ourProblem() {
        assertThatThrownBy(() -> dojah.read(401, "{\"error\":\"Unauthorized\"}"))
                .isInstanceOf(ApiException.class).hasMessageContaining("try again");
        assertThatThrownBy(() -> dojah.read(402, "{\"error\":\"Insufficient wallet balance\"}"))
                .isInstanceOf(ApiException.class).hasMessageContaining("try again");
        assertThatThrownBy(() -> dojah.read(500, "<html>"))
                .isInstanceOf(ApiException.class).hasMessageContaining("try again");
    }

    @Test
    @DisplayName("dates come in more than one shape")
    void dates() {
        assertThat(DojahIdentityVerifier.date("16-May-1997")).isEqualTo(LocalDate.of(1997, 5, 16));
        assertThat(DojahIdentityVerifier.date("16-MAY-1997")).isEqualTo(LocalDate.of(1997, 5, 16));
        assertThat(DojahIdentityVerifier.date("16/05/1997")).isEqualTo(LocalDate.of(1997, 5, 16));
        assertThat(DojahIdentityVerifier.date("nonsense")).isNull();
    }

    @Test
    @DisplayName("refuses to start without keys")
    void needsKeys() {
        assertThatThrownBy(() -> new DojahIdentityVerifier("", "", "https://api.dojah.io", new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class);
    }

    private record FixedAnswer(IdentityVerifier.Verification answer) implements IdentityVerifier {
        @Override
        public Verification verifyBvn(String bvn) {
            return answer;
        }

        @Override
        public Verification verifyNin(String nin) {
            return answer;
        }
    }
}
