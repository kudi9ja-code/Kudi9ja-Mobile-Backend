package com.quadrilateral.kudi9ja.integration.push;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * A push sender for local development and tests.
 *
 * <p>Records that a message would have gone out, to how many devices, and what
 * the lock screen would have said. It logs the body deliberately: unlike a
 * one-time code, a notification body is written to be read by the customer and
 * is already in the database, so there is nothing here a log file should not
 * hold.
 *
 * <p>Bound whenever no real provider is configured, so a deployment that has
 * not set up Firebase keeps working — customers simply read their notifications
 * when they open the app, which is exactly where the product was before push
 * existed.
 */
@Component
@ConditionalOnMissingBean(FirebasePushSender.class)
public class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @Override
    public Result send(List<String> tokens, String title, String body, Map<String, String> data) {
        if (tokens.isEmpty()) {
            return Result.none();
        }
        log.info("[push disabled] would send '{}' — '{}' to {} device(s)", title, body, tokens.size());
        return new Result(tokens.size(), List.of());
    }

    @Override
    public Result send(
            List<String> tokens, String title, String body, Map<String, String> data, String imageUrl) {
        if (imageUrl != null && !tokens.isEmpty()) {
            log.info("[push disabled] the next message would carry the picture {}", imageUrl);
        }
        return send(tokens, title, body, data);
    }
}
