package com.quadrilateral.kudi9ja.domain.notification;

import com.quadrilateral.kudi9ja.common.error.ApiException;
import com.quadrilateral.kudi9ja.config.Kudi9jaProperties;
import com.quadrilateral.kudi9ja.domain.user.User;
import com.quadrilateral.kudi9ja.domain.user.UserRepository;
import com.quadrilateral.kudi9ja.integration.email.Mailer;
import com.quadrilateral.kudi9ja.integration.push.PushSender;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Tells the customer what happened to their money.
 *
 * <p>Every notification is written in the same transaction as the thing it
 * describes. A wallet that was credited and a customer who was never told is a
 * worse outcome than both failing together, so they succeed or fail as one.
 *
 * <p>Push is the opposite: it happens <b>after</b> the transaction commits, and
 * it may fail freely. Two reasons. A notification about a withdrawal that was
 * then rolled back would be a lie, and this is the one place that can guarantee
 * it is never sent — so the send is deferred until the commit actually happens.
 * And Firebase being slow must never be able to fail a customer's withdrawal,
 * so nothing here throws.
 *
 * <p>This is the single point every one of the forty notification sites in the
 * application goes through. Push was added here and nowhere else; not one of
 * those callers changed.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final DeviceTokenRepository devices;
    private final NotificationPreferenceRepository preferences;
    private final PushSender push;
    private final Mailer mailer;
    private final UserRepository users;
    private final Kudi9jaProperties properties;

    /**
     * This service, through its proxy.
     *
     * <p>{@link #deliverAfterCommit} has to call {@link #deliver} the long way
     * round. A plain {@code deliver(...)} is a call on {@code this}, which goes
     * nowhere near the proxy — so neither {@code @Async} nor the {@code
     * REQUIRES_NEW} would have any effect and the delivery would quietly run on
     * the caller's thread, which is the thing being fixed.
     */
    private final NotificationService self;

    @Autowired
    public NotificationService(
            NotificationRepository repository,
            DeviceTokenRepository devices,
            NotificationPreferenceRepository preferences,
            PushSender push,
            Mailer mailer,
            UserRepository users,
            Kudi9jaProperties properties,
            @Lazy NotificationService self) {
        this.self = self;
        this.repository = repository;
        this.devices = devices;
        this.preferences = preferences;
        this.push = push;
        this.mailer = mailer;
        this.users = users;
        this.properties = properties;
    }

    @Transactional
    public Notification push(UUID userId, NotifyKind kind, String title, String body) {
        return push(userId, kind, title, body, null);
    }

    @Transactional
    public Notification push(UUID userId, NotifyKind kind, String title, String body, BigDecimal amount) {
        Notification saved = repository.save(Notification.of(userId, kind, title, body, amount));
        deliverAfterCommit(saved);
        return saved;
    }

    /**
     * Queues the push for after the surrounding transaction commits.
     *
     * <p>If the transaction rolls back the callback never runs, so a customer is
     * never buzzed about money that did not move. Outside a transaction — a
     * scheduled job, say — it sends immediately.
     *
     * <p>Either way the send happens on another thread. {@code afterCommit}
     * runs on the request thread, so a slow provider used to be paid for by the
     * customer waiting on the screen: the money had moved and the row was
     * written, and they were still looking at a spinner because a push had not
     * come back. The worst a stalled provider can now cost is a notification.
     */
    private void deliverAfterCommit(Notification notification) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            self.deliver(notification);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                self.deliver(notification);
            }
        });
    }

    /**
     * Sends one notification to every phone the customer has, if they want it.
     *
     * <p>Nothing here throws. The notification is already saved, so the worst
     * outcome of a failure is that the customer reads it the next time they
     * open the app.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deliver(Notification notification) {
        try {
            if (!wants(notification.getUserId(), notification.getKind())) {
                return;
            }

            // Before the handset check below, and deliberately so. A borrower
            // with no registered phone — the app uninstalled, notifications
            // refused, a handset replaced — still owes the money, and is
            // exactly the person a reminder has to reach.
            emailIfWarranted(notification);

            List<String> tokens = devices.findByUserId(notification.getUserId()).stream()
                    .map(DeviceToken::getToken)
                    .toList();
            if (tokens.isEmpty()) {
                // Worth a line. "No push arrived" and "no handset was
                // registered to send it to" look identical from the outside,
                // and the second is the far more common of the two.
                log.debug("No registered handset for {}; \"{}\" was saved but not pushed",
                        notification.getUserId(), notification.getTitle());
                return;
            }

            Map<String, String> data = new HashMap<>();
            data.put("notificationId", notification.getId().toString());
            data.put("kind", notification.getKind().name());
            if (notification.getAmount() != null) {
                // Read by the app after it opens, not shown on the lock screen.
                data.put("amount", notification.getAmount().toPlainString());
            }

            PushSender.Result result = push.send(
                    tokens,
                    notification.getTitle(),
                    // Amounts never reach a locked screen. See NotifyKind.
                    notification.getKind().lockScreenBody(notification.getBody()),
                    data);

            log.debug("Pushed \"{}\" to {} of {} handset(s) for {}",
                    notification.getTitle(), result.delivered(), tokens.size(),
                    notification.getUserId());

            // A token the provider says is dead is deleted rather than retried
            // for ever — that is how a register becomes mostly dead handsets.
            result.invalidTokens().forEach(devices::deleteByToken);

        } catch (RuntimeException e) {
            log.warn("Could not deliver a push notification to {}", notification.getUserId(), e);
        }
    }

    /**
     * Emails the few notifications that warrant it.
     *
     * <p>Never throws, and never blocks: the notification is already saved, the
     * mailer is asynchronous, and a mail provider having a bad afternoon must
     * not be able to fail the loan sweep that produced this.
     *
     * <p>Sent to the address on the account. An unverified one is still used —
     * a customer who never confirmed their email is a customer who will not get
     * a reminder otherwise, and a reminder sent to an address that bounces
     * costs nothing.
     */
    private void emailIfWarranted(Notification notification) {
        if (!notification.getKind().alsoEmail()) {
            return;
        }
        try {
            User user = users.findById(notification.getUserId()).orElse(null);
            if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
                return;
            }
            // The full body, not the lock-screen version. An inbox is not a
            // screen a stranger reads over a shoulder in a queue, and a reminder
            // that will not say the amount is not much of a reminder.
            mailer.sendPlain(user.getEmail(), notification.getTitle(), notification.getBody());

        } catch (RuntimeException e) {
            log.warn("Could not email notification {} to {}",
                    notification.getId(), notification.getUserId(), e);
        }
    }

    // ── Devices ────────────────────────────────────────────────────────────

    /**
     * Registers a phone, or moves an existing registration to this customer.
     *
     * <p>Matched on the token rather than the customer, because a handset that
     * changed hands arrives carrying a token already on file against whoever
     * had it before. Moving the row is what stops the previous owner being
     * notified about somebody else's money.
     */
    @Transactional
    public DeviceToken registerDevice(
            UUID userId, String token, DevicePlatform platform, String deviceLabel) {

        if (token == null || token.isBlank()) {
            throw ApiException.validation("A device token is needed.");
        }
        return devices.findByToken(token)
                .map(existing -> {
                    existing.touch(userId, deviceLabel);
                    return devices.save(existing);
                })
                .orElseGet(() -> devices.save(
                        DeviceToken.register(userId, token, platform, deviceLabel)));
    }

    /** Drops one phone, on sign-out from it. */
    @Transactional
    public void unregisterDevice(String token) {
        devices.deleteByToken(token);
    }

    /** Drops every phone, when an account is closed or signed out everywhere. */
    @Transactional
    public int unregisterAll(UUID userId) {
        return devices.deleteByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<DeviceToken> devicesFor(UUID userId) {
        return devices.findByUserId(userId);
    }

    /** Nightly housekeeping: handsets nobody has opened in months. */
    @Transactional
    public int purgeStaleDevices(Instant now) {
        int days = properties.push().staleDeviceDays();
        return devices.deleteStaleBefore(now.minus(days, ChronoUnit.DAYS));
    }

    // ── Preferences ────────────────────────────────────────────────────────

    /**
     * Whether this customer wants push for this kind.
     *
     * <p>Defaults to yes. A customer who has never opened the settings screen
     * should hear about their money, and the kinds that matter most cannot be
     * switched off at all.
     */
    @Transactional(readOnly = true)
    public boolean wants(UUID userId, NotifyKind kind) {
        if (!kind.optional()) {
            return true;
        }
        return preferences.findByUserIdAndKind(userId, kind)
                .map(NotificationPreference::isEnabled)
                .orElse(true);
    }

    /** Which groups this customer has switched off. */
    @Transactional(readOnly = true)
    public Set<NotifyKind> mutedKinds(UUID userId) {
        return preferences.findByUserId(userId).stream()
                .filter(p -> !p.isEnabled())
                .map(NotificationPreference::getKind)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Turns one group on or off.
     *
     * <p>Refuses to silence a group that is not optional. Security alerts and
     * money movements stay on: switching those off is indistinguishable from
     * not being told, and an attacker who could do it would do it first.
     */
    @Transactional
    public void setPreference(UUID userId, NotifyKind kind, boolean enabled) {
        if (!enabled && !kind.optional()) {
            throw ApiException.validation(
                    kind.label() + " cannot be switched off. They are how you find out "
                            + "your money has moved, or that somebody else is in your account.");
        }
        NotificationPreference preference = preferences.findByUserIdAndKind(userId, kind)
                .orElseGet(() -> NotificationPreference.of(userId, kind));
        preference.setEnabled(enabled);
        preferences.save(preference);
    }

    // ── Reads ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<Notification> list(UUID userId, Pageable pageable) {
        return repository.findByUserIdAndClearedAtIsNullOrderByCreatedAtDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return repository.countByUserIdAndReadFalseAndClearedAtIsNull(userId);
    }

    @Transactional
    public int markAllRead(UUID userId) {
        return repository.markAllRead(userId);
    }

    @Transactional
    public int clearAll(UUID userId) {
        return repository.clearAll(userId, Instant.now());
    }
}
