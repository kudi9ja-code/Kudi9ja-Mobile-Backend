package com.quadrilateral.kudi9ja.domain.support;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live part of help and complaints: open pages hold a server-sent event
 * stream, and a new message is pushed down it the moment it is saved.
 *
 * <p>Server-sent events rather than WebSockets because the traffic is one way
 * — messages are still sent by an ordinary POST, authenticated and
 * rate-limited like every other request — and because they pass through Render's proxy as a plain response.
 *
 * <p>Held in memory, which is right for one instance. Behind several, a
 * message reaches the pages connected to the instance that saved it and the
 * rest catch up on their next refresh; moving this to Redis pub/sub is the
 * change that fixes that, and nothing outside this class would notice.
 *
 * <p>Everything is sent after the transaction commits, so a page is never
 * told about a message a rollback then took back.
 */
@Component
public class SupportEvents {

    private static final Logger log = LoggerFactory.getLogger(SupportEvents.class);
    /** Longer than any proxy's idle timeout would allow without the heartbeat. */
    private static final Duration STREAM_LIFETIME = Duration.ofMinutes(30);
    private static final Duration HEARTBEAT = Duration.ofSeconds(25);

    private final Map<UUID, Set<SseEmitter>> customers = new ConcurrentHashMap<>();
    private final Set<SseEmitter> staff = new CopyOnWriteArraySet<>();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "support-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public SupportEvents() {
        heartbeat.scheduleAtFixedRate(this::beat, HEARTBEAT.toSeconds(), HEARTBEAT.toSeconds(), TimeUnit.SECONDS);
    }

    public SseEmitter forCustomer(UUID userId) {
        SseEmitter emitter = open();
        Set<SseEmitter> set = customers.computeIfAbsent(userId, id -> new CopyOnWriteArraySet<>());
        set.add(emitter);
        Runnable remove = () -> {
            set.remove(emitter);
            customers.computeIfPresent(userId, (id, s) -> s.isEmpty() ? null : s);
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        hello(emitter);
        return emitter;
    }

    public SseEmitter forStaff() {
        SseEmitter emitter = open();
        staff.add(emitter);
        emitter.onCompletion(() -> staff.remove(emitter));
        emitter.onTimeout(() -> staff.remove(emitter));
        emitter.onError(e -> staff.remove(emitter));
        hello(emitter);
        return emitter;
    }

    /**
     * Tells the customer's pages and the team's, each in its own shape —
     * the customer's view of a message links its file differently, and never
     * carries the team's notes. Null leaves that side out.
     */
    public void publish(UUID customerId, String event, Object forCustomer, Object forStaff) {
        afterCommit(() -> {
            if (forCustomer != null) {
                Set<SseEmitter> set = customers.get(customerId);
                if (set != null) {
                    set.forEach(emitter -> send(emitter, event, forCustomer, () -> set.remove(emitter)));
                }
            }
            if (forStaff != null) {
                staff.forEach(emitter -> send(emitter, event, forStaff, () -> staff.remove(emitter)));
            }
        });
    }

    public int connections() {
        return staff.size() + customers.values().stream().mapToInt(Set::size).sum();
    }

    @PreDestroy
    void stop() {
        heartbeat.shutdownNow();
        staff.forEach(SseEmitter::complete);
        customers.values().forEach(set -> set.forEach(SseEmitter::complete));
    }

    // ── Plumbing ──────────────────────────────────────────────────────────

    private static SseEmitter open() {
        return new SseEmitter(STREAM_LIFETIME.toMillis());
    }

    private static void hello(SseEmitter emitter) {
        try {
            // Tells the browser how long to wait before reconnecting, and
            // flushes the headers so the connection counts as open.
            emitter.send(SseEmitter.event().name("ready").data("{}").reconnectTime(3000));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }

    private void beat() {
        staff.forEach(emitter -> comment(emitter, () -> staff.remove(emitter)));
        customers.values().forEach(set -> set.forEach(emitter -> comment(emitter, () -> set.remove(emitter))));
    }

    private static void comment(SseEmitter emitter, Runnable onGone) {
        try {
            emitter.send(SseEmitter.event().comment("keep-alive"));
        } catch (IOException | IllegalStateException e) {
            onGone.run();
        }
    }

    private static void send(SseEmitter emitter, String event, Object payload, Runnable onGone) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload));
        } catch (IOException | IllegalStateException e) {
            log.debug("Dropping a closed support stream: {}", e.getMessage());
            onGone.run();
        }
    }

    private static void afterCommit(Runnable work) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    work.run();
                }
            });
        } else {
            work.run();
        }
    }
}
