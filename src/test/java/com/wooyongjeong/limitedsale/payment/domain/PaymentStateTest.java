package com.wooyongjeong.limitedsale.payment.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentStateTest {
    @Test
    void terminalResultSurvivesEveryLateResponse() {
        for (PaymentState terminal : new PaymentState[] {
                PaymentState.SUCCEEDED, PaymentState.FAILED, PaymentState.CLOSED}) {
            for (PaymentState incoming : PaymentState.values()) {
                assertThat(terminal.apply(incoming)).isEqualTo(terminal);
            }
        }
    }

    @Test
    void pendingPaymentAcceptsAConfirmedTerminalResult() {
        assertThat(PaymentState.UNKNOWN.apply(PaymentState.SUCCEEDED)).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(PaymentState.PROCESSING.apply(PaymentState.CLOSED)).isEqualTo(PaymentState.CLOSED);
    }
}
