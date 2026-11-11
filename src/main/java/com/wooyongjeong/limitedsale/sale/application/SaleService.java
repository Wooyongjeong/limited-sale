package com.wooyongjeong.limitedsale.sale.application;

import com.wooyongjeong.limitedsale.product.api.ProductDetails;
import com.wooyongjeong.limitedsale.product.api.ProductQuery;
import com.wooyongjeong.limitedsale.sale.api.SaleCommand;
import com.wooyongjeong.limitedsale.sale.api.SaleCreation;
import com.wooyongjeong.limitedsale.sale.api.SaleDetails;
import com.wooyongjeong.limitedsale.sale.api.SaleListItem;
import com.wooyongjeong.limitedsale.sale.api.SalePage;
import com.wooyongjeong.limitedsale.sale.api.SalePatch;
import com.wooyongjeong.limitedsale.sale.api.SaleQuery;
import com.wooyongjeong.limitedsale.sale.domain.Sale;
import com.wooyongjeong.limitedsale.sale.domain.SaleException;
import com.wooyongjeong.limitedsale.sale.domain.SaleStatus;
import com.wooyongjeong.limitedsale.sale.domain.SaleStore;
import com.wooyongjeong.limitedsale.sale.domain.SaleTerms;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SaleService implements SaleCommand, SaleQuery {
    private final SaleStore store;
    private final ProductQuery products;

    public SaleService(SaleStore store, ProductQuery products) {
        this.store = store;
        this.products = products;
    }

    @Override
    @Transactional
    public SaleDetails create(SaleCreation input) {
        ProductDetails product = product(input.productId());
        SaleTerms terms = new SaleTerms(input.productId(), input.name(), input.description(),
            input.unitPrice() == null ? product.basePrice() : input.unitPrice(), input.startsAt(), input.endsAt(),
            input.totalQuantity(), input.perMemberLimit() == null ? 1 : input.perMemberLimit());
        Instant now = store.now();
        terms.requireFutureStart(now);
        return details(store.create(terms, product.name(), now), now);
    }

    @Override
    @Transactional
    public SaleDetails patch(long saleId, SalePatch input) {
        Sale current = locked(saleId);
        Instant now = store.now();
        if (input.version() < 0) {
            throw SaleException.invalid("version", "버전은 0 이상의 정수여야 합니다");
        }
        boolean restricted = input.productId() != null || input.unitPrice() != null || input.startsAt() != null
            || input.endsAt() != null || input.totalQuantity() != null || input.perMemberLimit() != null;
        current.requireRevisionAllowed(restricted, input.version(), now);
        SaleTerms old = current.terms();
        String snapshot = input.productId() == null ? current.productNameSnapshot() : product(input.productId()).name();
        SaleTerms replacement = new SaleTerms(input.productId() == null ? old.productId() : input.productId(),
            input.name() == null ? old.name() : input.name(),
            input.description() == null ? old.description() : input.description(),
            input.unitPrice() == null ? old.unitPrice() : input.unitPrice(),
            input.startsAt() == null ? old.startsAt() : input.startsAt(),
            input.endsAt() == null ? old.endsAt() : input.endsAt(),
            input.totalQuantity() == null ? old.totalQuantity() : input.totalQuantity(),
            input.perMemberLimit() == null ? old.perMemberLimit() : input.perMemberLimit());
        Sale changed = current.revise(replacement, snapshot, restricted, input.version(), now);
        return details(changed == current ? current : store.update(changed), now);
    }

    @Override
    @Transactional
    public void delete(long saleId) {
        Sale sale = locked(saleId);
        sale.requireDeletable(store.now());
        store.delete(saleId);
    }

    @Override
    @Transactional
    public SaleDetails stop(long saleId) {
        return operate(saleId, true);
    }

    @Override
    @Transactional
    public SaleDetails resume(long saleId) {
        return operate(saleId, false);
    }

    private SaleDetails operate(long saleId, boolean stop) {
        Sale current = locked(saleId);
        Instant now = store.now();
        Sale changed = current.operate(stop, now);
        return details(changed == current ? current : store.update(changed), now);
    }

    @Override
    @Transactional(readOnly = true)
    public SaleDetails get(long saleId) {
        requireId(saleId, "saleId");
        Sale sale = store.find(saleId).orElseThrow(() ->
            new SaleException(SaleException.Kind.NOT_FOUND, "판매 이벤트를 찾을 수 없습니다"));
        return details(sale, store.now());
    }

    @Override
    @Transactional(readOnly = true)
    public SalePage list(int page, int size, Long productId, String status) {
        if (page < 0) {
            throw SaleException.invalid("page", "페이지는 0 이상의 정수여야 합니다");
        }
        if (size < 1 || size > 100) {
            throw SaleException.invalid("size", "페이지 크기는 1~100이어야 합니다");
        }
        if (productId != null) {
            requireId(productId, "productId");
        }
        SaleStatus filter = null;
        if (status != null) {
            try {
                filter = SaleStatus.valueOf(status);
            } catch (IllegalArgumentException exception) {
                throw SaleException.invalid("status", "지원하지 않는 이벤트 상태입니다");
            }
        }
        Instant now = store.now();
        var result = store.list(page, size, productId, filter, now);
        var items = result.items().stream().map(sale -> {
            SaleDetails view = details(sale, now);
            return new SaleListItem(view.saleId(), view.productId(), view.name(), view.productNameSnapshot(),
                view.unitPrice(), view.startsAt(), view.endsAt(), view.totalQuantity(), view.perMemberLimit(),
                view.status(), view.version(), view.createdAt(), view.updatedAt());
        }).toList();
        long total = result.total();
        return new SalePage(items, page, size, total, total / size + (total % size == 0 ? 0 : 1));
    }

    private Sale locked(long id) {
        requireId(id, "saleId");
        return store.lock(id).orElseThrow(() ->
            new SaleException(SaleException.Kind.NOT_FOUND, "판매 이벤트를 찾을 수 없습니다"));
    }

    private ProductDetails product(long id) {
        requireId(id, "productId");
        return products.find(id).orElseThrow(() ->
            new SaleException(SaleException.Kind.PRODUCT_NOT_FOUND, "제품을 찾을 수 없습니다"));
    }

    private static void requireId(long id, String field) {
        if (id <= 0) {
            throw SaleException.invalid(field, "ID는 양의 정수여야 합니다");
        }
    }

    private static SaleDetails details(Sale sale, Instant now) {
        SaleTerms terms = sale.terms();
        return new SaleDetails(sale.id(), terms.productId(), terms.name(), terms.description(),
            sale.productNameSnapshot(), terms.unitPrice(), terms.startsAt(), terms.endsAt(), terms.totalQuantity(),
            terms.perMemberLimit(), sale.statusAt(now).name(), sale.version(), sale.createdAt(), sale.updatedAt());
    }
}
