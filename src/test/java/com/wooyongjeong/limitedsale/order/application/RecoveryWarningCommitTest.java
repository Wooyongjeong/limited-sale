package com.wooyongjeong.limitedsale.order.application;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wooyongjeong.limitedsale.order.domain.OrderStore;
import com.wooyongjeong.limitedsale.order.domain.PaymentOrder;
import com.wooyongjeong.limitedsale.order.domain.PaymentOrderStore;
import com.wooyongjeong.limitedsale.payment.api.PaymentDetails;
import com.wooyongjeong.limitedsale.payment.api.PaymentOperations;
import com.wooyongjeong.limitedsale.sale.api.SaleReservation;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecoveryWarningCommitTest {
    private final PaymentOrderStore orders = mock(PaymentOrderStore.class);
    private final PaymentOperations payments = mock(PaymentOperations.class);
    private final OrderPaymentTransaction transaction = new OrderPaymentTransaction(orders, mock(OrderStore.class),
            payments, mock(SaleReservation.class), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(5));
    private final Logger logger = (Logger) LoggerFactory.getLogger(OrderPaymentTransaction.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final UUID token = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
        TransactionSynchronizationManager.initSynchronization();
        when(orders.lock(1)).thenReturn(new PaymentOrder(1, UUID.randomUUID(), 2, 3, 1, "PAYMENT_PENDING",
                now.minusSeconds(1), now, token, now.plusSeconds(30), false, null));
        when(payments.lock(1)).thenReturn(new PaymentDetails(UUID.randomUUID(), 1000, "UNKNOWN"));
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void rolledBackReviewDoesNotEmitWarning() {
        when(orders.databaseTime()).thenReturn(now, now.plusSeconds(31));
        assertThatThrownBy(() -> transaction.finish(1, token, true)).isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.getSynchronizations().forEach(
                sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(logs.list).isEmpty();
    }

    @Test
    void warningIsEmittedOnlyAfterCommit() {
        when(orders.databaseTime()).thenReturn(now);
        transaction.finish(1, token, true);
        assertThat(logs.list).isEmpty();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(logs.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("requires payment review"));
    }
}
