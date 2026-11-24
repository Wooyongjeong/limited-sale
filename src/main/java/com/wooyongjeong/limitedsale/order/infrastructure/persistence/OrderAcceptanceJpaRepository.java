package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OrderAcceptanceJpaRepository extends JpaRepository<OrderAcceptanceEntity, Long> {
    Optional<OrderAcceptanceEntity> findByMemberIdAndRequestKey(long memberId, UUID requestKey);

    Optional<OrderAcceptanceEntity> findByMemberIdAndPublicId(long memberId, UUID publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from OrderAcceptanceEntity a where a.publicId = :publicId")
    Optional<OrderAcceptanceEntity> findForUpdate(@Param("publicId") UUID publicId);
}
