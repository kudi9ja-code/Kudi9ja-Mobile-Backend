package com.quadrilateral.kudi9ja;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * The server behind the Kudi9ja mobile app.
 *
 * <p>Kudi9ja is a product of Kudi9ja Limited (RC 9405885),
 * Lagos, Nigeria. Every contract, receipt and legal document names the company,
 * not the product.
 *
 * <p>The client is not the source of truth. Balances, interest, credit scores
 * and loan pricing are all computed here; the client displays what it is given.
 *
 * <p>Scheduling is switched on by {@code SchedulingConfig} rather than here, so
 * that the property which disables the jobs actually disables them. Enabling it
 * in both places would leave the sweeps running whatever the configuration
 * said.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class Kudi9jaApplication {

    public static void main(String[] args) {
        SpringApplication.run(Kudi9jaApplication.class, args);
    }
}
