package com.almahwar.model;

/**
 * Search criteria for the product list. {@code null} fields are not filtered.
 *
 * @param text         matched against Arabic/English name, product code and barcode
 * @param status       active / inactive / all
 * @param lowStockOnly only products with {@code quantity <= minimum_stock}
 */
public record ProductFilter(String text, Integer categoryId, Integer brandId, Integer unitId,
                            ActiveStatus status, boolean lowStockOnly) {

    public enum ActiveStatus {
        ALL("الكل"), ACTIVE("نشط"), INACTIVE("غير نشط");

        private final String labelAr;

        ActiveStatus(String labelAr) {
            this.labelAr = labelAr;
        }

        public String getLabelAr() {
            return labelAr;
        }
    }

    public ProductFilter {
        status = status == null ? ActiveStatus.ALL : status;
        text = text == null || text.isBlank() ? null : text.trim();
    }

    public static ProductFilter all() {
        return new ProductFilter(null, null, null, null, ActiveStatus.ALL, false);
    }

    public static ProductFilter search(String text) {
        return new ProductFilter(text, null, null, null, ActiveStatus.ALL, false);
    }

    public ProductFilter withStatus(ActiveStatus newStatus) {
        return new ProductFilter(text, categoryId, brandId, unitId, newStatus, lowStockOnly);
    }

    public ProductFilter withLowStockOnly(boolean lowOnly) {
        return new ProductFilter(text, categoryId, brandId, unitId, status, lowOnly);
    }
}
