package com.wooyongjeong.limitedsale.order.domain;

import java.time.Instant;
import java.util.UUID;

public final class RecoveryPolicy {
    private RecoveryPolicy() {
    }

    public enum Action { WAIT, CHARGE, QUERY, CLOSE, FINISH }

    public static Action action(String paymentStatus, Instant expiresAt, Instant now, boolean userRequest) {
        if ("SUCCEEDED".equals(paymentStatus) || "FAILED".equals(paymentStatus) || "CLOSED".equals(paymentStatus)) {
            return Action.FINISH;
        }
        if (!now.isBefore(expiresAt)) {
            return Action.CLOSE;
        }
        if ("READY".equals(paymentStatus)) {
            return userRequest ? Action.CHARGE : Action.WAIT;
        }
        return Action.QUERY;
    }

    public static boolean owns(UUID expected, UUID actual, Instant until, Instant now) {
        return expected != null && expected.equals(actual) && until != null && now.isBefore(until);
    }
}
