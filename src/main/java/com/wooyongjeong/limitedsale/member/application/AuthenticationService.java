package com.wooyongjeong.limitedsale.member.application;

import com.wooyongjeong.limitedsale.member.domain.AuthenticationRecord;
import com.wooyongjeong.limitedsale.member.domain.AuthenticationStore;
import com.wooyongjeong.limitedsale.member.domain.LoginAttemptLimit;
import com.wooyongjeong.limitedsale.member.domain.MemberInput;
import com.wooyongjeong.limitedsale.member.domain.PasswordVerifier;
import jakarta.validation.constraints.Email;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationService {
    private final AuthenticationStore store;
    private final PasswordVerifier passwords;
    private final LoginAttemptLimit limits;
    private final jakarta.validation.Validator validator;

    private record EmailValue(@Email String value) { }

    public AuthenticationService(AuthenticationStore store, PasswordVerifier passwords, LoginAttemptLimit limits,
            jakarta.validation.Validator validator) {
        this.store = store;
        this.passwords = passwords;
        this.limits = limits;
        this.validator = validator;
    }

    public static class InvalidCredentials extends RuntimeException { }
    public static class RateLimited extends RuntimeException { }

    public AuthenticationRecord authenticate(String email, String password, String address) {
        String normalized = MemberInput.email(email);
        if (normalized.isEmpty() || normalized.length() > 254 || !normalized.contains("@")
                || !validator.validate(new EmailValue(normalized)).isEmpty() || !MemberInput.validPassword(password)) {
            throw new MemberService.InputException(List.of(new MemberService.FieldError("credentials", "입력을 확인하세요")));
        }
        if (limits.blocked(normalized, address)) {
            throw new RateLimited();
        }
        AuthenticationRecord record = store.findForAuthentication(normalized).orElse(null);
        boolean matched = passwords.matches(password, record == null ? passwords.dummyHash() : record.passwordHash());
        boolean valid = record != null && matched;
        if (limits.finish(normalized, address, valid)) {
            throw new RateLimited();
        }
        if (!valid) {
            throw new InvalidCredentials();
        }
        return record;
    }
}
