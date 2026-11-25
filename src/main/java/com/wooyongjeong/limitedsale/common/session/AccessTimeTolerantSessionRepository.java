package com.wooyongjeong.limitedsale.common.session;

import io.micrometer.core.instrument.Counter;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

/**
 * Keeps a Redis outage at the very end of a request from breaking a response whose work is already committed (stage
 * 2-5 finding 1, fixed in 4-2).
 *
 * <p>With {@code flush-mode: immediate} every attribute and access-time change is written to Redis while the request
 * runs, and a failure there fails the request normally. What is left for the save that Spring Session runs when the
 * response is committed is, for a session that already existed, the final confirmation of a session whose id did not
 * change. If Redis fails at that point the status line is usually already sent, so the exception could only cut the
 * response short; this repository logs and counts it instead. Two cases still fail as before, because the client's
 * cookie would otherwise point to nothing: a session created in this request, and one whose id changed (a login renames
 * the session).
 */
final class AccessTimeTolerantSessionRepository<S extends Session> implements SessionRepository<S> {
    private static final Logger log = LoggerFactory.getLogger(AccessTimeTolerantSessionRepository.class);

    private final SessionRepository<S> delegate;
    private final Counter skipped;
    /** The id each loaded session had when this request got it; sessions do not override equals, so by identity. */
    private final Map<S, String> idsWhenLoaded = Collections.synchronizedMap(new WeakHashMap<>());

    AccessTimeTolerantSessionRepository(SessionRepository<S> delegate, Counter skipped) {
        this.delegate = delegate;
        this.skipped = skipped;
    }

    /** A session created in this request is not tracked: it must reach Redis, or its cookie would point to nothing. */
    @Override
    public S createSession() {
        return delegate.createSession();
    }

    @Override
    public void save(S session) {
        String idWhenLoaded = idsWhenLoaded.get(session);
        try {
            delegate.save(session);
        } catch (DataAccessException failure) {
            if (idWhenLoaded == null || !idWhenLoaded.equals(session.getId())) {
                throw failure;
            }
            skipped.increment();
            log.warn("Session save at the end of the request failed and was skipped; the response stands: {}",
                failure.toString());
        }
    }

    @Override
    public @Nullable S findById(String id) {
        S session = delegate.findById(id);
        if (session != null) {
            idsWhenLoaded.put(session, id);
        }
        return session;
    }

    @Override
    public void deleteById(String id) {
        delegate.deleteById(id);
    }
}
