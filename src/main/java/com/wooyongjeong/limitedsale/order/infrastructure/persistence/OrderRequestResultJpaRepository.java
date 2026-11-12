package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrderRequestResultJpaRepository extends JpaRepository<OrderRequestResultEntity, Long> {
    Optional<OrderRequestResultEntity> findByMemberIdAndRequestKey(long memberId, UUID requestKey);
}
