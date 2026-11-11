package com.wooyongjeong.limitedsale.sale.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SaleJpaRepository extends JpaRepository<SaleEntity, Long> {
    String FILTER = """
         where (:productId is null or s.productId = :productId)
           and (:status is null or (case
             when s.endsAt <= :now then 'ENDED'
             when s.isStopped = true then 'STOPPED'
             when s.startsAt > :now then 'SCHEDULED'
             when s.soldQuantity = s.totalQuantity then 'SOLD_OUT'
             else 'OPEN' end) = :status)
        """;

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SaleEntity s where s.id = :id")
    Optional<SaleEntity> findForUpdate(@Param("id") long id);

    // Read a UTC string so JDBC/JVM timezone conversion cannot shift the DB clock.
    @Query(value = "select DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%s.%fZ')", nativeQuery = true)
    String databaseTime();

    @Query(value = "select s from SaleEntity s" + FILTER, countQuery = "select count(s) from SaleEntity s" + FILTER)
    Page<SaleEntity> findPage(@Param("productId") Long productId, @Param("status") String status,
        @Param("now") Instant now, Pageable pageable);

    @Query("select count(s) from SaleEntity s" + FILTER)
    long countFiltered(@Param("productId") Long productId, @Param("status") String status, @Param("now") Instant now);
}
