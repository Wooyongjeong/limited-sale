package com.wooyongjeong.limitedsale.member.presentation.response;

import com.wooyongjeong.limitedsale.member.domain.Member;

public record MemberResponse(String email, String nickname, String role) {
    public static MemberResponse from(Member member) {
        return new MemberResponse(member.email(), member.nickname(), member.role().name());
    }
}
