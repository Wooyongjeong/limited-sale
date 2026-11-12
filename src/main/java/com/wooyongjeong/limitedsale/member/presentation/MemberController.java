package com.wooyongjeong.limitedsale.member.presentation;

import com.wooyongjeong.limitedsale.common.web.ApiResponse;
import com.wooyongjeong.limitedsale.member.application.MemberService;
import com.wooyongjeong.limitedsale.member.domain.Member;
import com.wooyongjeong.limitedsale.member.presentation.code.MemberResponseCode;
import com.wooyongjeong.limitedsale.member.presentation.request.MemberRegistrationRequest;
import com.wooyongjeong.limitedsale.member.presentation.response.MemberResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/members")
public class MemberController {
    private final MemberService service;

    public MemberController(MemberService service) {
        this.service = service;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<MemberResponse>> register(@RequestBody MemberRegistrationRequest input) {
        Member member = service.register(input.email(), input.password(), input.nickname());
        return ResponseEntity.status(201)
            .body(ApiResponse.of(MemberResponseCode.MEMBER_CREATED, MemberResponse.from(member)));
    }
}
