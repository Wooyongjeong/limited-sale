package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrderAcceptanceJpaRepository extends JpaRepository<OrderAcceptanceEntity, Long> {
    Optional<OrderAcceptanceEntity> findByMemberIdAndRequestKey(long memberId, UUID requestKey);

    Optional<OrderAcceptanceEntity> findByMemberIdAndPublicId(long memberId, UUID publicId);
}
