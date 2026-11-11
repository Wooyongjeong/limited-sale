package com.wooyongjeong.limitedsale.member.application;

import com.wooyongjeong.limitedsale.member.domain.AuthenticationRecord;
import com.wooyongjeong.limitedsale.member.domain.AuthenticationStore;
import com.wooyongjeong.limitedsale.member.domain.LoginAttemptLimit;
import com.wooyongjeong.limitedsale.member.domain.Member;
import com.wooyongjeong.limitedsale.member.domain.MemberRole;
import com.wooyongjeong.limitedsale.member.domain.PasswordVerifier;
import com.wooyongjeong.limitedsale.member.infrastructure.security.Argon2PasswordHasher;
import jakarta.validation.Validation;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthenticationServiceTest {
    private final AuthenticationStore store = mock(AuthenticationStore.class);
    private final PasswordVerifier passwords = mock(PasswordVerifier.class);
    private final LoginAttemptLimit limits = mock(LoginAttemptLimit.class);
    private final AuthenticationService service = new AuthenticationService(store, passwords, limits,
        Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void unknownEmailStillVerifiesPasswordAndNeverAuthenticatesEvenOnMatch() {
        when(passwords.dummyHash()).thenReturn("dummy-hash");
        when(passwords.matches("password", "dummy-hash")).thenReturn(true);
        assertThatThrownBy(() -> service.authenticate("unknown@example.com", "password", "local"))
            .isInstanceOf(AuthenticationService.InvalidCredentials.class);
        verify(passwords).matches("password", "dummy-hash");
        verify(limits).finish("unknown@example.com", "local", false);
    }

    @Test
    void existingAccountUsesStoredHashAndCanAuthenticate() {
        var record = new AuthenticationRecord(1, new Member("known@example.com", "known", MemberRole.USER), "hash");
        when(store.findForAuthentication("known@example.com")).thenReturn(Optional.of(record));
        assertThatThrownBy(() -> service.authenticate("known@example.com", "wrongpass", "local"))
            .isInstanceOf(AuthenticationService.InvalidCredentials.class);
        verify(passwords).matches("wrongpass", "hash");
        when(passwords.matches("password", "hash")).thenReturn(true);
        assertThat(service.authenticate("known@example.com", "password", "local")).isEqualTo(record);
        verify(passwords).matches("password", "hash");
        verify(passwords, never()).dummyHash();
    }

    @Test
    void blockedRequestDoesNotVerify() {
        when(limits.blocked("unknown@example.com", "local")).thenReturn(true);
        assertThatThrownBy(() -> service.authenticate("unknown@example.com", "password", "local"))
            .isInstanceOf(AuthenticationService.RateLimited.class);
        verify(passwords, never()).matches(anyString(), anyString());
        verify(store, never()).findForAuthentication(anyString());
    }

    @Test
    void realDummyHashUsesConfiguredCostAndIsReusedOutsideTransaction() {
        var real = new Argon2PasswordHasher();
        String dummy = real.dummyHash();
        assertThat(dummy).contains("$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(real.dummyHash()).isSameAs(dummy);
        assertThat(real.matches("dummy-password-for-unknown-account", dummy)).isTrue();
        assertThat(real.matches("wrongpass", dummy)).isFalse();
        var verifier = mock(PasswordVerifier.class);
        when(verifier.dummyHash()).thenReturn(dummy);
        when(verifier.matches(anyString(), anyString())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return real.matches(invocation.getArgument(0), invocation.getArgument(1));
        });
        var actual = new AuthenticationService(store, verifier, limits,
            Validation.buildDefaultValidatorFactory().getValidator());
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> actual.authenticate("missing@example.com", "wrongpass", "local"))
                .isInstanceOf(AuthenticationService.InvalidCredentials.class);
        }
        verify(verifier, org.mockito.Mockito.times(2)).matches("wrongpass", dummy);
        assertThatThrownBy(() -> actual.authenticate("missing@example.com", "dummy-password-for-unknown-account", "local"))
            .isInstanceOf(AuthenticationService.InvalidCredentials.class);
        verify(limits, org.mockito.Mockito.times(3)).finish("missing@example.com", "local", false);
    }
}
