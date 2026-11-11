package com.wooyongjeong.limitedsale.sale.infrastructure.persistence;

import com.wooyongjeong.limitedsale.sale.domain.Sale;
import com.wooyongjeong.limitedsale.sale.domain.SaleStatus;
import com.wooyongjeong.limitedsale.sale.domain.SaleStore;
import com.wooyongjeong.limitedsale.sale.domain.SaleTerms;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.hibernate.Session;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
public class JpaSaleStore implements SaleStore {
    private static final String LARGE_PAGE = """
        select * from sales
        where (:productId is null or product_id = :productId)
          and (:status is null or (case
            when ends_at <= :now then 'ENDED'
            when is_stopped = true then 'STOPPED'
            when starts_at > :now then 'SCHEDULED'
            when sold_quantity = total_quantity then 'SOLD_OUT'
            else 'OPEN' end) = :status)
        order by id desc limit :size offset :offset
        """;
    private final SaleJpaRepository repository;
    private final EntityManager entityManager;

    public JpaSaleStore(SaleJpaRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    public Instant now() {
        return Instant.parse(repository.databaseTime());
    }

    @Override
    public Sale create(SaleTerms terms, String productName, Instant now) {
        return repository.saveAndFlush(new SaleEntity(terms, productName, now)).toSale();
    }

    @Override
    public Optional<Sale> find(long id) {
        return repository.findById(id).map(SaleEntity::toSale);
    }

    @Override
    public Optional<Sale> lock(long id) {
        return repository.findForUpdate(id).map(SaleEntity::toSale);
    }

    @Override
    public Sale update(Sale sale) {
        SaleEntity entity = repository.getReferenceById(sale.id());
        entity.revise(sale);
        repository.flush();
        return entity.toSale();
    }

    @Override
    public void delete(long id) {
        repository.delete(repository.getReferenceById(id));
        repository.flush();
    }

    @Override
    public Results list(int page, int size, Long productId, SaleStatus status, Instant now) {
        String filter = status == null ? null : status.name();
        var request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        long offset = request.getOffset();
        if (offset <= Integer.MAX_VALUE) {
            var result = repository.findPage(productId, filter, now, request);
            return new Results(result.getContent().stream().map(SaleEntity::toSale).toList(),
                result.getTotalElements());
        }
        long total = repository.countFiltered(productId, filter, now);
        if (offset >= total) {
            return new Results(List.of(), total);
        }
        var entities = entityManager.unwrap(Session.class).createNativeQuery(LARGE_PAGE, SaleEntity.class)
            .setParameter("productId", productId).setParameter("status", filter)
            .setParameter("now", now)
            .setParameter("size", size).setParameter("offset", offset).getResultList();
        return new Results(entities.stream().map(SaleEntity::toSale).toList(), total);
    }
}
