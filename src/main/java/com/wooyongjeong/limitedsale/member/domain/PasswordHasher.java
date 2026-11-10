package com.wooyongjeong.limitedsale.member.domain;

public interface PasswordHasher {
    String hash(String password);
}
