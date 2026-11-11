package com.wooyongjeong.limitedsale.member.infrastructure.security;

import com.wooyongjeong.limitedsale.member.domain.PasswordHasher;
import com.wooyongjeong.limitedsale.member.domain.PasswordVerifier;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class Argon2PasswordHasher implements PasswordHasher, PasswordVerifier {
    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);

    private final String dummyHash = encoder.encode("dummy-password-for-unknown-account");

    @Override
    public String dummyHash() {
        return dummyHash;
    }

    @Override
    public String hash(String password) {
        return encoder.encode(password);
    }
    @Override
    public boolean matches(String password, String hash) {
        return encoder.matches(password, hash);
    }
}
