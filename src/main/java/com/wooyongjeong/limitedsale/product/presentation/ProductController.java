package com.wooyongjeong.limitedsale.product.presentation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.wooyongjeong.limitedsale.common.web.ApiResponse;
import com.wooyongjeong.limitedsale.product.api.ProductCommand;
import com.wooyongjeong.limitedsale.product.api.ProductDetails;
import com.wooyongjeong.limitedsale.product.api.ProductPage;
import com.wooyongjeong.limitedsale.product.api.ProductQuery;
import com.wooyongjeong.limitedsale.product.domain.ProductValidationException;
import com.wooyongjeong.limitedsale.product.presentation.code.ProductResponseCode;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProductController {
    private static final Set<String> FIELDS = Set.of("name", "description", "basePrice");
    private final ProductCommand command;
    private final ProductQuery query;

    public ProductController(ProductCommand command, ProductQuery query) {
        this.command = command;
        this.query = query;
    }

    @JsonDeserialize(using = RegistrationDeserializer.class)
    public record Registration(JsonNode input) {
    }

    public static class RegistrationDeserializer extends JsonDeserializer<Registration> {
        @Override
        public Registration deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode input = parser.getCodec().readTree(parser);
            if (parser.nextToken() != null) {
                throw JsonMappingException.from(parser, "Expected one JSON value");
            }
            return new Registration(input);
        }
    }

    @PostMapping(path = "/api/v1/admin/products", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ProductDetails>> register(@RequestBody Registration registration) {
        JsonNode input = registration.input();
        if (!input.isObject()) {
            throw new ProductValidationException("body", "제품 등록 요청은 JSON 객체여야 합니다");
        }
        input.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) {
                throw new ProductValidationException("body", "알 수 없는 요청 필드가 있습니다");
            }
        });
        JsonNode name = input.get("name");
        JsonNode description = input.get("description");
        JsonNode price = input.get("basePrice");
        if (name != null && !name.isNull() && !name.isTextual()) {
            throw new ProductValidationException("name", "제품명은 문자열이어야 합니다");
        }
        if (description != null && !description.isNull() && !description.isTextual()) {
            throw new ProductValidationException("description", "설명은 문자열이어야 합니다");
        }
        if (price == null || !price.isIntegralNumber() || !price.canConvertToLong()) {
            throw new ProductValidationException("basePrice", "기준 가격은 정수여야 합니다");
        }
        ProductDetails created = command.register(name == null || name.isNull() ? null : name.textValue(),
            description == null || description.isNull() ? null : description.textValue(), price.longValue());
        return ResponseEntity.status(201).body(ApiResponse.of(ProductResponseCode.PRODUCT_CREATED, created));
    }

    @GetMapping(path = "/api/v1/products", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ProductPage> list(@RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        return ApiResponse.of(ProductResponseCode.PRODUCTS_FOUND,
            query.list(page == null ? 0 : integer(page, "page"), size == null ? 20 : integer(size, "size")));
    }

    @GetMapping(path = "/api/v1/products/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<ProductDetails>> detail(@PathVariable String id) {
        long productId;
        try {
            if (!id.matches("[0-9]+")) {
                throw new NumberFormatException();
            }
            productId = Long.parseLong(id);
        } catch (NumberFormatException exception) {
            throw new ProductValidationException("productId", "제품 ID는 양의 정수여야 합니다");
        }
        return query.find(productId)
            .map(product -> ResponseEntity.ok(ApiResponse.of(ProductResponseCode.PRODUCT_FOUND, product)))
            .orElseGet(() -> ResponseEntity.status(404)
                .body(ApiResponse.of(ProductResponseCode.PRODUCT_NOT_FOUND, null)));
    }

    private static int integer(String value, String field) {
        try {
            if (!value.matches("[0-9]+")) {
                throw new NumberFormatException();
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new ProductValidationException(field, "올바른 정수를 입력하세요");
        }
    }
}
