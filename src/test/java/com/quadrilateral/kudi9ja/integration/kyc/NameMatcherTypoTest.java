package com.quadrilateral.kudi9ja.integration.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NameMatcherTypoTest {

    @Test
    void oneLetterSlipInALongPartIsAccepted() {
        assertThat(NameMatcher.matchesAllowingTypo("Francis segun micheal", "FRANCIS SEGUN MICHAEL")).isTrue();
        assertThat(NameMatcher.matchesAllowingTypo("Francis segun micheal", "FRANCES SEGUN MICHEAL")).isTrue();
        assertThat(NameMatcher.matchesAllowingTypo("Chioma Adeyemi", "ADEYEMI CHIOMAH")).isTrue();
    }

    @Test
    void shortFormOfAnOluwaNameIsAccepted() {
        assertThat(NameMatcher.matchesAllowingTypo("Francis Segun Micheal", "OLUWASEGUN MICHEAL FRANCIS")).isTrue();
        assertThat(NameMatcher.matchesAllowingTypo("Femi Adeyemi", "OLUFEMI ADEYEMI")).isTrue();
        assertThat(NameMatcher.matchesAllowingTypo("Oluwasegun Francis", "SEGUN FRANCIS")).isTrue();
        // Only the Oluwa/Olu prefix; any other ending is a different name.
        assertThat(NameMatcher.matchesAllowingTypo("Segun Francis", "ADESEGUN FRANCIS")).isFalse();
        assertThat(NameMatcher.matches("Francis Segun Micheal", "OLUWASEGUN MICHEAL FRANCIS")).isFalse();
    }

    @Test
    void strictMatchIsUnchanged() {
        assertThat(NameMatcher.matches("Francis segun micheal", "FRANCIS SEGUN MICHAEL")).isFalse();
    }

    @Test
    void differentPeopleAreStillRefused() {
        assertThat(NameMatcher.matchesAllowingTypo("Francis segun micheal", "JOHN SEGUN MICHAEL")).isFalse();
        assertThat(NameMatcher.matchesAllowingTypo("Chioma Adeyemi", "ADEYEMI CHUKWU")).isFalse();
        // Short parts get no slack.
        assertThat(NameMatcher.matchesAllowingTypo("Ade Bola", "ADA BOLA")).isFalse();
        // Two slips in one part is too many.
        assertThat(NameMatcher.matchesAllowingTypo("Francis Segun", "FRANKIE SEGUN")).isFalse();
    }
}
