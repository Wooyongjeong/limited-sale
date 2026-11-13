package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PurchaseOrderJpaRepository extends JpaRepository<PurchaseOrderEntity, Long> {
    Optional<PurchaseOrderEntity> findByPublicId(UUID publicId);

    Optional<PurchaseOrderEntity> findByMemberIdAndPublicId(long memberId, UUID publicId);
}
