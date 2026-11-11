package com.wooyongjeong.limitedsale.sale.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.wooyongjeong.limitedsale.common.web.ApiResponse;
import com.wooyongjeong.limitedsale.sale.api.SaleCommand;
import com.wooyongjeong.limitedsale.sale.api.SaleCreation;
import com.wooyongjeong.limitedsale.sale.api.SaleDetails;
import com.wooyongjeong.limitedsale.sale.api.SalePage;
import com.wooyongjeong.limitedsale.sale.api.SalePatch;
import com.wooyongjeong.limitedsale.sale.api.SaleQuery;
import com.wooyongjeong.limitedsale.sale.presentation.code.SaleResponseCode;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SaleController {
    private static final Set<String> CREATE_FIELDS = Set.of("productId", "name", "description", "unitPrice",
        "startsAt", "endsAt", "totalQuantity", "perMemberLimit");
    private static final Set<String> PATCH_FIELDS = Set.of("version", "productId", "name", "description", "unitPrice",
        "startsAt", "endsAt", "totalQuantity", "perMemberLimit");
    private final SaleCommand command;
    private final SaleQuery query;

    public SaleController(SaleCommand command, SaleQuery query) {
        this.command = command;
        this.query = query;
    }

    @PostMapping(path = "/api/v1/admin/sales", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<SaleDetails>> create(@RequestBody SaleJson.Body body) {
        JsonNode input = body.value();
        SaleJson.fields(input, CREATE_FIELDS);
        var created = command.create(new SaleCreation(SaleJson.number(input, "productId", true),
            SaleJson.text(input, "name", true), SaleJson.description(input), SaleJson.number(input, "unitPrice", false),
            SaleJson.time(input, "startsAt", true), SaleJson.time(input, "endsAt", true),
            SaleJson.integer(input, "totalQuantity", true), SaleJson.integer(input, "perMemberLimit", false)));
        return ResponseEntity.status(201).body(ApiResponse.of(SaleResponseCode.SALE_CREATED, created));
    }

    @PatchMapping(path = "/api/v1/admin/sales/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SaleDetails> patch(@PathVariable String id, @RequestBody SaleJson.Body body) {
        JsonNode input = body.value();
        SaleJson.fields(input, PATCH_FIELDS);
        var changed = command.patch(SaleJson.id(id, "saleId"), new SalePatch(SaleJson.number(input, "version", true),
            SaleJson.number(input, "productId", false), SaleJson.text(input, "name", false),
            SaleJson.description(input),
            SaleJson.number(input, "unitPrice", false), SaleJson.time(input, "startsAt", false),
            SaleJson.time(input, "endsAt", false), SaleJson.integer(input, "totalQuantity", false),
            SaleJson.integer(input, "perMemberLimit", false)));
        return ApiResponse.of(SaleResponseCode.SALE_UPDATED, changed);
    }

    @DeleteMapping(path = "/api/v1/admin/sales/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Void> delete(@PathVariable String id) {
        command.delete(SaleJson.id(id, "saleId"));
        return ApiResponse.of(SaleResponseCode.SALE_DELETED, null);
    }

    @PostMapping(path = "/api/v1/admin/sales/{id}/stop", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SaleDetails> stop(@PathVariable String id) {
        return ApiResponse.of(SaleResponseCode.SALE_STOPPED, command.stop(SaleJson.id(id, "saleId")));
    }

    @PostMapping(path = "/api/v1/admin/sales/{id}/resume", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SaleDetails> resume(@PathVariable String id) {
        return ApiResponse.of(SaleResponseCode.SALE_RESUMED, command.resume(SaleJson.id(id, "saleId")));
    }

    @GetMapping(path = "/api/v1/sales/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SaleDetails> get(@PathVariable String id) {
        return ApiResponse.of(SaleResponseCode.SALE_FOUND, query.get(SaleJson.id(id, "saleId")));
    }

    @GetMapping(path = "/api/v1/sales", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SalePage> list(@RequestParam(required = false) String page,
            @RequestParam(required = false) String size, @RequestParam(required = false) String productId,
            @RequestParam(required = false) String status) {
        return ApiResponse.of(SaleResponseCode.SALES_FOUND, query.list(
            page == null ? 0 : SaleJson.pageNumber(page, "page"),
            size == null ? 20 : SaleJson.pageNumber(size, "size"),
            productId == null ? null : SaleJson.id(productId, "productId"), status));
    }
}
