package com.quadrilateral.kudi9ja.integration.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quadrilateral.kudi9ja.common.error.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Credit score from Dojah")
class DojahCreditBureauTest {

    private final DojahCreditBureau dojah =
            new DojahCreditBureau("app", "test_sk_x", "https://sandbox.dojah.io", new ObjectMapper());

    @Test
    @DisplayName("reads the score, rating and arrears")
    void found() {
        CreditBureau.Report r = dojah.read(200, """
                {"entity":{"bvn":"1*****78901","name":"Anon Doe","score":{"hasLoans":"YES",
                 "totalNoOfDelinquentFacilities":2,"ficoScore":{"score":610,"rating":"AVERAGE",
                 "reasons":"There is serious delinquency"},"lastReportedDate":"30-APR-2021"}}}
                """);
        assertThat(r.found()).isTrue();
        assertThat(r.score()).isEqualTo(610);
        assertThat(r.rating()).isEqualTo("AVERAGE");
        assertThat(r.hasLoans()).isEqualTo("YES");
        assertThat(r.delinquentFacilities()).isEqualTo(2);
        assertThat(r.lastReported()).isEqualTo("30-APR-2021");
    }

    @Test
    @DisplayName("no record at the bureaus is a result, not an error")
    void noRecord() {
        assertThat(dojah.read(404, "{\"error\":\"No record found\"}").found()).isFalse();
    }

    @Test
    @DisplayName("an empty wallet says so")
    void emptyWallet() {
        assertThatThrownBy(() -> dojah.read(402, "{\"error\":\"Insufficient wallet balance\"}"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("wallet is empty");
    }
}
