package com.example.demo.security;

import com.example.demo.entity.AppUser;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthTokenStoreTest {

    @Test
    void sessionExpiresAtConfiguredDeadline() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-29T00:00:00Z"));
        AuthTokenStore store = new AuthTokenStore(Duration.ofMinutes(30), clock);

        AuthSession session = store.createSession(user());
        assertEquals(Instant.parse("2026-09-29T00:30:00Z"), session.getExpiresAt());
        assertTrue(store.getSession(session.getToken()).isPresent());

        clock.advance(Duration.ofMinutes(30));
        assertFalse(store.getSession(session.getToken()).isPresent());
        assertEquals(0, store.sessionCount());
    }

    @Test
    void creatingSessionRemovesOtherExpiredSessions() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-29T00:00:00Z"));
        AuthTokenStore store = new AuthTokenStore(Duration.ofMinutes(10), clock);
        store.createSession(user());

        clock.advance(Duration.ofMinutes(11));
        store.createSession(user());

        assertEquals(1, store.sessionCount());
    }

    @Test
    void concurrentSessionCreationProducesUniqueUsableTokens() throws Exception {
        AuthTokenStore store = new AuthTokenStore(Duration.ofHours(1), Clock.systemUTC());
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> tasks = java.util.stream.IntStream.range(0, 100)
                    .mapToObj(index -> (Callable<String>) () -> store.createSession(user()).getToken())
                    .toList();
            List<String> tokens = executor.invokeAll(tasks).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .toList();

            assertEquals(100, new HashSet<>(tokens).size());
            assertTrue(tokens.stream().allMatch(token -> store.getSession(token).isPresent()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void backendOnlySeesTokenHash() {
        InMemoryAuthTokenBackend backend = new InMemoryAuthTokenBackend();
        AuthTokenStore store = new AuthTokenStore(backend, Duration.ofDays(30), Clock.systemUTC());

        String token = store.createSession(user()).getToken();

        assertEquals(64, token.length());
        assertFalse(backend.find(token).isPresent());
        assertTrue(backend.find(AuthTokenStore.hash(token)).isPresent());
    }

    @Test
    void activeSessionIsRenewedAndIdleSessionExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-01T00:00:00Z"));
        AuthTokenStore store = new AuthTokenStore(Duration.ofDays(30), clock);
        AuthSession session = store.createSession(user());

        clock.advance(Duration.ofDays(20));
        assertEquals(Instant.parse("2026-11-20T00:00:00Z"), store.getSession(session.getToken()).orElseThrow().getExpiresAt());

        clock.advance(Duration.ofDays(29));
        assertTrue(store.getSession(session.getToken()).isPresent());

        clock.advance(Duration.ofDays(31));
        assertFalse(store.getSession(session.getToken()).isPresent());
    }

    @Test
    void removingUserSessionsLogsOutEveryDevice() {
        AuthTokenStore store = new AuthTokenStore(Duration.ofDays(30), Clock.systemUTC());
        String phone = store.createSession(user()).getToken();
        String tablet = store.createSession(user()).getToken();

        store.removeUserSessions(1L);

        assertFalse(store.getSession(phone).isPresent());
        assertFalse(store.getSession(tablet).isPresent());
    }

    @Test
    void rejectsNonPositiveTtl() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuthTokenStore(Duration.ZERO, Clock.systemUTC()));
    }

    private AppUser user() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("admin");
        return user;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
