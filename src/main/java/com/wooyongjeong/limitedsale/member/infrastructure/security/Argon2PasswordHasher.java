package com.wooyongjeong.limitedsale.member.infrastructure.security;

import com.wooyongjeong.limitedsale.member.domain.PasswordHasher;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class Argon2PasswordHasher implements PasswordHasher {
    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);

    @Override
    public String hash(String password) {
        return encoder.encode(password);
    }
}
