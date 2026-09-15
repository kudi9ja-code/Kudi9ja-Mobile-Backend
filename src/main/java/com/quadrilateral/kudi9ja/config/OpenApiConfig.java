package com.quadrilateral.kudi9ja.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The generated API documentation.
 *
 * <p>Written as a description of the contract rather than a list of paths,
 * because the paths are the easy half. What a reader of this API most needs to
 * know is which of its rules are not negotiable, and those are stated here so
 * they are in front of anyone integrating before they discover them as
 * refusals.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    private final Kudi9jaProperties properties;

    public OpenApiConfig(Kudi9jaProperties properties) {
        this.properties = properties;
    }

    @Bean
    public OpenAPI kudi9jaOpenApi() {
        Kudi9jaProperties.Company company = properties.company();

        return new OpenAPI()
                .info(new Info()
                        .title("Kudi9ja API")
                        .version("v1")
                        .description(description())
                        .contact(new Contact()
                                .name(company.legalName())
                                .email(company.supportEmail()))
                        .license(new License()
                                .name(company.legalName() + " (" + company.rcNumber() + ")")))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("""
                                The access token from /auth/signin or /auth/refresh.

                                Short-lived, because the app locks itself after two idle minutes \
                                and there is nothing to be gained by a long-lived bearer token \
                                sitting on a phone. Refresh tokens rotate: the one presented is \
                                spent and a new one comes back, so a stolen refresh token is good \
                                for one exchange rather than for as long as the session lives.

                                The token says who the caller is. It never says what they may do: \
                                admin access is a database grant re-checked on every request, \
                                because a claim the client holds is a claim the client can \
                                forge.""")))
                // Applied globally. Everything needs a token unless the security
                // configuration lists it as open, so documenting it the other way
                // round would describe the opposite of what the server does.
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    private String description() {
        Kudi9jaProperties.Company company = properties.company();

        return """
                The server behind the Kudi9ja mobile app.

                **%s (%s)**, Lagos, Nigeria. Kudi9ja is its product — every contract, \
                receipt and legal document names the company, not the product.

                ### Rules that shape this whole API

                - **Kudi9ja issues no account numbers.** What a customer has is a *customer \
                reference* (`K9-A1B2C3`) for matching payments. It is not payable into. Money \
                leaves the wallet to a bank account the customer already holds, in their own name.
                - **No money enters a wallet without an admin confirming it** against the bank \
                statement. There is no card, no USSD and no instant credit — bank transfer only, \
                claimed in the app with a receipt.
                - **Every pay-in carries its own unique reference**, not one per customer. Two \
                transfers of the same amount on the same day are otherwise indistinguishable on a \
                statement.
                - **Withdrawals debit at request, not at approval**, so the same money cannot be \
                spent twice while it is under review. Declining refunds in full by reversing the \
                pending transaction; the ledger is append-only and nothing is deleted.
                - **A running plan or loan keeps the terms it was opened on.** Rate changes never \
                rewrite history.
                - **Interest never compounds**, on savings or on loans. It is flat, computed once.
                - **The client computes nothing.** Balances, interest, loan pricing \
                and eligibility are all derived here; the app displays what it is given.
                - **Every money-moving endpoint takes an `Idempotency-Key` header** and takes the \
                transaction PIN in the body. A retry must not double a payment, and a client's \
                assertion that a PIN was entered is worth nothing.
                - **Every admin action is written to an append-only audit log**, and every admin \
                permission is re-checked against the database on each request.

                The wallet is **not a bank account** and is **not NDIC-insured**. Kudi9ja asserts \
                no licence and no CBN compliance; the legal documents deny these rather than \
                claiming them.

                Support: %s · Legal: %s · Privacy: %s
                """.formatted(
                        company.legalName(),
                        company.rcNumber(),
                        company.supportEmail(),
                        company.legalEmail(),
                        company.privacyEmail());
    }
}
