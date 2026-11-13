package com.wooyongjeong.limitedsale.order.presentation;

import com.wooyongjeong.limitedsale.common.web.ResponseCode;

public enum SimulationResponseCode implements ResponseCode {
    SIMULATION_CONFIGURED(200, "시나리오를 설정했습니다"),
    SIMULATION_CONFLICT(409, "결제 처리가 시작되어 설정을 변경할 수 없습니다"),
    SIMULATION_UNAVAILABLE(503, "결제 시뮬레이션 서버에 연결할 수 없습니다");

    private final int status;
    private final String message;

    SimulationResponseCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    public int httpStatus() {
        return status;
    }

    public String code() {
        return name();
    }

    public String message() {
        return message;
    }
}
