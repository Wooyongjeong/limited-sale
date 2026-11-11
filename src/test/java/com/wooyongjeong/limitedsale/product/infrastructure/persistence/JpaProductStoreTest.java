package com.wooyongjeong.limitedsale.product.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JpaProductStoreTest {
    @Test
    void normalPageUsesPageableWithDescendingId() {
        var repository = mock(ProductJpaRepository.class);
        var entityManager = mock(EntityManager.class);
        when(repository.findAll(any(Pageable.class))).thenAnswer(invocation ->
            new PageImpl<ProductEntity>(List.of(), invocation.getArgument(0), 0));
        var result = new JpaProductStore(repository, entityManager).list(2, 10);
        var page = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(page.capture());
        assertThat(page.getValue().getPageNumber()).isEqualTo(2);
        assertThat(page.getValue().getPageSize()).isEqualTo(10);
        assertThat(page.getValue().getSort().getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(result.items()).isEmpty();
    }

    @Test
    void hugeOffsetUsesLongWithoutNarrowing() {
        var repository = mock(ProductJpaRepository.class);
        var entityManager = mock(EntityManager.class);
        var session = mock(Session.class);
        @SuppressWarnings("unchecked")
        NativeQuery<ProductEntity> query = mock(NativeQuery.class);
        when(repository.count()).thenReturn(5_000_000_000L);
        when(entityManager.unwrap(Session.class)).thenReturn(session);
        when(session.createNativeQuery(any(String.class), eq(ProductEntity.class))).thenReturn(query);
        when(query.setParameter("size", 2)).thenReturn(query);
        when(query.setParameter("offset", 4_294_967_294L)).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        var result = new JpaProductStore(repository, entityManager).list(Integer.MAX_VALUE, 2);
        assertThat(result.totalElements()).isEqualTo(5_000_000_000L);
        verify(query).setParameter("offset", 4_294_967_294L);
    }

    @Test
    void hugeOffsetBeyondEndReturnsEmptyWithoutQuery() {
        var repository = mock(ProductJpaRepository.class);
        var entityManager = mock(EntityManager.class);
        when(repository.count()).thenReturn(5L);
        var result = new JpaProductStore(repository, entityManager).list(Integer.MAX_VALUE, 100);
        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(5L);
        org.mockito.Mockito.verifyNoInteractions(entityManager);
    }
}
