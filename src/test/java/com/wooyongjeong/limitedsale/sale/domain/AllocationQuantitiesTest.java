package com.wooyongjeong.limitedsale.sale.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AllocationQuantitiesTest {
    @Test
    void confirmationMovesAndReleaseRemovesTheReservedQuantity() {
        assertThat(new AllocationQuantities(4, 2).finish(3, true)).isEqualTo(new AllocationQuantities(1, 5));
        assertThat(new AllocationQuantities(4, 2).finish(3, false)).isEqualTo(new AllocationQuantities(1, 2));
    }

    @Test
    void refusesToFinalizeMoreThanWasReserved() {
        assertThatThrownBy(() -> new AllocationQuantities(1, 0).finish(2, true))
                .isInstanceOf(IllegalStateException.class);
    }
}
