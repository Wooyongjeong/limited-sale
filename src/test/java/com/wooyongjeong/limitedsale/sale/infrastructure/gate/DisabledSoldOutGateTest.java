package com.wooyongjeong.limitedsale.sale.infrastructure.gate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DisabledSoldOutGateTest {
    @Test
    void neverMarksAndWritesNothing() {
        var gate = new DisabledSoldOutGate();
        gate.markAfterCommit(1);
        gate.clearAfterCommit(1);
        assertThat(gate.isMarked(1)).isFalse();
    }
}
