package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.payment.api.PaymentDetails;
import java.util.UUID;

record PaymentAttempt(long orderId, PaymentDetails payment, Action action, UUID token) {
    enum Action { NOT_FOUND, DONE, WAIT, CHARGE, QUERY, CLOSE }
}
