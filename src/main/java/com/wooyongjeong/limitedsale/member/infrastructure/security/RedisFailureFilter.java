package com.wooyongjeong.limitedsale.member.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wooyongjeong.limitedsale.member.presentation.code.AuthenticationResponseCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RedisFailureFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;

    public RedisFailureFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException | ServletException exception) {
            if (!isRedisFailure(exception) || response.isCommitted()) {
                throw exception;
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
