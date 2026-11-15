package com.wooyongjeong.limitedsale.order.application;

import com.wooyongjeong.limitedsale.payment.api.PaymentDetails;

record PaymentAttempt(long orderId, PaymentDetails payment, Action action) {
    enum Action { NOT_FOUND, DONE, WAIT, CHARGE, QUERY, CLOSE }
}
