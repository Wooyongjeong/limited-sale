package com.wooyongjeong.limitedsale.member.domain;

public interface PasswordVerifier {
    boolean matches(String password, String hash);
}
