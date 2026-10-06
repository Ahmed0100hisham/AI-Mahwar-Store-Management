package com.almahwar.model;

import com.almahwar.util.MoneyUtil;
import com.almahwar.util.QuantityUtil;

import java.math.BigDecimal;

/** Table: Quotation_Items. The agreed unit price; nothing is reserved in stock. */
public class QuotationItem {

    private Integer quotationItemId;
    private Integer quotationId;
    private Integer productId;
    private BigDecimal quantity = QuantityUtil.ZERO;
    private BigDecimal unitPrice = MoneyUtil.ZERO;
    /** Discount on the whole line, in KWD. */
    private BigDecimal discountAmount = MoneyUtil.ZERO;

    // Read-only, joined for display
    private String productCode;
    private String productName;
    private String barcode;
    private String unitName;
    private boolean unitAllowsDecimal;
    /** The product's stock now (information only; a quotation reserves nothing). */
    private BigDecimal available;

    public Integer getQuotationItemId() { return quotationItemId; }
    public void setQuotationItemId(Integer quotationItemId) { this.quotationItemId = quotationItemId; }

    public Integer getQuotationId() { return quotationId; }
    public void setQuotationId(Integer quotationId) { this.quotationId = quotationId; }

    public Integer getProductId() { return productId; }
    public void setProductId(Integer productId) { this.productId = productId; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }

    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getBarcode() { return barcode; }
    public void setBarcode(String barcode) { this.barcode = barcode; }

    public String getUnitName() { return unitName; }
    public void setUnitName(String unitName) { this.unitName = unitName; }

    public boolean isUnitAllowsDecimal() { return unitAllowsDecimal; }
    public void setUnitAllowsDecimal(boolean unitAllowsDecimal) { this.unitAllowsDecimal = unitAllowsDecimal; }

    public BigDecimal getAvailable() { return available; }
    public void setAvailable(BigDecimal available) { this.available = available; }

    /** {@code quantity × unit price − line discount}, rounded like the persisted {@code line_total}. */
    public BigDecimal getLineTotal() {
        if (quantity == null || unitPrice == null) {
            return null;
        }
        BigDecimal discount = discountAmount == null ? BigDecimal.ZERO : discountAmount;
        return MoneyUtil.of(quantity.multiply(unitPrice).subtract(discount));
    }

    /** The same line as a sale line (shared validation and conversion to a sale draft). */
    public SaleItem toSaleItem() {
        SaleItem i = new SaleItem();
        i.setProductId(productId);
        i.setProductCode(productCode);
        i.setProductName(productName);
        i.setUnitName(unitName);
        i.setQuantity(quantity);
        i.setUnitPrice(unitPrice);
        i.setDiscountAmount(discountAmount);
        return i;
    }

    public QuotationItem copy() {
        QuotationItem i = new QuotationItem();
        i.quotationItemId = quotationItemId;
        i.quotationId = quotationId;
        i.productId = productId;
        i.quantity = quantity;
        i.unitPrice = unitPrice;
        i.discountAmount = discountAmount;
        i.productCode = productCode;
        i.productName = productName;
        i.barcode = barcode;
        i.unitName = unitName;
        i.unitAllowsDecimal = unitAllowsDecimal;
        i.available = available;
        return i;
    }
}
