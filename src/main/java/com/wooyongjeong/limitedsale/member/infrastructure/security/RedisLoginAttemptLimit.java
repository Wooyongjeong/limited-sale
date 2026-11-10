package com.wooyongjeong.limitedsale.member.infrastructure.security;

import com.wooyongjeong.limitedsale.member.domain.LoginAttemptLimit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisLoginAttemptLimit implements LoginAttemptLimit {
    private static final DefaultRedisScript<Long> CHECK = new DefaultRedisScript<>(
        "local n = tonumber(redis.call('GET', KEYS[1]) or '0'); "
            + "if n >= tonumber(ARGV[1]) then return 1 else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> FINISH = new DefaultRedisScript<>("""
        local n = tonumber(redis.call('GET', KEYS[1]) or '0')
        if n >= tonumber(ARGV[3]) then return 1 end
        if ARGV[1] == '1' then
            redis.call('DEL', KEYS[1])
            return 0
        end
        n = redis.call('INCR', KEYS[1])
        if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
        if n >= tonumber(ARGV[3]) then return 1 else return 0 end
        """, Long.class);
    private final StringRedisTemplate redis;
    private final Duration window;
    private final int maximumFailures;

    public RedisLoginAttemptLimit(StringRedisTemplate redis,
            @Value("${limited-sale.auth.failure-window:5m}") Duration window,
            @Value("${limited-sale.auth.failure-limit:5}") int maximumFailures) {
        if (window.isZero() || window.isNegative() || window.toMillis() == 0) {
            throw new IllegalArgumentException("Login failure window must be at least one millisecond");
        }
        if (maximumFailures <= 0) {
            throw new IllegalArgumentException("Login failure limit must be positive");
        }
        this.redis = redis;
        this.window = window;
        this.maximumFailures = maximumFailures;
    }

    @Override
    public boolean blocked(String email, String address) {
        return redis.execute(CHECK, List.of(key(email, address)), Integer.toString(maximumFailures)) == 1;
    }

    @Override
    public boolean finish(String email, String address, boolean success) {
        return redis.execute(FINISH, List.of(key(email, address)), success ? "1" : "0",
            Long.toString(window.toMillis()), Integer.toString(maximumFailures)) == 1;
    }

    private static String key(String email, String address) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest((email.length() + ":" + email + address).getBytes(StandardCharsets.UTF_8));
            return "limited-sale:login-failure:" + HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
