package com.almahwar.model;

import com.almahwar.util.MoneyUtil;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Table: Purchases (فاتورة مشتريات) with its items.
 * <p>
 * Totals are always derived from the items: subtotal = Σ line totals,
 * total = subtotal − discount, remaining = total − paid (see {@link #recalculate()}).
 */
public class Purchase {

    private Integer purchaseId;
    private String purchaseNo;
    private String supplierInvoiceNo;
    private LocalDateTime purchaseDate;
    private Integer supplierId;
    private Integer userId;
    private PaymentMethod paymentMethod = PaymentMethod.CASH;
    private BigDecimal subtotal = MoneyUtil.ZERO;
    private BigDecimal discountAmount = MoneyUtil.ZERO;
    private BigDecimal totalAmount = MoneyUtil.ZERO;
    private BigDecimal paidAmount = MoneyUtil.ZERO;
    private PurchaseStatus status = PurchaseStatus.DRAFT;
    private String notes;
    /** Identifies one "save" request; a retry with the same id cannot create a second invoice. */
    private UUID requestId;
    private LocalDateTime postedAt;
    private Integer postedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<PurchaseItem> items = new ArrayList<>();

    // Read-only, joined for display
    private String supplierCode;
    private String supplierName;
    private String userName;
    private String postedByName;

    public Integer getPurchaseId() { return purchaseId; }
    public void setPurchaseId(Integer purchaseId) { this.purchaseId = purchaseId; }

    public String getPurchaseNo() { return purchaseNo; }
    public void setPurchaseNo(String purchaseNo) { this.purchaseNo = purchaseNo; }

    public String getSupplierInvoiceNo() { return supplierInvoiceNo; }
    public void setSupplierInvoiceNo(String supplierInvoiceNo) { this.supplierInvoiceNo = supplierInvoiceNo; }

    public LocalDateTime getPurchaseDate() { return purchaseDate; }
    public void setPurchaseDate(LocalDateTime purchaseDate) { this.purchaseDate = purchaseDate; }

    public Integer getSupplierId() { return supplierId; }
    public void setSupplierId(Integer supplierId) { this.supplierId = supplierId; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }

    /** Amounts are {@code null} when hidden from a user without the purchase-cost permission. */
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }

    public BigDecimal getRemainingAmount() {
        return totalAmount == null || paidAmount == null ? null : MoneyUtil.of(totalAmount.subtract(paidAmount));
    }

    public PaymentStatus getPaymentStatus() {
        return totalAmount == null || paidAmount == null ? null : PaymentStatus.of(totalAmount, paidAmount);
    }

    public PaymentType getPaymentType() {
        return totalAmount == null || paidAmount == null ? null : PaymentType.of(paymentMethod, totalAmount, paidAmount);
    }

    public PurchaseStatus getStatus() { return status; }
    public void setStatus(PurchaseStatus status) { this.status = status; }

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

    public List<PurchaseItem> getItems() { return items; }
    public void setItems(List<PurchaseItem> items) { this.items = items == null ? new ArrayList<>() : items; }

    public String getSupplierCode() { return supplierCode; }
    public void setSupplierCode(String supplierCode) { this.supplierCode = supplierCode; }

    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getPostedByName() { return postedByName; }
    public void setPostedByName(String postedByName) { this.postedByName = postedByName; }

    /** Sets subtotal and total from the items and the invoice discount (paid amount is not changed). */
    public Purchase recalculate() {
        BigDecimal sum = BigDecimal.ZERO;
        for (PurchaseItem i : items) {
            BigDecimal line = i.getLineTotal();
            sum = sum.add(line == null ? BigDecimal.ZERO : line);
        }
        subtotal = MoneyUtil.of(sum);
        BigDecimal discount = discountAmount == null ? BigDecimal.ZERO : discountAmount;
        totalAmount = MoneyUtil.of(subtotal.subtract(discount));
        return this;
    }

    public boolean isDraft() {
        return status == PurchaseStatus.DRAFT;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Purchase other && purchaseId != null && purchaseId.equals(other.purchaseId));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(purchaseId);
    }

    @Override
    public String toString() {
        return purchaseNo;
    }
}
