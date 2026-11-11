package com.wooyongjeong.limitedsale.product.infrastructure.persistence;

import com.wooyongjeong.limitedsale.product.domain.Product;
import com.wooyongjeong.limitedsale.product.domain.ProductInput;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Entity
@Table(name = "products")
public class ProductEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(nullable = false)
    private long basePrice;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected ProductEntity() {
    }

    ProductEntity(ProductInput input) {
        name = input.name();
        description = input.description();
        basePrice = input.basePrice();
        createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        updatedAt = createdAt;
    }

    Product toProduct() {
        return new Product(id, name, description, basePrice, createdAt, updatedAt);
    }
}
