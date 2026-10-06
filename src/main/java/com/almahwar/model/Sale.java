package com.almahwar.model;

import com.almahwar.util.MoneyUtil;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Table: Sales (فاتورة مبيعات) with its items.
 * <p>
 * Totals are always derived from the items: subtotal = Σ line totals, total = subtotal − invoice discount,
 * remaining = total − paid (see {@link #recalculate()}). Profit = total − Σ quantity × historical unit cost,
 * so both line and invoice discounts reduce it.
 */
public class Sale {

    private Integer saleId;
    /** {@code Sales.invoice_no}, e.g. {@code SAL-000123}. */
    private String saleNo;
    private LocalDateTime saleDate;
    private Integer customerId;
    private Integer userId;
    private SaleType saleType = SaleType.RETAIL;
    private PaymentMethod paymentMethod = PaymentMethod.CASH;
    private BigDecimal subtotal = MoneyUtil.ZERO;
    private BigDecimal discountAmount = MoneyUtil.ZERO;
    private BigDecimal totalAmount = MoneyUtil.ZERO;
    private BigDecimal paidAmount = MoneyUtil.ZERO;
    /** Σ quantity × historical unit cost, fixed at posting; {@code null} when hidden. */
    private BigDecimal costTotal = MoneyUtil.ZERO;
    /** {@code Sales.gross_profit} of a posted sale; {@code null} for drafts or when hidden. */
    private BigDecimal grossProfit;
    private SaleStatus status = SaleStatus.DRAFT;
    private String notes;
    /** Identifies one POS sale; a retry with the same id cannot create a second invoice. */
    private UUID requestId;
    private LocalDateTime postedAt;
    private Integer postedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<SaleItem> items = new ArrayList<>();

    // Read-only, joined for display
    private String customerCode;
    private String customerName;
    private String customerPhone;
    private String userName;
    private String postedByName;
    /** The quotation this sale was made from, if any ({@code Sales.quotation_id}). */
    private Integer quotationId;
    private String quotationNo;

    public Integer getSaleId() { return saleId; }
    public void setSaleId(Integer saleId) { this.saleId = saleId; }

    public String getSaleNo() { return saleNo; }
    public void setSaleNo(String saleNo) { this.saleNo = saleNo; }

    public LocalDateTime getSaleDate() { return saleDate; }
    public void setSaleDate(LocalDateTime saleDate) { this.saleDate = saleDate; }

    public Integer getCustomerId() { return customerId; }
    public void setCustomerId(Integer customerId) { this.customerId = customerId; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public SaleType getSaleType() { return saleType; }
    public void setSaleType(SaleType saleType) { this.saleType = saleType; }

    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }

    public BigDecimal getCostTotal() { return costTotal; }
    public void setCostTotal(BigDecimal costTotal) { this.costTotal = costTotal; }

    public BigDecimal getRemainingAmount() {
        return totalAmount == null || paidAmount == null ? null : MoneyUtil.of(totalAmount.subtract(paidAmount));
    }

    /** Total − historical cost total (both discounts reduce it); {@code null} for drafts or when hidden. */
    public BigDecimal getGrossProfit() { return grossProfit; }
    public void setGrossProfit(BigDecimal grossProfit) { this.grossProfit = grossProfit; }

    public PaymentStatus getPaymentStatus() {
        return totalAmount == null || paidAmount == null ? null : PaymentStatus.of(totalAmount, paidAmount);
    }

    public PaymentType getPaymentType() {
        return totalAmount == null || paidAmount == null ? null : PaymentType.of(paymentMethod, totalAmount, paidAmount);
    }

    public SaleStatus getStatus() { return status; }
    public void setStatus(SaleStatus status) { this.status = status; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }

    public LocalDateTime getPostedAt() { return postedAt; }
    public void setPostedAt(LocalDateTime postedAt) { this.postedAt = postedAt; }

    public Integer getPostedBy() { return postedBy; }
    public void setPostedBy(Integer postedBy) { this.postedBy = postedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<SaleItem> getItems() { return items; }
    public void setItems(List<SaleItem> items) { this.items = items == null ? new ArrayList<>() : items; }

    public String getCustomerCode() { return customerCode; }
    public void setCustomerCode(String customerCode) { this.customerCode = customerCode; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getPostedByName() { return postedByName; }
    public void setPostedByName(String postedByName) { this.postedByName = postedByName; }

    public Integer getQuotationId() { return quotationId; }
    public void setQuotationId(Integer quotationId) { this.quotationId = quotationId; }

    public String getQuotationNo() { return quotationNo; }
    public void setQuotationNo(String quotationNo) { this.quotationNo = quotationNo; }

    /** The walk-in (cash) customer: sales to it must be paid in full. */
    public boolean isWalkIn() {
        return Customer.CASH_CUSTOMER_CODE.equals(customerCode);
    }

    /** Sets subtotal and total from the items and the invoice discount (paid amount is not changed). */
    public Sale recalculate() {
        BigDecimal sum = BigDecimal.ZERO;
        for (SaleItem i : items) {
            BigDecimal line = i.getLineTotal();
            sum = sum.add(line == null ? BigDecimal.ZERO : line);
        }
        subtotal = MoneyUtil.of(sum);
        BigDecimal discount = discountAmount == null ? BigDecimal.ZERO : discountAmount;
        totalAmount = MoneyUtil.of(subtotal.subtract(discount));
        return this;
    }

    /** Σ quantity × unit cost of the items (each line rounded to 3 decimals, like the stored cost total). */
    public BigDecimal itemsCostTotal() {
        BigDecimal sum = BigDecimal.ZERO;
        for (SaleItem i : items) {
            BigDecimal c = i.getCostTotal();
            sum = sum.add(c == null ? BigDecimal.ZERO : c);
        }
        return MoneyUtil.of(sum);
    }

    public boolean isDraft() {
        return status == SaleStatus.DRAFT;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Sale other && saleId != null && saleId.equals(other.saleId));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(saleId);
    }

    @Override
    public String toString() {
        return saleNo;
    }
}
