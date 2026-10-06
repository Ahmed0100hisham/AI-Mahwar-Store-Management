package com.almahwar.api.product;

import com.almahwar.api.error.FieldValidationException;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Allowed sort orders of the product list. The client names a field ({@code sort=name}, {@code sort=salePrice,desc});
 * only these fields exist and each maps to a fixed SQL expression, so no client text ever reaches the ORDER BY.
 * A unique tie-breaker ({@code product_id}) keeps paging stable.
 */
public enum ProductSort {

    NAME("name", "p.name_ar"),
    CODE("code", "p.product_code"),
    SALE_PRICE("salePrice", "p.sale_price"),
    QUANTITY("quantity", "p.quantity");

    private final String apiName;
    private final String column;

    ProductSort(String apiName, String column) {
        this.apiName = apiName;
        this.column = column;
    }

    /** A validated sort: the SQL text is built only from constants. */
    public record Order(ProductSort field, boolean descending) {
        public String sql() {
            return field.column + (descending ? " DESC" : " ASC") + ", p.product_id" + (descending ? " DESC" : " ASC");
        }
    }

    /** {@code "name"}, {@code "name,asc"}, {@code "quantity,desc"}; anything else is a VALIDATION_ERROR on "sort". */
    public static Order parse(String value) {
        if (value == null || value.isBlank()) {
            return new Order(NAME, false);
        }
        String[] parts = value.trim().split(",", -1);
        if (parts.length > 2) {
            throw invalid();
        }
        ProductSort field = Arrays.stream(values()).filter(s -> s.apiName.equals(parts[0].trim())).findFirst()
                .orElseThrow(ProductSort::invalid);
        boolean descending = false;
        if (parts.length == 2) {
            String direction = parts[1].trim().toLowerCase(Locale.ROOT);
            if (!direction.equals("asc") && !direction.equals("desc")) {
                throw invalid();
            }
            descending = direction.equals("desc");
        }
        return new Order(field, descending);
    }

    private static FieldValidationException invalid() {
        String allowed = Arrays.stream(values()).map(s -> s.apiName).collect(Collectors.joining(", "));
        return new FieldValidationException("sort", "ترتيب غير مدعوم. المسموح: " + allowed + " (مع ,asc أو ,desc).");
    }
}
