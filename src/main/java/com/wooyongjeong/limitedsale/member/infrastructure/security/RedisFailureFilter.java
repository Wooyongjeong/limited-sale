package com.wooyongjeong.limitedsale.member.infrastructure.security;

import tools.jackson.databind.ObjectMapper;
import com.wooyongjeong.limitedsale.member.presentation.code.AuthenticationResponseCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a Redis failure into 503 AUTHENTICATION_UNAVAILABLE. A failure that arrives after the response is committed
 * cannot change the answer any more; it comes from Spring Session finishing the request (it looks the session up again
 * when it commits it in its finally block). Re-throwing it sent Tomcat into error-page handling for a response that
 * was already committed, which failed in turn (about 200 times per run in 4-3 S1, two stack traces each), so it is
 * logged and counted (redis_failure_after_commit_total) and the written response is left to finish.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RedisFailureFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RedisFailureFilter.class);
    private final ObjectMapper mapper;
    private final Counter afterCommit;

    public RedisFailureFilter(ObjectMapper mapper, MeterRegistry registry) {
        this.mapper = mapper;
        this.afterCommit = Counter.builder("redis.failure.after.commit")
            .description("Redis failures after the response was committed; the written response was kept")
            .register(registry);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException | ServletException exception) {
            if (!isRedisFailure(exception)) {
                throw exception;
            }
            if (response.isCommitted()) {
                afterCommit.increment();
                log.warn("Redis failed after the response was committed; the response stands: {}",
                    exception.toString());
                return;
            }
            response.reset();
            SecurityConfiguration.write(mapper, response, AuthenticationResponseCode.AUTHENTICATION_UNAVAILABLE);
        }
    }

    public static boolean isRedisFailure(Throwable exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof RedisConnectionFailureException || cause instanceof RedisSystemException
                    || cause.getClass().getName().startsWith("io.lettuce.core.Redis")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
