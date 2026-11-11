package com.wooyongjeong.limitedsale.sale.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SaleStore {
    Instant now();
    Sale create(SaleTerms terms, String productName, Instant now);
    Optional<Sale> find(long id);
    Optional<Sale> lock(long id);
    Sale update(Sale sale);
    void delete(long id);
    Results list(int page, int size, Long productId, SaleStatus status, Instant now);

    record Results(List<Sale> items, long total) {
        public Results {
            items = List.copyOf(items);
        }
    }
}
