package com.wooyongjeong.limitedsale.common.web;

public interface ResponseCode {
    int httpStatus();
    String code();
    String message();
}
