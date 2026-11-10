package com.wooyongjeong.limitedsale.member.infrastructure;

import com.wooyongjeong.limitedsale.LimitedSaleApplication;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public final class AdminInitializer {
    private AdminInitializer() {
    }

    public static void main(String[] args) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(LimitedSaleApplication.class)
                .web(WebApplicationType.NONE).run(args)) {
            context.getBean(MemberService.class).initializeAdmin(System.getenv("ADMIN_EMAIL"),
                System.getenv("ADMIN_PASSWORD"), System.getenv("ADMIN_NICKNAME"));
            System.out.println("관리자 초기화 완료");
        } catch (RuntimeException exception) {
            System.err.println("관리자 초기화에 실패했습니다. 입력과 기존 계정을 확인하세요.");
            System.exit(1);
        }
    }
}
