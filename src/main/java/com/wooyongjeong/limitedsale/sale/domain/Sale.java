package com.wooyongjeong.limitedsale.sale.domain;

import java.time.Instant;

public record Sale(long id, SaleTerms terms, String productNameSnapshot, int reservedQuantity,
        int soldQuantity, boolean stopped, long version, Instant createdAt, Instant updatedAt) {
    public SaleStatus statusAt(Instant now) {
        if (!now.isBefore(terms.endsAt())) {
            return SaleStatus.ENDED;
        }
        if (stopped) {
            return SaleStatus.STOPPED;
        }
        if (now.isBefore(terms.startsAt())) {
            return SaleStatus.SCHEDULED;
        }
        return soldQuantity == terms.totalQuantity() ? SaleStatus.SOLD_OUT : SaleStatus.OPEN;
    }

    public Sale revise(SaleTerms replacement, String snapshot, boolean restrictedFields,
            long expectedVersion, Instant now) {
        boolean fixedTermsChanged = terms.productId() != replacement.productId()
            || terms.unitPrice() != replacement.unitPrice() || !terms.startsAt().equals(replacement.startsAt())
            || !terms.endsAt().equals(replacement.endsAt()) || terms.totalQuantity() != replacement.totalQuantity()
            || terms.perMemberLimit() != replacement.perMemberLimit();
        requireRevisionAllowed(restrictedFields || fixedTermsChanged, expectedVersion, now);
        if (now.isBefore(terms.startsAt())) {
            replacement.requireFutureStart(now);
        }
        if (terms.equals(replacement) && productNameSnapshot.equals(snapshot)) {
            return this;
        }
        return new Sale(id, replacement, snapshot, reservedQuantity, soldQuantity, stopped, version, createdAt, now);
    }

    public void requireRevisionAllowed(boolean restrictedFields, long expectedVersion, Instant now) {
        if (expectedVersion != version) {
            throw new SaleException(SaleException.Kind.VERSION_CONFLICT, "다른 작업으로 이벤트 버전이 변경됐습니다");
        }
        if (restrictedFields && !now.isBefore(terms.startsAt())) {
            throw new SaleException(SaleException.Kind.STARTED, "시작 후에는 이름·설명만 수정할 수 있습니다");
        }
    }

    public Sale operate(boolean stop, Instant now) {
        if (!now.isBefore(terms.endsAt())) {
            throw new SaleException(SaleException.Kind.ENDED, "종료된 이벤트는 중단·재개할 수 없습니다");
        }
        if (stopped == stop) {
            return this;
        }
        return new Sale(id, terms, productNameSnapshot, reservedQuantity, soldQuantity, stop, version, createdAt, now);
    }

    public void requireDeletable(Instant now) {
        if (!now.isBefore(terms.startsAt())) {
            throw new SaleException(SaleException.Kind.STARTED, "시작 후에는 삭제할 수 없습니다");
        }
    }
}
