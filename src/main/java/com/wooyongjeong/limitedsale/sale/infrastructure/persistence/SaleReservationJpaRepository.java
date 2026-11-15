package com.wooyongjeong.limitedsale.sale.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SaleReservationJpaRepository extends JpaRepository<SaleReservationEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SaleReservationEntity r where r.orderId = :orderId")
    Optional<SaleReservationEntity> findForUpdate(@Param("orderId") long orderId);
}
