package com.wooyongjeong.limitedsale.sale.infrastructure.persistence;

import com.wooyongjeong.limitedsale.sale.domain.SaleStatus;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.hibernate.Session;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JpaSaleStoreTest {
    @Test
    void offsetAboveIntegerRangeIsNeverNarrowedAndKeepsStatusFilterTime() {
        var repository = mock(SaleJpaRepository.class);
        var manager = mock(EntityManager.class);
        var session = mock(Session.class);
        @SuppressWarnings("unchecked")
        NativeQuery<SaleEntity> query = mock(NativeQuery.class);
        Instant now = Instant.parse("2030-01-01T00:00:00.123456Z");
        when(repository.countFiltered(9L, "STOPPED", now)).thenReturn(5_000_000_000L);
        when(manager.unwrap(Session.class)).thenReturn(session);
        when(session.createNativeQuery(any(String.class), eq(SaleEntity.class))).thenReturn(query);
        when(query.setParameter("productId", 9L)).thenReturn(query);
        when(query.setParameter("status", "STOPPED")).thenReturn(query);
        when(query.setParameter("now", now)).thenReturn(query);
        when(query.setParameter("size", 2)).thenReturn(query);
        when(query.setParameter("offset", 4_294_967_294L)).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        var result = new JpaSaleStore(repository, manager).list(Integer.MAX_VALUE, 2, 9L, SaleStatus.STOPPED, now);
        assertThat(result.total()).isEqualTo(5_000_000_000L);
        verify(query).setParameter("offset", 4_294_967_294L);
    }

    @Test
    void offsetBeyondEndDoesNotExecuteHugeDataQuery() {
        var repository = mock(SaleJpaRepository.class);
        var manager = mock(EntityManager.class);
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        when(repository.countFiltered(null, null, now)).thenReturn(1L);
        var result = new JpaSaleStore(repository, manager).list(Integer.MAX_VALUE, 100, null, null, now);
        assertThat(result.items()).isEmpty();
        assertThat(result.total()).isEqualTo(1);
        verifyNoInteractions(manager);
    }
}
