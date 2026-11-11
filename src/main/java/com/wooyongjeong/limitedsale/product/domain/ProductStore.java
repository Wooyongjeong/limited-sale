package com.wooyongjeong.limitedsale.product.domain;

import java.util.List;
import java.util.Optional;

public interface ProductStore {
    Product create(ProductInput input);

    Optional<Product> find(long id);

    Results list(int page, int size);

    record Results(List<Product> items, long totalElements) {
        public Results {
            items = List.copyOf(items);
        }
    }
}
