package com.wooyongjeong.limitedsale.member.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemberInputTest {
    @Test
    void passwordLengthAndCharacters() {
        assertThat(MemberInput.validPassword("a".repeat(8))).isTrue();
        assertThat(MemberInput.validPassword("!".repeat(64))).isTrue();
        assertThat(MemberInput.validPassword("a".repeat(7))).isFalse();
        assertThat(MemberInput.validPassword("a".repeat(65))).isFalse();
        assertThat(MemberInput.validPassword("pass word")).isFalse();
        assertThat(MemberInput.validPassword("한글password")).isFalse();
    }

    @Test
    void nicknamesNormalizeAndValidate() {
        assertThat(MemberInput.nickname("  가  AbC  ")).isEqualTo("가 AbC");
        assertThat(MemberInput.nicknameKey("가 AbC")).isEqualTo("가 abc");
        assertThat(MemberInput.validNickname("가".repeat(12))).isTrue();
        assertThat(MemberInput.validNickname("가".repeat(13))).isFalse();
        assertThat(MemberInput.validNickname("ᄀ")).isFalse();
        assertThat(MemberInput.validNickname(MemberInput.nickname("\tname"))).isFalse();
        assertThat(MemberInput.nickname("   ")).isEmpty();
    }
}
