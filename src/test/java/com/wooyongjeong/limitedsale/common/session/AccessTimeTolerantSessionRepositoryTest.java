package com.wooyongjeong.limitedsale.common.session;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessTimeTolerantSessionRepositoryTest {
    private final AtomicBoolean redisDown = new AtomicBoolean();
    private final MapSessionRepository sessions = new MapSessionRepository(new java.util.HashMap<>());
    private final Counter skipped = Counter.builder("session.save.skipped").register(new SimpleMeterRegistry());
    private final AccessTimeTolerantSessionRepository<Session> repository = new AccessTimeTolerantSessionRepository<>(
        new FailingWhenDown(), skipped);

    private final class FailingWhenDown implements SessionRepository<Session> {
        @Override
        public Session createSession() {
            return sessions.createSession();
        }

        @Override
        public void save(Session session) {
            if (redisDown.get()) {
                throw new QueryTimeoutException("Redis command timed out");
            }
            sessions.save((MapSession) session);
        }

        @Override
        public Session findById(String id) {
            return sessions.findById(id);
        }

        @Override
        public void deleteById(String id) {
            sessions.deleteById(id);
        }
    }

    @Test
    void aFailedEndOfRequestSaveOfAnUnchangedSessionIsSkippedAndCounted() {
        Session created = repository.createSession();
        repository.save(created);
        Session loaded = repository.findById(created.getId());
        redisDown.set(true);

        repository.save(loaded);

        assertThat(skipped.count()).isEqualTo(1);
    }

    @Test
    void aSessionWhoseIdChangedStillFailsBecauseItsNewCookieWouldPointToNothing() {
        Session created = repository.createSession();
        repository.save(created);
        Session loaded = repository.findById(created.getId());
        loaded.changeSessionId();
        redisDown.set(true);

        assertThatThrownBy(() -> repository.save(loaded)).isInstanceOf(QueryTimeoutException.class);
        assertThat(skipped.count()).isZero();
    }

    @Test
    void aSessionCreatedInThisRequestStillFailsBecauseItsCookieWouldPointToNothing() {
        Session created = repository.createSession();
        redisDown.set(true);

        assertThatThrownBy(() -> repository.save(created)).isInstanceOf(QueryTimeoutException.class);
        assertThat(skipped.count()).isZero();
    }

    @Test
    void aSessionThisRepositoryNeverHandedOutIsNotTrusted() {
        Session foreign = sessions.createSession();
        redisDown.set(true);

        assertThatThrownBy(() -> repository.save(foreign)).isInstanceOf(QueryTimeoutException.class);
    }

    @Test
    void otherFailuresThanRedisAccessArePassedOn() {
        Session created = repository.createSession();
        var invalidated = new AccessTimeTolerantSessionRepository<Session>(new SessionRepository<>() {
            @Override
            public Session createSession() {
                return created;
            }

            @Override
            public void save(Session session) {
                throw new IllegalStateException("Session was invalidated");
            }

            @Override
            public Session findById(String id) {
                return created;
            }

            @Override
            public void deleteById(String id) {
            }
        }, skipped);
        Session again = invalidated.findById(created.getId());

        assertThatThrownBy(() -> invalidated.save(again)).isInstanceOf(IllegalStateException.class);
    }
}
