package com.wooyongjeong.mockpg.application;

import com.wooyongjeong.mockpg.domain.PaymentResult;
import com.wooyongjeong.mockpg.domain.PaymentStore;
import com.wooyongjeong.mockpg.domain.ScenarioConfiguration;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {
    private final PaymentStore payments;

    public PaymentService(PaymentStore payments) {
        this.payments = payments;
    }

    public PaymentResult charge(UUID key, long amount) throws InterruptedException {
        PaymentResult result = payments.charge(key, amount);
        if (result.responseDelaySeconds() > 0) {
            Thread.sleep(result.responseDelaySeconds() * 1000L);
        }
        return result;
    }

    public PaymentResult close(UUID key, long amount) {
        return payments.close(key, amount);
    }

    public PaymentResult query(UUID key) {
        return payments.query(key);
    }

    public ScenarioConfiguration configure(UUID key, ScenarioConfiguration configuration) {
        return payments.configure(key, configuration);
    }
}
