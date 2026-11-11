package com.wooyongjeong.limitedsale.product.presentation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductExceptionHandlerTest {
    @Test
    void unexpectedIllegalArgumentIsServerError() throws Exception {
        var result = new ProductExceptionHandler().unexpected(new IllegalArgumentException("internal secret"));
        assertThat(result.getStatusCode().value()).isEqualTo(500);
        assertThat(result.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(result.getBody().message()).doesNotContain("internal secret");
    }
}
