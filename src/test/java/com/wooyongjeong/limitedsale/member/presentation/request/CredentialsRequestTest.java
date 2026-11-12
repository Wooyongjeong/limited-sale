package com.wooyongjeong.limitedsale.member.presentation.request;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialsRequestTest {
    @Test
    void requestStringsDoNotExposeCredentials() {
        assertThat(new LoginRequest("secret@example.com", "secret-password").toString())
            .doesNotContain("secret@example.com", "secret-password");
        assertThat(new MemberRegistrationRequest("secret@example.com", "secret-password", "nickname").toString())
            .doesNotContain("secret@example.com", "secret-password", "nickname");
    }
}
