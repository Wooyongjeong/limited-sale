package com.wooyongjeong.limitedsale.product.infrastructure.persistence;

import com.wooyongjeong.limitedsale.product.domain.Product;
import com.wooyongjeong.limitedsale.product.domain.ProductInput;
import com.wooyongjeong.limitedsale.product.domain.ProductStore;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.hibernate.Session;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
public class JpaProductStore implements ProductStore {
    private final ProductJpaRepository repository;
    private final EntityManager entityManager;

    public JpaProductStore(ProductJpaRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    public Product create(ProductInput input) {
        var entity = new ProductEntity(input);
        entityManager.persist(entity);
        return entity.toProduct();
    }

    @Override
    public Optional<Product> find(long id) {
        return Optional.ofNullable(entityManager.find(ProductEntity.class, id)).map(ProductEntity::toProduct);
    }

    @Override
    public Results list(int page, int size) {
        Pageable request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        long offset = request.getOffset();
        if (offset <= Integer.MAX_VALUE) {
            var result = repository.findAll(request);
            return new Results(result.getContent().stream().map(ProductEntity::toProduct).toList(),
                result.getTotalElements());
        }
        long total = repository.count();
        if (offset >= total) {
            return new Results(List.of(), total);
        }
        var entities = entityManager.unwrap(Session.class).createNativeQuery(
            "select * from products order by id desc limit :size offset :offset", ProductEntity.class)
            .setParameter("size", size).setParameter("offset", offset).getResultList();
        return new Results(entities.stream().map(ProductEntity::toProduct).toList(), total);
    }
}
