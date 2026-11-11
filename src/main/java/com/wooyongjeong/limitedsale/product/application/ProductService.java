package com.wooyongjeong.limitedsale.product.application;

import com.wooyongjeong.limitedsale.product.api.ProductCommand;
import com.wooyongjeong.limitedsale.product.api.ProductDetails;
import com.wooyongjeong.limitedsale.product.api.ProductListItem;
import com.wooyongjeong.limitedsale.product.api.ProductPage;
import com.wooyongjeong.limitedsale.product.api.ProductQuery;
import com.wooyongjeong.limitedsale.product.domain.Product;
import com.wooyongjeong.limitedsale.product.domain.ProductInput;
import com.wooyongjeong.limitedsale.product.domain.ProductStore;
import com.wooyongjeong.limitedsale.product.domain.ProductValidationException;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService implements ProductCommand, ProductQuery {
    private final ProductStore store;

    public ProductService(ProductStore store) {
        this.store = store;
    }

    @Override
    @Transactional
    public ProductDetails register(String name, String description, Long basePrice) {
        return details(store.create(ProductInput.create(name, description, basePrice)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductDetails> find(long productId) {
        if (productId <= 0) {
            throw new ProductValidationException("productId", "제품 ID는 양의 정수여야 합니다");
        }
        return store.find(productId).map(ProductService::details);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductPage list(int page, int size) {
        if (page < 0) {
            throw new ProductValidationException("page", "페이지는 0 이상의 정수여야 합니다");
        }
        if (size < 1 || size > 100) {
            throw new ProductValidationException("size", "페이지 크기는 1~100이어야 합니다");
        }
        var result = store.list(page, size);
        var items = result.items().stream().map(product -> new ProductListItem(product.id(), product.name(),
            product.basePrice(), product.createdAt(), product.updatedAt())).toList();
        long total = result.totalElements();
        return new ProductPage(items, page, size, total, total / size + (total % size == 0 ? 0 : 1));
    }

    private static ProductDetails details(Product product) {
        return new ProductDetails(product.id(), product.name(), product.description(), product.basePrice(),
            product.createdAt(), product.updatedAt());
    }
}
