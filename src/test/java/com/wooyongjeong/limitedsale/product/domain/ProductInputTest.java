package com.wooyongjeong.limitedsale.product.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductInputTest {
    @Test
    void normalizesNameAndPreservesDescription() {
        var input = ProductInput.create("  상품  이름  ", "  설명\n ", 1L);
        assertThat(input.name()).isEqualTo("상품  이름");
        assertThat(input.description()).isEqualTo("  설명\n ");
        assertThat(ProductInput.create("x", null, 1_000_000_000L).description()).isEmpty();
    }

    @Test
    void countsSupplementaryCharactersAsCharacters() {
        String symbol = "😀";
        var input = ProductInput.create(symbol.repeat(100), symbol.repeat(2000), 1L);
        assertThat(input.name()).isEqualTo(symbol.repeat(100));
        assertThat(input.description()).isEqualTo(symbol.repeat(2000));
        assertThatThrownBy(() -> ProductInput.create(symbol.repeat(101), "", 1L))
            .isInstanceOf(ProductValidationException.class);
        assertThatThrownBy(() -> ProductInput.create("x", symbol.repeat(2001), 1L))
            .isInstanceOf(ProductValidationException.class);
    }

    @Test
    void rejectsInvalidBounds() {
        assertThatThrownBy(() -> ProductInput.create("  ", "", 1L)).isInstanceOf(ProductValidationException.class);
        assertThatThrownBy(() -> ProductInput.create("x".repeat(101), "", 1L))
            .isInstanceOf(ProductValidationException.class);
        assertThatThrownBy(() -> ProductInput.create("x", "x".repeat(2001), 1L))
            .isInstanceOf(ProductValidationException.class);
        assertThatThrownBy(() -> ProductInput.create("x", "", 0L)).isInstanceOf(ProductValidationException.class);
        assertThatThrownBy(() -> ProductInput.create("x", "", 1_000_000_001L))
            .isInstanceOf(ProductValidationException.class);
    }
}
