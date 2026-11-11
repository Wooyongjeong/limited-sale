package com.wooyongjeong.limitedsale.product.api;

import java.util.Optional;

public interface ProductQuery {
    Optional<ProductDetails> find(long productId);

    ProductPage list(int page, int size);
}
