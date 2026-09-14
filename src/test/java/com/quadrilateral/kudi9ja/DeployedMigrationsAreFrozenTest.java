package com.quadrilateral.kudi9ja;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.CRC32;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A migration that has reached production is a historical record, not a
 * document to keep current.
 *
 * <p>This test exists because two comments in {@code V3} were reworded — "two
 * guarantors" to "one" — after the file had already run against the live
 * database. Nothing about the SQL changed. Flyway does not care: it checksums
 * the whole file, comments included, and on the next start refused to run at
 * all with "checksum mismatch for migration version 3". Two deploys failed on
 * it, and the feature that was actually being shipped never reached anyone.
 *
 * <p>So every migration that has been applied in production is pinned here by
 * the checksum Flyway will compute for it. Touching one — even a comment, even
 * whitespace — fails this test at compile time on a laptop, rather than at
 * boot time on a server that is now down.
 *
 * <p>When a <b>new</b> migration ships, add its checksum here <i>after</i> it
 * has deployed. The number is printed by the test if it is missing.
 */
@DisplayName("Migrations that have reached production are frozen")
class DeployedMigrationsAreFrozenTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /**
     * The checksum Flyway recorded in {@code flyway_schema_history} for each
     * migration when it ran against the live database. From Render's own
     * "Applied to database" line on the failed deploy, for V3; computed the same
     * way for the others.
     */
    private static final Map<String, Integer> DEPLOYED = Map.of(
            "V1__initial_schema.sql", -173574079,
            "V2__payment_reference.sql", -1815322573,
            "V3__loan_applications.sql", -1240739060);

    @Test
    @DisplayName("no deployed migration has changed, not even a comment")
    void deployedMigrationsUnchanged() throws IOException {
        for (Map.Entry<String, Integer> pinned : DEPLOYED.entrySet()) {
            Path file = MIGRATIONS.resolve(pinned.getKey());
            assertThat(file).as("%s must still exist", pinned.getKey()).exists();
            assertThat(flywayChecksum(file))
                    .as("%s has been edited since it ran in production. Flyway will refuse "
                            + "to start against a database that already holds the old version. "
                            + "Put the file back exactly as it was and make the change in a new "
                            + "migration instead.", pinned.getKey())
                    .isEqualTo(pinned.getValue());
        }
    }

    @Test
    @DisplayName("every migration on disk is either pinned or newer than the last pin")
    void newMigrationsAreNoticed() throws IOException {
        try (var files = Files.list(MIGRATIONS)) {
            files.filter(p -> p.getFileName().toString().startsWith("V"))
                    .sorted()
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        if (!DEPLOYED.containsKey(name)) {
                            // Not a failure — a new migration is expected to be
                            // unpinned until it has deployed. Say what to add.
                            try {
                                System.out.println("Unpinned migration " + name
                                        + " — once deployed, pin it with checksum "
                                        + flywayChecksum(p));
                            } catch (IOException e) {
                                throw new IllegalStateException(e);
                            }
                        }
                    });
        }
    }

    /**
     * Flyway's checksum, reproduced: a CRC32 carried across every line of the
     * file, with a leading BOM dropped and line endings normalised. The number
     * this yields is the one in {@code flyway_schema_history}.
     */
    static int flywayChecksum(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        CRC32 crc = new CRC32();
        for (String line : text.split("\n", -1)) {
            crc.update(line.getBytes(StandardCharsets.UTF_8));
        }
        return (int) crc.getValue();
    }
}
