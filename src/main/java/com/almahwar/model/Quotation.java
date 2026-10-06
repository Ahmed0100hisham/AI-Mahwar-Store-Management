package com.almahwar.model;

import com.almahwar.util.MoneyUtil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Table: Quotations (عرض سعر) with its items. A quotation is <b>financially neutral</b>: creating, editing, sending
 * or accepting it never touches stock, customer accounts, cash or profit. Only the sale made from it does, when that
 * sale is posted through the normal sales flow.
 * <p>
 * Totals follow the sales conventions: subtotal = Σ line totals, total = subtotal − quotation discount.
 */
public class Quotation {

    private Integer quotationId;
    private String quotationNo;
    private LocalDateTime quotationDate;
    private LocalDate validUntil;
    private Integer customerId;
    /** For the walk-in customer: the prospect's name / phone written on the quotation (optional). */
    private String prospectName;
    private String prospectPhone;
    private SaleType priceType = SaleType.RETAIL;
    private BigDecimal subtotal = MoneyUtil.ZERO;
    private BigDecimal discountAmount = MoneyUtil.ZERO;
    private BigDecimal totalAmount = MoneyUtil.ZERO;
    private QuotationStatus status = QuotationStatus.DRAFT;
    private Integer convertedSaleId;
    private String notes;
    private String terms;
    private String statusNote;
    private LocalDateTime sentAt;
    private LocalDateTime decidedAt;
    private Integer decidedBy;
    private UUID requestId;
    private Integer userId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<QuotationItem> items = new ArrayList<>();

    // Read-only, joined for display
    private String customerCode;
    private String customerName;
    private String customerPhone;
    private String userName;
    private String decidedByName;
    private String convertedSaleNo;
    private SaleStatus convertedSaleStatus;

    public Integer getQuotationId() { return quotationId; }
    public void setQuotationId(Integer quotationId) { this.quotationId = quotationId; }

    public String getQuotationNo() { return quotationNo; }
    public void setQuotationNo(String quotationNo) { this.quotationNo = quotationNo; }

    public LocalDateTime getQuotationDate() { return quotationDate; }
    public void setQuotationDate(LocalDateTime quotationDate) { this.quotationDate = quotationDate; }

    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate validUntil) { this.validUntil = validUntil; }

    public Integer getCustomerId() { return customerId; }
    public void setCustomerId(Integer customerId) { this.customerId = customerId; }

    public String getProspectName() { return prospectName; }
    public void setProspectName(String prospectName) { this.prospectName = prospectName; }

    public String getProspectPhone() { return prospectPhone; }
    public void setProspectPhone(String prospectPhone) { this.prospectPhone = prospectPhone; }

    public SaleType getPriceType() { return priceType; }
    public void setPriceType(SaleType priceType) { this.priceType = priceType; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public QuotationStatus getStatus() { return status; }
    public void setStatus(QuotationStatus status) { this.status = status; }

    public Integer getConvertedSaleId() { return convertedSaleId; }
    public void setConvertedSaleId(Integer convertedSaleId) { this.convertedSaleId = convertedSaleId; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getTerms() { return terms; }
    public void setTerms(String terms) { this.terms = terms; }

    public String getStatusNote() { return statusNote; }
    public void setStatusNote(String statusNote) { this.statusNote = statusNote; }

    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }

    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }

    public Integer getDecidedBy() { return decidedBy; }
    public void setDecidedBy(Integer decidedBy) { this.decidedBy = decidedBy; }

    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<QuotationItem> getItems() { return items; }
    public void setItems(List<QuotationItem> items) { this.items = items == null ? new ArrayList<>() : items; }

    public String getCustomerCode() { return customerCode; }
    public void setCustomerCode(String customerCode) { this.customerCode = customerCode; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public String getDecidedByName() { return decidedByName; }
    public void setDecidedByName(String decidedByName) { this.decidedByName = decidedByName; }

    public String getConvertedSaleNo() { return convertedSaleNo; }
    public void setConvertedSaleNo(String convertedSaleNo) { this.convertedSaleNo = convertedSaleNo; }

    public SaleStatus getConvertedSaleStatus() { return convertedSaleStatus; }
    public void setConvertedSaleStatus(SaleStatus convertedSaleStatus) { this.convertedSaleStatus = convertedSaleStatus; }

    public boolean isWalkIn() {
        return Customer.CASH_CUSTOMER_CODE.equals(customerCode);
    }

    /** Name shown / printed: the prospect's name for a walk-in quotation, else the customer's name. */
    public String getDisplayName() {
        return isWalkIn() && prospectName != null && !prospectName.isBlank() ? prospectName : customerName;
    }

    /** Phone shown / printed (prospect's for walk-in). */
    public String getDisplayPhone() {
        return isWalkIn() && prospectPhone != null && !prospectPhone.isBlank() ? prospectPhone : customerPhone;
    }

    /** Past its validity date ({@code today} is the reference day). */
    public boolean isPastValidity(LocalDate today) {
        return validUntil != null && validUntil.isBefore(today);
    }

    /** Sets subtotal and total from the items and the quotation discount. */
    public Quotation recalculate() {
        BigDecimal sum = BigDecimal.ZERO;
        for (QuotationItem i : items) {
            BigDecimal line = i.getLineTotal();
            sum = sum.add(line == null ? BigDecimal.ZERO : line);
        }
        subtotal = MoneyUtil.of(sum);
        BigDecimal discount = discountAmount == null ? BigDecimal.ZERO : discountAmount;
        totalAmount = MoneyUtil.of(subtotal.subtract(discount));
        return this;
    }

    @Override
    public String toString() {
        return quotationNo;
    }
}
