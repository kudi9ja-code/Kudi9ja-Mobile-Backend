package com.quadrilateral.kudi9ja.integration.push;

import java.util.List;
import java.util.Map;

/**
 * How a notification reaches a phone that is not currently open.
 *
 * <p>An interface rather than a Firebase call, for the same reason the mailer
 * is one: the flows that notify a customer should not know what carries the
 * message, and a test should not need a Google project to run.
 *
 * <p>Delivery is <b>best effort and never blocking</b>. A notification is
 * already written to the database in the same transaction as the thing it
 * describes; push is a second, faster copy of it. If Firebase is slow or down,
 * the customer still has the notification the next time they open the app, and
 * the withdrawal that triggered it must not fail because a message could not be
 * delivered.
 */
public interface PushSender {

    /**
     * Sends one message to every device given.
     *
     * @param tokens the registration tokens to deliver to
     * @param title  the line shown on the lock screen
     * @param body   the line beneath it, already redacted for a lock screen by
     *               {@code NotifyKind.lockScreenBody}
     * @param data   what the app reads when the notification is tapped, so it
     *               can open the right screen rather than the home page
     * @return the tokens the provider rejected as permanently invalid, which
     *         the caller should delete
     */
    Result send(List<String> tokens, String title, String body, Map<String, String> data);

    /**
     * The same, with a picture drawn large beneath the text.
     *
     * <p>Only the operating system draws it, so it shows while the app is
     * closed or in the background. The installed app draws foreground
     * messages itself and shows the text alone.
     *
     * @param imageUrl a public {@code https} link to a JPEG or PNG, or null
     */
    default Result send(
            List<String> tokens, String title, String body, Map<String, String> data, String imageUrl) {
        return send(tokens, title, body, data);
    }

    /**
     * @param delivered how many the provider accepted
     * @param invalidTokens tokens the provider says will never work again —
     *                      an app that was uninstalled, or a token that has
     *                      rotated. These are deleted rather than retried,
     *                      because retrying them for ever is how a token table
     *                      becomes mostly dead handsets.
     */
    record Result(int delivered, List<String> invalidTokens) {

        public static Result none() {
            return new Result(0, List.of());
        }
    }
}
