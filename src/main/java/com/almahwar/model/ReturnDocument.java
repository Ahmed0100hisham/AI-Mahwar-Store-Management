package com.almahwar.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A sales return ({@code Sale_Returns}) or a purchase return ({@code Purchase_Returns}) with its lines. Posted at
 * once and never edited or deleted.
 * <ul>
 *   <li>{@code totalAmount}: value of the returned goods, at the net prices of the original document;</li>
 *   <li>{@code refundAmount}: money actually paid back (sale) / received back (purchase); the rest of the value
 *       only reduces the customer's / supplier's balance;</li>
 *   <li>{@code costTotal}: sale returns only — the historical cost of the goods that went back into stock.</li>
 * </ul>
 */
public class ReturnDocument {

    private ReturnKind kind;
    private Integer returnId;
    private String returnNo;
    private Integer originalId;
    private String originalNo;
    private Integer partyId;
    private String partyCode;
    private String partyName;
    private LocalDateTime returnDate;
    private BigDecimal totalAmount;
    private BigDecimal costTotal;
    private BigDecimal refundAmount;
    /** Method of the money moved; {@code CREDIT} when nothing was paid back (only the balance changed). */
    private PaymentMethod refundMethod = PaymentMethod.CASH;
    private ReturnReason reason;
    private String notes;
    private UUID requestId;
    private Integer userId;
    private String userName;
    private LocalDateTime createdAt;
    private List<ReturnLine> lines = new ArrayList<>();

    /** Filled when it is saved: the party's balance right after the return ({@code null} for walk-in). */
    private BigDecimal balanceAfter;

    public ReturnKind getKind() { return kind; }
    public void setKind(ReturnKind kind) { this.kind = kind; }

    public Integer getReturnId() { return returnId; }
    public void setReturnId(Integer returnId) { this.returnId = returnId; }

    public String getReturnNo() { return returnNo; }
    public void setReturnNo(String returnNo) { this.returnNo = returnNo; }

    public Integer getOriginalId() { return originalId; }
    public void setOriginalId(Integer originalId) { this.originalId = originalId; }

    public String getOriginalNo() { return originalNo; }
    public void setOriginalNo(String originalNo) { this.originalNo = originalNo; }

    public Integer getPartyId() { return partyId; }
    public void setPartyId(Integer partyId) { this.partyId = partyId; }

    public String getPartyCode() { return partyCode; }
    public void setPartyCode(String partyCode) { this.partyCode = partyCode; }

    public String getPartyName() { return partyName; }
    public void setPartyName(String partyName) { this.partyName = partyName; }

    public LocalDateTime getReturnDate() { return returnDate; }
    public void setReturnDate(LocalDateTime returnDate) { this.returnDate = returnDate; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public BigDecimal getCostTotal() { return costTotal; }
    public void setCostTotal(BigDecimal costTotal) { this.costTotal = costTotal; }

    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal refundAmount) { this.refundAmount = refundAmount; }

    /** The part of the value that only changed the party's balance. */
    public BigDecimal getAccountAmount() {
        return totalAmount == null || refundAmount == null ? null : totalAmount.subtract(refundAmount);
    }

    public PaymentMethod getRefundMethod() { return refundMethod; }
    public void setRefundMethod(PaymentMethod refundMethod) { this.refundMethod = refundMethod; }

    public ReturnReason getReason() { return reason; }
    public void setReason(ReturnReason reason) { this.reason = reason; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public List<ReturnLine> getLines() { return lines; }
    public void setLines(List<ReturnLine> lines) { this.lines = lines == null ? new ArrayList<>() : lines; }

    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }

    public boolean isWalkIn() {
        return kind == ReturnKind.SALE && Customer.CASH_CUSTOMER_CODE.equals(partyCode);
    }

    @Override
    public String toString() {
        return returnNo;
    }
}
