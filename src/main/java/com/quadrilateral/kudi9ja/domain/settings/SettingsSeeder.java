package com.quadrilateral.kudi9ja.domain.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Puts version one of the platform settings in place on an empty database.
 *
 * <p>Almost everything in this application reads the settings, and most of it
 * reads them through {@code currentReadOnly}, which refuses rather than seeding
 * — a read on a request path must not quietly write a row. So the seeding has
 * to happen once, at startup, before the first request arrives.
 *
 * <p>The defaults are the ones compiled into the Flutter client, so a fresh
 * deployment prices exactly as the app has always said it would: 17% a year on
 * savings, the published loan card, ₦5,000 flat management fee to ₦250,000, then 2%.
 *
 * <p>Seeding never overwrites. Once a version exists this does nothing at all,
 * which is what makes it safe to run on every boot — including a boot that
 * follows an admin changing the rates five minutes earlier.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SettingsSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SettingsSeeder.class);

    private final SettingsService settings;

    public SettingsSeeder(SettingsService settings) {
        this.settings = settings;
    }

    @Override
    public void run(ApplicationArguments args) {
        // current() seeds an empty database and returns what is already there
        // otherwise, so this is one call rather than a check and a write.
        PlatformSettings inForce = settings.current();
        log.info(
                "Platform settings version {} in force — savings {}% a year, loans priced across {} tenures",
                inForce.getVersion(),
                inForce.getSavingsAnnualRate().multiply(java.math.BigDecimal.valueOf(100)).stripTrailingZeros(),
                inForce.getLoanRates().size());
    }
}
