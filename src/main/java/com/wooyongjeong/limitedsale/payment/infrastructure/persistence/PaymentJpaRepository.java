package com.wooyongjeong.limitedsale.payment.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentJpaRepository extends JpaRepository<PaymentEntity, Long> {
}
