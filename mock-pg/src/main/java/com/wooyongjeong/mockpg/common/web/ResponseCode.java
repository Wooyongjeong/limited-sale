package com.wooyongjeong.mockpg.common.web;

public interface ResponseCode {
    int httpStatus();
    String code();
    String message();
}
