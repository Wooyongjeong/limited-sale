package com.wooyongjeong.limitedsale.order.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface OrderOutboxJpaRepository extends JpaRepository<OrderOutboxEntity, Long> {
}
