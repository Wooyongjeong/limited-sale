package com.wooyongjeong.limitedsale.member.domain;

import java.util.Optional;

public interface MemberStore {
    Optional<Member> findByEmail(String email);
    long firstAvailableNicknameSuffix(String baseKey);
    void insert(String email, String passwordHash, String nickname, MemberRole role);
}
