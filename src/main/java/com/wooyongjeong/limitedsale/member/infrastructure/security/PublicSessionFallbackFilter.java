package com.wooyongjeong.limitedsale.member.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class PublicSessionFallbackFilter extends OncePerRequestFilter {
    private static final Pattern PUBLIC_PATH = Pattern.compile("^/api/v1/(products|sales)(/[^/]+)?/?$");
    private final StringRedisTemplate redis;
    private final String cookieName;

    public PublicSessionFallbackFilter(StringRedisTemplate redis,
            @Value("${server.servlet.session.cookie.name:SESSION}") String cookieName) {
        this.redis = redis;
        this.cookieName = cookieName;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"GET".equals(request.getMethod()) || !PUBLIC_PATH.matcher(request.getServletPath()).matches()) {
            chain.doFilter(request, response);
            return;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null || Arrays.stream(cookies).noneMatch(cookie -> cookieName.equals(cookie.getName()))) {
            chain.doFilter(request, response);
            return;
        }
        try {
            redis.execute((RedisCallback<String>) connection -> connection.ping());
        } catch (RuntimeException exception) {
            if (!RedisFailureFilter.isRedisFailure(exception)) {
                throw exception;
            }
            Cookie[] remaining = Arrays.stream(cookies).filter(cookie -> !cookieName.equals(cookie.getName()))
                .toArray(Cookie[]::new);
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override
                public Cookie[] getCookies() {
                    return remaining.length == 0 ? null : remaining.clone();
                }
            }, response);
            return;
        }
        chain.doFilter(request, response);
    }
}
