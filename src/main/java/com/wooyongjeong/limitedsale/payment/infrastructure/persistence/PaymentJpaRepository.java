package com.wooyongjeong.limitedsale.payment.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PaymentJpaRepository extends JpaRepository<PaymentEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentEntity p where p.orderId = :orderId")
    Optional<PaymentEntity> lockByOrderId(@Param("orderId") long orderId);

    Optional<PaymentEntity> findByOrderId(long orderId);
}
