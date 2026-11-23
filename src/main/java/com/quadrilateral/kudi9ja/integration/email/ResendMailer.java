package com.quadrilateral.kudi9ja.integration.email;

import com.quadrilateral.kudi9ja.common.util.Masks;
import com.quadrilateral.kudi9ja.config.Kudi9jaProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Email through Resend.
 *
 * <p>One POST against their REST API. There is no official Java SDK and none is
 * needed — the request is a JSON body and a bearer token, and a dependency that
 * wrapped it would be more code than it saved.
 *
 * <p>Sending is {@code @Async} and never throws. The three things this carries
 * are a one-time code, a security alert and a money confirmation, and each is
 * triggered by something that has <b>already happened</b> — an account was
 * opened, a payout account moved, a payment was confirmed. None of those should
 * fail because a mail provider is slow, so a failure here is logged and the
 * calling transaction is left alone.
 *
 * <p>That does mean a customer can be left waiting for a code that never
 * arrives, which is why the failure is logged at error rather than swallowed
 * quietly: it is an operational problem, not a customer mistake.
 */
@Component
@ConditionalOnProperty(name = "kudi9ja.mail.provider", havingValue = "resend")
public class ResendMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(ResendMailer.class);
    private static final String ENDPOINT = "https://api.resend.com/emails";

    private final RestClient http = RestClient.create();
    private final TemplateEngine templateEngine;
    private final Kudi9jaProperties properties;

    public ResendMailer(TemplateEngine templateEngine, Kudi9jaProperties properties) {
        this.templateEngine = templateEngine;
        this.properties = properties;

        if (properties.mail().apiKey() == null || properties.mail().apiKey().isBlank()) {
            throw new IllegalStateException(
                    "Resend is configured as the mail provider but RESEND_API_KEY is empty. "
                            + "Sign-up would stall at the email step with no code ever arriving.");
        }
        log.info("Email will be sent through Resend, from {}", properties.mail().from());
    }

    @Override
    @Async
    public void send(String to, String subject, String template, Map<String, Object> model) {
        deliver(to, subject, render(template, model));
    }

    @Override
    @Async
    public void sendPlain(String to, String subject, String body) {
        // Wrapped in the same shell as everything else, so a plain message still
        // carries the company name and the RC number the documents require.
        deliver(to, subject, render("plain", Map.of("body", body)));
    }

    /**
     * Renders a template with the company details every message must carry.
     *
     * <p>Every contract, receipt and legal document names <b>Kudi9ja
     * Limited</b>, not the product. The footer is not decoration:
     * it is how a customer can tell a real Kudi9ja email from a convincing one.
     */
    private String render(String template, Map<String, Object> model) {
        Kudi9jaProperties.Company company = properties.company();

        Map<String, Object> enriched = new HashMap<>(model);
        enriched.putIfAbsent("company", company.legalName());
        enriched.putIfAbsent("rcNumber", company.rcNumber());
        enriched.putIfAbsent("product", company.productName());
        enriched.putIfAbsent("supportEmail", company.supportEmail());
        enriched.putIfAbsent("supportPhone", company.supportPhone());

        Context context = new Context();
        context.setVariables(enriched);
        return templateEngine.process("email/" + template, context);
    }

    private void deliver(String to, String subject, String html) {
        try {
            http.post()
                    .uri(ENDPOINT)
                    .header("Authorization", "Bearer " + properties.mail().apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "from", properties.mail().from(),
                            "to", List.of(to),
                            "subject", subject,
                            "html", html,
                            // A no-reply address that swallows replies is how
                            // somebody trying to report fraud gets ignored.
                            "reply_to", properties.mail().replyTo()))
                    .retrieve()
                    .toBodilessEntity();

            log.debug("Sent '{}' to {}", subject, Masks.email(to));

        } catch (Exception e) {
            // The address is masked even here. A log line is read by more people
            // than a database row, and a customer's email is theirs.
            log.error("Could not send '{}' to {}", subject, Masks.email(to), e);
        }
    }
}
