package com.wooyongjeong.limitedsale.member.application;

import com.wooyongjeong.limitedsale.member.domain.Member;
import com.wooyongjeong.limitedsale.member.domain.MemberConflict;
import com.wooyongjeong.limitedsale.member.domain.MemberInput;
import com.wooyongjeong.limitedsale.member.domain.MemberRole;
import com.wooyongjeong.limitedsale.member.domain.MemberStore;
import com.wooyongjeong.limitedsale.member.domain.PasswordHasher;
import jakarta.validation.constraints.Email;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MemberService {
    private static final int ATTEMPTS = 20;
    private static final String[] PREFIXES = {"발랄한", "용감한", "즐거운", "차분한", "다정한"};
    private static final String[] SUFFIXES = {"강아지", "고양이", "토끼", "판다", "다람쥐"};
    private final MemberStore store;
    private final PasswordHasher passwordHasher;
    private final jakarta.validation.Validator validator;
    private final Supplier<String> nicknameSource;

    @Autowired
    public MemberService(MemberStore store, PasswordHasher passwordHasher, jakarta.validation.Validator validator) {
        this(store, passwordHasher, validator, MemberService::randomBase);
    }

    MemberService(MemberStore store, PasswordHasher passwordHasher, jakarta.validation.Validator validator,
            Supplier<String> nicknameSource) {
        this.store = store;
        this.passwordHasher = passwordHasher;
        this.validator = validator;
        this.nicknameSource = nicknameSource;
    }

    private record EmailValue(@Email String value) { }

    public record FieldError(String field, String reason) { }

    public static class InputException extends RuntimeException {
        private final List<FieldError> errors;

        public InputException(List<FieldError> errors) {
            this.errors = List.copyOf(errors);
        }

        public List<FieldError> errors() {
            return errors;
        }
    }

    public static class NicknameUnavailable extends RuntimeException { }
    public static class ExistingUser extends RuntimeException { }

    public Member register(String email, String password, String nickname) {
        return create(email, password, nickname, MemberRole.USER);
    }

    public Member initializeAdmin(String email, String password, String nickname) {
        String normalized = validateEmail(email);
        var existing = store.findByEmail(normalized);
        if (existing.isPresent()) {
            if (existing.get().role() == MemberRole.ADMIN) {
                return existing.get();
            }
            throw new ExistingUser();
        }
        try {
            return create(normalized, password, nickname, MemberRole.ADMIN);
        } catch (MemberConflict conflict) {
            if (conflict.kind() != MemberConflict.Kind.EMAIL) {
                throw conflict;
            }
            var concurrent = store.findByEmail(normalized);
            if (concurrent.isPresent() && concurrent.get().role() == MemberRole.ADMIN) {
                return concurrent.get();
            }
            throw new ExistingUser();
        }
    }

    private String validateEmail(String email) {
        String normalized = MemberInput.email(email);
        if (normalized.isEmpty() || normalized.length() > 254 || !normalized.contains("@")
                || !validator.validate(new EmailValue(normalized)).isEmpty()) {
            throw new InputException(List.of(new FieldError("email", "올바른 이메일을 입력하세요")));
        }
        return normalized;
    }

    private Member create(String email, String password, String nickname, MemberRole role) {
        String normalized = validateEmail(email);
        String displayName = MemberInput.nickname(nickname);
        List<FieldError> errors = new ArrayList<>();
        if (!MemberInput.validPassword(password)) {
            errors.add(new FieldError("password", "공백 없는 출력 가능 ASCII 8~64자를 입력하세요"));
        }
        if (!displayName.isEmpty() && !MemberInput.validNickname(displayName)) {
            errors.add(new FieldError("nickname", "한글 완성형, 영문, 숫자, 공백 12자 이내로 입력하세요"));
        }
        if (!errors.isEmpty()) {
            throw new InputException(errors);
        }
        String hash = passwordHasher.hash(password);
        if (!displayName.isEmpty()) {
            store.insert(normalized, hash, displayName, role);
            return new Member(normalized, displayName, role);
        }
        String base = nicknameSource.get();
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            long suffix = store.firstAvailableNicknameSuffix(MemberInput.nicknameKey(base));
            String candidate = suffix == 0 ? base : base + suffix;
            if (candidate.length() > 12) {
                if (attempt + 1 < ATTEMPTS) {
                    base = nicknameSource.get();
                }
                continue;
            }
            try {
                store.insert(normalized, hash, candidate, role);
                return new Member(normalized, candidate, role);
            } catch (MemberConflict conflict) {
                if (conflict.kind() == MemberConflict.Kind.EMAIL) {
                    throw conflict;
                }
            }
        }
        throw new NicknameUnavailable();
    }

    private static String randomBase() {
        var random = ThreadLocalRandom.current();
        return PREFIXES[random.nextInt(PREFIXES.length)] + " " + SUFFIXES[random.nextInt(SUFFIXES.length)];
    }
}
