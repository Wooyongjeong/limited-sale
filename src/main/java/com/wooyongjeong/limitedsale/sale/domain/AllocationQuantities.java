package com.wooyongjeong.limitedsale.sale.domain;

public record AllocationQuantities(int reserved, int finalized) {
    public AllocationQuantities finish(int quantity, boolean confirmed) {
        if (quantity <= 0 || reserved < quantity) {
            throw new IllegalStateException("Insufficient reserved quantity");
        }
        int result = confirmed ? Math.addExact(finalized, quantity) : finalized;
        return new AllocationQuantities(reserved - quantity, result);
    }
}
