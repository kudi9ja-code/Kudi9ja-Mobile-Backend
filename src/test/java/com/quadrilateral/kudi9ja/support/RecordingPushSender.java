package com.quadrilateral.kudi9ja.support;

import com.quadrilateral.kudi9ja.integration.push.PushSender;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A push sender that keeps what it was asked to deliver.
 *
 * <p>The delivery itself is Google's network, so what a test can usefully check
 * is what we handed over: which devices, what the lock screen would have said,
 * and — most of all — what it would <b>not</b> have said. Reading the recorded
 * body is the only way to prove an amount never left the building.
 */
public class RecordingPushSender implements PushSender {

    private final List<Sent> sent = new ArrayList<>();

    /** Tokens the fake provider should reject, so the caller's cleanup can be tested. */
    private final List<String> rejects = new ArrayList<>();

    @Override
    public synchronized Result send(
            List<String> tokens, String title, String body, Map<String, String> data) {
        return send(tokens, title, body, data, null);
    }

    @Override
    public synchronized Result send(
            List<String> tokens, String title, String body, Map<String, String> data, String imageUrl) {

        sent.add(new Sent(List.copyOf(tokens), title, body, Map.copyOf(data), imageUrl));

        List<String> invalid = tokens.stream().filter(rejects::contains).toList();
        return new Result(tokens.size() - invalid.size(), invalid);
    }

    public synchronized List<Sent> all() {
        return List.copyOf(sent);
    }

    /** The one message, failing loudly when there is not exactly one. */
    public synchronized Sent only() {
        if (sent.size() != 1) {
            throw new AssertionError("expected exactly one push, got " + sent.size() + ": " + sent);
        }
        return sent.get(0);
    }

    /** Makes the fake provider treat this token as permanently dead. */
    public synchronized void reject(String token) {
        rejects.add(token);
    }

    public synchronized void clear() {
        sent.clear();
        rejects.clear();
    }

    public record Sent(
            List<String> tokens, String title, String body, Map<String, String> data, String imageUrl) {
    }

    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        public RecordingPushSender recordingPushSender() {
            return new RecordingPushSender();
        }
    }
}
