package com.quadrilateral.kudi9ja;

import static org.assertj.core.api.Assertions.assertThat;

import com.quadrilateral.kudi9ja.domain.legal.LegalAcceptanceRepository;
import com.quadrilateral.kudi9ja.domain.legal.LegalDocument;
import com.quadrilateral.kudi9ja.domain.legal.LegalDocumentKind;
import com.quadrilateral.kudi9ja.domain.legal.LegalDocumentRepository;
import com.quadrilateral.kudi9ja.domain.legal.LegalSeeder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * When a legal document may be rewritten, and when it may not.
 *
 * <p>Two failures are possible here and they pull in opposite directions.
 *
 * <p>Freeze too early and a typo, a wrong address, or a support number that
 * does not answer is stuck in production until somebody remembers to bump a
 * version — which is how this test came to exist: the phone number was removed
 * from the shipped file, the file was deployed, and the live Terms carried on
 * publishing the old number because the seeder saw the version already existed
 * and returned.
 *
 * <p>Freeze too late and the document a customer agreed to changes underneath
 * them, which makes the acceptance record a lie and is far worse than a stale
 * phone number.
 *
 * <p>The line between them is not a flag anybody has to remember to set. It is
 * whether a single person has accepted that exact version.
 */
@SpringBootTest
@ActiveProfiles("dev")
@DisplayName("Legal documents")
class LegalDocumentFreezeTest {

    @Autowired
    private LegalSeeder seeder;

    @Autowired
    private LegalDocumentRepository documents;

    @Autowired
    private LegalAcceptanceRepository acceptances;

    private LegalDocument terms() {
        return documents.findByKindOrderByEffectiveFromDesc(LegalDocumentKind.TERMS)
                .stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("the Terms were never seeded"));
    }

    @Test
    @DisplayName("the shipped documents are published on startup")
    void seeded() {
        for (LegalDocumentKind kind : LegalDocumentKind.values()) {
            assertThat(documents.findByKindOrderByEffectiveFromDesc(kind))
                    .as("%s was not published", kind)
                    .isNotEmpty();
        }
    }

    /**
     * Every shipped version is published, and the newest is the one in force.
     * Version 1.1 of the Privacy Policy and the Terms removed the credit
     * score; 1.0 stays on record for the customers who accepted it.
     */
    @Test
    @DisplayName("every shipped version is published and the newest binds")
    void everyShippedVersionIsPublished() {
        assertThat(documents.findByKindAndVersion(LegalDocumentKind.PRIVACY, "1.0")).isPresent();
        assertThat(documents.findByKindAndVersion(LegalDocumentKind.PRIVACY, "1.1")).isPresent();
        assertThat(documents.findByKindAndVersion(LegalDocumentKind.TERMS, "1.1")).isPresent();

        LegalDocument privacy = documents
                .findByKindOrderByEffectiveFromDesc(LegalDocumentKind.PRIVACY).get(0);
        assertThat(privacy.getVersion()).isEqualTo("1.1");
        assertThat(privacy.getChangeSummary()).contains("credit score");
        assertThat(privacy.getBodyJson())
                .as("the policy must not describe a score the product no longer has")
                .doesNotContain("Credit scoring and automated decisions")
                .contains("How we decide on a loan");
    }

    @Test
    @DisplayName("versions sort numerically, not as strings")
    void versionsSortNumerically() {
        assertThat(LegalSeeder.compareVersions("1.9", "1.10")).isNegative();
        assertThat(LegalSeeder.compareVersions("1.1", "1.0")).isPositive();
        assertThat(LegalSeeder.compareVersions("2.0", "1.10")).isPositive();
    }

    /**
     * The number that used to be published as a way to reach the company. It
     * does not answer, and this asserts the live document no longer offers it.
     */
    @Test
    @DisplayName("no document publishes the retired support number")
    void noRetiredNumber() {
        for (LegalDocumentKind kind : LegalDocumentKind.values()) {
            for (LegalDocument document :
                    documents.findByKindOrderByEffectiveFromDesc(kind)) {
                assertThat(document.getBodyJson())
                        .as("%s still publishes a number that rings out", kind)
                        .doesNotContain("5834 952");
            }
        }
    }

    /**
     * Re-running the seeder over an unaccepted document picks up whatever the
     * shipped file now says, rather than leaving the old text in place for ever.
     */
    @Test
    @DisplayName("an unaccepted document is refreshed from the shipped file")
    void refreshesWhileUnaccepted() {
        LegalDocument before = terms();
        String version = before.getVersion();

        // Something that could only have come from an edit nobody made.
        before.setSummary("A stale summary that the shipped file does not contain.");
        documents.save(before);

        ApplicationArguments args = new DefaultApplicationArguments();
        seeder.run(args);

        LegalDocument after = terms();
        assertThat(after.getVersion()).isEqualTo(version);
        assertThat(after.getSummary())
                .as("the seeder left stale text in a document nobody had accepted")
                .isNotEqualTo("A stale summary that the shipped file does not contain.");
    }

    /**
     * The property that actually matters. Once one person has agreed to a
     * version, its text is what they agreed to, and no deploy may quietly
     * change it — a new version is the only honest way to change the terms.
     */
    @Test
    @DisplayName("a document somebody has accepted is never rewritten")
    void frozenOnceAccepted() {
        LegalDocument document = terms();
        assertThat(acceptances.countByDocumentId(document.getId()))
                .as("this test needs a document nobody has accepted yet")
                .isZero();

        String agreedText = "The exact wording somebody agreed to.";
        document.setSummary(agreedText);
        documents.save(document);

        // Somebody accepts it. From here the text is a record, not a draft.
        acceptances.save(com.quadrilateral.kudi9ja.domain.legal.LegalAcceptance.of(
                java.util.UUID.randomUUID(), document, "test device", "127.0.0.1"));

        seeder.run(new DefaultApplicationArguments());

        assertThat(terms().getSummary())
                .as("a deploy rewrote a document a customer had already agreed to")
                .isEqualTo(agreedText);
    }
}
