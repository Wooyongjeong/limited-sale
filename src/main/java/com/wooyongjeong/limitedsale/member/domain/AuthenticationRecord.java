package com.wooyongjeong.limitedsale.member.domain;

public record AuthenticationRecord(long id, Member member, String passwordHash) {
}
