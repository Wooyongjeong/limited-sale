package com.wooyongjeong.limitedsale.member.domain;

public class MemberConflict extends RuntimeException {
    public enum Kind { EMAIL, NICKNAME }

    private final Kind kind;

    public MemberConflict(Kind kind, Throwable cause) {
        super(kind.name(), cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
