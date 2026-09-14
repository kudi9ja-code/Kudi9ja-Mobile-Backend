package com.quadrilateral.kudi9ja.security.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.quadrilateral.kudi9ja.common.error.ApiError;
import com.quadrilateral.kudi9ja.common.error.ErrorCode;
import com.quadrilateral.kudi9ja.config.Kudi9jaProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns one address away once it has asked too often.
 *
 * <p>The per-account lockout already stops somebody attacking one customer:
 * five wrong passcodes and the account is closed for a while. It does nothing
 * against one machine trying a million <i>different</i> emails, or asking for
 * ten thousand one-time codes, or hammering sign-up until the database is too
 * busy to serve anyone. Each of those looks, from the account's point of view,
 * like nothing at all. From the address's point of view it is unmistakable,
 * and that is what is counted here.
 *
 * <p>Two tiers, both per address, both per minute:
 *
 * <ul>
 *   <li><b>auth</b> — the endpoints that need no token: signing up, signing
 *       in, codes, password reset. Tight, because a person never comes close.
 *   <li><b>general</b> — everything, as a backstop. Loose enough for a busy
 *       handset refreshing every screen, far too tight for a script.
 * </ul>
 *
 * <p>Fixed one-minute windows in memory. Not a sliding window and not shared
 * across instances, and both are deliberate: this process runs as one
 * instance, and a limiter that needs Redis to say "slow down" is a limiter
 * that is down whenever Redis is. A fixed window lets an attacker get at most
 * twice the limit across a boundary, which is still a limit.
 *
 * <p>The address is read from the <b>right</b> of {@code X-Forwarded-For},
 * as many hops in as there are trusted proxies. The left of that header is
 * whatever the client chose to write, and a limiter keyed on it is a limiter
 * anyone can dodge by rotating a fake.
 *
 * <p>Runs before Spring Security, so a refused request costs a map lookup and
 * nothing else — no token parse, no session read, no database.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** The tier that a sign-up, sign-in, code or reset falls into. */
    private static final String[] AUTH_PREFIXES = {
            "/api/v1/auth/",
    };

    /**
     * Never limited. The platform's health check must always be answered, and
     * a preflight is the browser asking whether it may ask.
     */
    private static final String[] EXEMPT_PREFIXES = {
            "/actuator/health",
    };

    private final Kudi9jaProperties.RateLimit config;
    private final ObjectMapper json;

    /**
     * One counter per (tier, address, window). Entries fall out of the cache a
     * window after they were written, so an address that stops asking stops
     * costing memory. Bounded, so a flood of addresses cannot grow it without
     * limit either.
     */
    private final Cache<String, Window> windows = Caffeine.newBuilder()
            .expireAfterWrite(WINDOW.plusSeconds(5))
            .maximumSize(200_000)
            .build();

    public RateLimitFilter(Kudi9jaProperties properties, ObjectMapper json) {
        this.config = properties.rateLimit();
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        if (!config.enabled()) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        for (String prefix : EXEMPT_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain) throws ServletException, IOException {

        String address = clientAddress(request);
        String path = request.getRequestURI();

        // The general tier applies to everything, the auth tier on top of it
        // for the open endpoints. Checked tightest-first so the response says
        // which one tripped.
        if (isAuth(path)) {
            Window auth = count("auth", address);
            if (auth.hits > config.authPerMinute()) {
                refuse(response, request, address, "auth", auth);
                return;
            }
        }

        Window general = count("general", address);
        if (general.hits > config.generalPerMinute()) {
            refuse(response, request, address, "general", general);
            return;
        }

        chain.doFilter(request, response);
    }

    private Window count(String tier, String address) {
        long bucket = Instant.now().getEpochSecond() / WINDOW.toSeconds();
        String key = tier + '|' + address + '|' + bucket;
        Window window = windows.get(key, k -> new Window(bucket));
        window.hits = window.counter.incrementAndGet();
        return window;
    }

    private void refuse(
            HttpServletResponse response,
            HttpServletRequest request,
            String address,
            String tier,
            Window window) throws IOException {

        long secondsLeft = (window.bucket + 1) * WINDOW.toSeconds()
                - Instant.now().getEpochSecond();
        secondsLeft = Math.max(1, secondsLeft);

        // Once per window per address, not once per refused request — a flood
        // that is being refused must not also flood the log.
        if (window.counter.get() == limitFor(tier) + 1) {
            log.warn("Rate limit ({}) tripped by {} on {} — {} in the window",
                    tier, address, request.getRequestURI(), window.counter.get());
        }

        response.setStatus(ErrorCode.RATE_LIMITED.status().value());
        response.setHeader("Retry-After", Long.toString(secondsLeft));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(
                response.getOutputStream(),
                ApiError.of(
                        ErrorCode.RATE_LIMITED,
                        "Too many requests from your connection. Wait a minute and try again.",
                        Map.of("retryAfterSeconds", secondsLeft),
                        request.getRequestURI()));
    }

    private int limitFor(String tier) {
        return "auth".equals(tier) ? config.authPerMinute() : config.generalPerMinute();
    }

    private static boolean isAuth(String path) {
        for (String prefix : AUTH_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The address to count against.
     *
     * <p>With no proxy in front, the socket's peer. Behind one, the entry that
     * proxy appended to {@code X-Forwarded-For} — which is the <i>last</i>
     * one, since each hop appends. Reading the first entry, as most examples
     * do, keys the limiter on a value the client typed.
     */
    String clientAddress(HttpServletRequest request) {
        // Spring's ForwardedHeaderFilter runs first, and it both strips
        // X-Forwarded-For from what later filters see and rewrites
        // getRemoteAddr() from the header's FIRST entry — the one the client
        // wrote. Neither is any use here. The raw container request still
        // holds the header as it arrived, so that is what is read.
        HttpServletRequest raw = request;
        while (raw instanceof HttpServletRequestWrapper wrapper
                && wrapper.getRequest() instanceof HttpServletRequest inner) {
            raw = inner;
        }

        int hops = config.trustedProxyHops();
        String forwarded = raw.getHeader("X-Forwarded-For");
        if (hops > 0 && forwarded != null && !forwarded.isBlank()) {
            String[] chain = forwarded.split(",");
            int index = chain.length - hops;
            if (index >= 0) {
                return chain[index].trim();
            }
            // Fewer entries than trusted hops: the header was not set by our
            // proxy at all. Fall through to the socket rather than trust it.
        }
        return raw.getRemoteAddr();
    }

    /** One address's count in one window. */
    private static final class Window {
        final long bucket;
        final AtomicInteger counter = new AtomicInteger();
        volatile int hits;

        Window(long bucket) {
            this.bucket = bucket;
        }
    }
}
