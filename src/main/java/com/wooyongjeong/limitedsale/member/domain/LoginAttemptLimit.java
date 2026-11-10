package com.wooyongjeong.limitedsale.member.domain;

public interface LoginAttemptLimit {
    boolean blocked(String email, String address);
    boolean finish(String email, String address, boolean success);
}
