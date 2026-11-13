package com.wooyongjeong.mockpg.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wooyongjeong.mockpg.common.web.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {
    private final byte[] expected;
    private final ObjectMapper mapper;

    public ApiKeyFilter(@Value("${mock-pg.api-key}") String key, ObjectMapper mapper) {
        if (key.isBlank()) {
            throw new IllegalArgumentException("Mock PG API key is required");
        }
        expected = key.getBytes(StandardCharsets.UTF_8);
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean health = request.getRequestURI().equals(request.getContextPath() + "/actuator/health")
                && (request.getMethod().equals("GET") || request.getMethod().equals("HEAD"));
        if (!health) {
            String supplied = request.getHeader("X-Mock-PG-Key");
            if (supplied == null || !MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(401);
                response.setContentType("application/json");
                mapper.writeValue(response.getOutputStream(), ApiResponse.of(PaymentResponseCode.UNAUTHORIZED, null));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
