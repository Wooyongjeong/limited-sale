package com.wooyongjeong.limitedsale.product.api;

public interface ProductCommand {
    ProductDetails register(String name, String description, Long basePrice);
}
