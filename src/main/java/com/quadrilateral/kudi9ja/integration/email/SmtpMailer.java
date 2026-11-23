package com.quadrilateral.kudi9ja.integration.email;

import com.quadrilateral.kudi9ja.config.Kudi9jaProperties;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Sends real mail over SMTP.
 *
 * <p>Sending is asynchronous and failures are logged rather than thrown. A mail
 * server being slow or briefly down must not fail the request that triggered
 * the message: a customer whose withdrawal was approved should not see an error
 * because the receipt email could not go out. The one exception is the signup
 * OTP, whose caller waits on delivery, because a code nobody receives is worse
 * than an honest failure.
 */
@Component
@ConditionalOnProperty(name = "kudi9ja.mail.provider", havingValue = "smtp")
public class SmtpMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailer.class);

    private final JavaMailSender sender;
    private final TemplateEngine templateEngine;
    private final Kudi9jaProperties properties;
    private final String from;

    public SmtpMailer(
            JavaMailSender sender,
            TemplateEngine templateEngine,
            Kudi9jaProperties properties,
            @Value("${kudi9ja.mail.from:no-reply@kudi9ja.com}") String from) {
        this.sender = sender;
        this.templateEngine = templateEngine;
        this.properties = properties;
        this.from = from;
    }

    @Override
    @Async
    public void send(String to, String subject, String template, Map<String, Object> model) {
        try {
            Context context = new Context();
            Map<String, Object> enriched = new HashMap<>(model);
            // Every message names the company, not the product: it is
            // Kudi9ja Limited that contracts with the
            // customer, and the footer has to say so.
            enriched.putIfAbsent("company", properties.company().legalName());
            enriched.putIfAbsent("rcNumber", properties.company().rcNumber());
            enriched.putIfAbsent("product", properties.company().productName());
            enriched.putIfAbsent("supportEmail", properties.company().supportEmail());
            enriched.putIfAbsent("supportPhone", properties.company().supportPhone());
            context.setVariables(enriched);

            String html = templateEngine.process("email/" + template, context);

            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            sender.send(message);
            log.debug("Sent '{}' to {}", subject, com.quadrilateral.kudi9ja.common.util.Masks.email(to));
        } catch (Exception e) {
            log.error("Could not send '{}' to {}", subject,
                    com.quadrilateral.kudi9ja.common.util.Masks.email(to), e);
        }
    }

    @Override
    @Async
    public void sendPlain(String to, String subject, String body) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            sender.send(message);
        } catch (Exception e) {
            log.error("Could not send '{}' to {}", subject,
                    com.quadrilateral.kudi9ja.common.util.Masks.email(to), e);
        }
    }
}
