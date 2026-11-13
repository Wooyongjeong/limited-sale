package com.wooyongjeong.limitedsale.order.api;

import java.util.OptionalLong;
import java.util.UUID;

public interface OrderLookup {
    OptionalLong findId(UUID publicId);
}
