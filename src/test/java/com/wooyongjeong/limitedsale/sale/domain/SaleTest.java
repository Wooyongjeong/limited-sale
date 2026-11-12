package com.wooyongjeong.limitedsale.sale.domain;

import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaleTest {
    private static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant END = START.plusSeconds(3600);

    private Sale sale(boolean stopped, int reserved, int sold) {
        return new Sale(1, terms("event", START), "product", reserved, sold, stopped, 3, START, START);
    }

    private SaleTerms terms(String name, Instant start) {
        return new SaleTerms(1, name, null, 100, start, END, 10, 1);
    }

    @Test
    void statusHasExplicitTimeStopAndConfirmedSoldOutPriority() {
        assertThat(sale(false, 0, 0).statusAt(START.minusSeconds(1))).isEqualTo(SaleStatus.SCHEDULED);
        assertThat(sale(true, 0, 0).statusAt(START.minusSeconds(1))).isEqualTo(SaleStatus.STOPPED);
        assertThat(sale(false, 10, 0).statusAt(START)).isEqualTo(SaleStatus.OPEN);
        assertThat(sale(false, 0, 10).statusAt(START)).isEqualTo(SaleStatus.SOLD_OUT);
        assertThat(sale(true, 0, 10).statusAt(START)).isEqualTo(SaleStatus.STOPPED);
        assertThat(sale(true, 0, 10).statusAt(END)).isEqualTo(SaleStatus.ENDED);
    }

    @Test
    void allocationUsesPeriodThenStopThenLimitThenStock() {
        assertThat(sale(true, 10, 0).allocationDecision(START.minusNanos(1000), 1, 1))
            .isEqualTo(AllocationDecision.SALE_OUTSIDE_PERIOD);
        assertThat(sale(true, 10, 0).allocationDecision(START, 1, 1))
            .isEqualTo(AllocationDecision.SALE_STOPPED);
        assertThat(sale(false, 10, 0).allocationDecision(START, 1, 1))
            .isEqualTo(AllocationDecision.PURCHASE_LIMIT_EXCEEDED);
        assertThat(sale(false, 10, 0).allocationDecision(START, 0, 1))
            .isEqualTo(AllocationDecision.SALE_SOLD_OUT);
        assertThat(sale(false, 0, 0).allocationDecision(START, 0, 1))
            .isEqualTo(AllocationDecision.ALLOCATED);
        assertThat(sale(false, 0, 0).allocationDecision(END, 0, 1))
            .isEqualTo(AllocationDecision.SALE_OUTSIDE_PERIOD);
    }

    @Test
    void metadataCanChangeAfterStartButTermsAndStaleVersionsCannot() {
        Sale original = sale(false, 0, 0);
        Sale edited = original.revise(terms("changed", START), "product", false, 3, END);
        assertThat(edited.terms().name()).isEqualTo("changed");
        assertThatThrownBy(() -> original.revise(terms("changed", START), "product", true, 3, START))
            .isInstanceOf(SaleException.class).hasMessageContaining("시작");
        assertThatThrownBy(() -> original.revise(terms("changed", START), "product", false, 2, START))
            .isInstanceOf(SaleException.class).hasMessageContaining("버전");
        assertThatThrownBy(() -> original.requireDeletable(START)).isInstanceOf(SaleException.class);
        SaleTerms changedPrice = new SaleTerms(1, "changed", "", 200, START, END, 10, 1);
        assertThatThrownBy(() -> original.revise(changedPrice, "product", false, 3, START))
            .isInstanceOf(SaleException.class);
    }

    @Test
    void stopAndResumeWorkBeforeStartAreIdempotentAndRejectEnded() {
        Sale original = sale(false, 0, 0);
        Sale stopped = original.operate(true, START.minusSeconds(2));
        assertThat(stopped.operate(true, START.minusSeconds(1))).isSameAs(stopped);
        assertThat(stopped.operate(false, START.minusSeconds(1)).statusAt(START.minusSeconds(1)))
            .isEqualTo(SaleStatus.SCHEDULED);
        assertThatThrownBy(() -> stopped.operate(true, END)).isInstanceOf(SaleException.class);
        assertThatThrownBy(() -> stopped.operate(false, END)).isInstanceOf(SaleException.class);
    }

    @Test
    void validatesFinalTermsAndPreservesDescription() {
        assertThat(terms("  event  ", START).name()).isEqualTo("event");
        assertThat(terms("event", START).description()).isEmpty();
        assertThatThrownBy(() -> new SaleTerms(1, "event", "", 0, START, END, 10, 1))
            .isInstanceOf(SaleException.class);
        assertThatThrownBy(() -> new SaleTerms(1, "event", "", 1, START, END, 10, 11))
            .isInstanceOf(SaleException.class);
        assertThatThrownBy(() -> terms("event", END)).isInstanceOf(SaleException.class);
        assertThatThrownBy(() -> terms("event", START.plusNanos(1))).isInstanceOf(SaleException.class);
    }
    @Test
    void acceptsCodePointAndNumericUpperBoundsButRejectsInvalidFinalValues() {
        String name = "😀".repeat(100);
        var maximum = new SaleTerms(1, name, "x".repeat(2000), 1_000_000_000L,
            START, END, 1_000_000, 1_000_000);
        assertThat(maximum.name()).isEqualTo(name);
        assertThatThrownBy(() -> new SaleTerms(1, name + "x", "", 1, START, END, 1, 1))
            .isInstanceOf(SaleException.class);
        assertThatThrownBy(() -> new SaleTerms(1, "a", "x".repeat(2001), 1, START, END, 1, 1))
            .isInstanceOf(SaleException.class);
        assertThatThrownBy(() -> new SaleTerms(1, "a", "", 1_000_000_001L, START, END, 1, 1))
            .isInstanceOf(SaleException.class);
        assertThatThrownBy(() -> new SaleTerms(1, "a", "", 1, START, END, 1_000_001, 1))
            .isInstanceOf(SaleException.class);
        assertThatThrownBy(() -> new SaleTerms(1, "a", "", 1, START, END, 1, 0))
            .isInstanceOf(SaleException.class);
    }

}
