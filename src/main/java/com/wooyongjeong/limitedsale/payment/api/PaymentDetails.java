package com.wooyongjeong.limitedsale.payment.api;

import java.util.UUID;

public record PaymentDetails(UUID key, long amount, String status) {
}
