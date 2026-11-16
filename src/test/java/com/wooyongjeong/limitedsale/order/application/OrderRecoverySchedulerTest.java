package com.wooyongjeong.limitedsale.order.application;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderRecoverySchedulerTest {
    @Test
    void schedulerIsEnabledByDefaultAndCanBeDisabled() {
        var runner = new ApplicationContextRunner().withUserConfiguration(OrderRecoveryScheduler.class)
                .withBean(OrderPaymentTransaction.class, () -> mock(OrderPaymentTransaction.class))
                .withBean(OrderPaymentService.class, () -> mock(OrderPaymentService.class));
        runner.run(context -> assertThat(context).hasSingleBean(OrderRecoveryScheduler.class));
        runner.withPropertyValues("limited-sale.recovery.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(OrderRecoveryScheduler.class));
    }

    @Test
    void rejectsZeroSchedulingInterval() {
        new ApplicationContextRunner().withUserConfiguration(OrderRecoveryScheduler.class)
                .withBean(OrderPaymentTransaction.class, () -> mock(OrderPaymentTransaction.class))
                .withBean(OrderPaymentService.class, () -> mock(OrderPaymentService.class))
                .withPropertyValues("limited-sale.recovery.interval=PT0S")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shutdownPreventsFurtherPolling() {
        var transaction = mock(OrderPaymentTransaction.class);
        var service = mock(OrderPaymentService.class);
        var scheduler = new OrderRecoveryScheduler(transaction, service, 20, 4);
        scheduler.shutdown();
        scheduler.poll();
        verify(transaction, never()).candidates(anyInt());
    }

    @Test
    void oneJobFailureDoesNotPreventAnotherJob() throws Exception {
        var transaction = mock(OrderPaymentTransaction.class);
        var service = mock(OrderPaymentService.class);
        var completed = new CountDownLatch(1);
        when(transaction.candidates(20)).thenReturn(List.of(1L, 2L));
        doThrow(new IllegalStateException("test failure")).when(service).recover(1);
        doAnswer(call -> {
            completed.countDown();
            return null;
        }).when(service).recover(2);
        var scheduler = new OrderRecoveryScheduler(transaction, service, 20, 2);
        try {
            scheduler.poll();
            assertThat(completed.await(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            scheduler.shutdown();
        }
        verify(service).recover(2);
    }

    @Test
    void fullCapacityDoesNotQueueAJobThatCouldClaimALease() throws Exception {
        var transaction = mock(OrderPaymentTransaction.class);
        var service = mock(OrderPaymentService.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(transaction.candidates(20)).thenReturn(List.of(1L, 2L));
        doAnswer(call -> {
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            return null;
        }).when(service).recover(1);
        var scheduler = new OrderRecoveryScheduler(transaction, service, 20, 1);
        try {
            scheduler.poll();
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            scheduler.poll();
            verify(service, never()).recover(2);
        } finally {
            release.countDown();
            scheduler.shutdown();
        }
    }

    @Test
    void rejectsNonPositiveCapacity() {
        var transaction = mock(OrderPaymentTransaction.class);
        var service = mock(OrderPaymentService.class);
        assertThatThrownBy(() -> new OrderRecoveryScheduler(transaction, service, 0, 4))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderRecoveryScheduler(transaction, service, 20, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
