package com.wooyongjeong.limitedsale.member.domain;

public interface PasswordVerifier {
    String dummyHash();

    boolean matches(String password, String hash);
}
