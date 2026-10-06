package com.almahwar.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A payment received from a customer ({@code Customer_Payments}, سند قبض) or paid to a supplier
 * ({@code Supplier_Payments}, سند صرف). The party's balance is never stored here: it changes only through the
 * {@code Account_Ledger} PAYMENT entry written in the same transaction.
 */
public class PartyPayment {

    private Integer paymentId;
    private String paymentNo;
    private PartyType partyType;
    private Integer partyId;
    /** The day chosen on screen (today, or an earlier day); {@code null} = today. */
    private LocalDate paymentDay;
    /** The stored date and time (server clock; 12:00 for an earlier day). */
    private LocalDateTime paymentDate;
    private BigDecimal amount;
    private PaymentMethod paymentMethod = PaymentMethod.CASH;
    private String referenceNo;
    private String notes;
    private UUID requestId;
    private Integer userId;
    private LocalDateTime createdAt;

    // Read-only, joined for display / filled after saving
    private String partyCode;
    private String partyName;
    private String userName;
    /** The party's balance right after this payment (set when it is saved). */
    private BigDecimal balanceAfter;

    public Integer getPaymentId() { return paymentId; }
    public void setPaymentId(Integer paymentId) { this.paymentId = paymentId; }

    public String getPaymentNo() { return paymentNo; }
    public void setPaymentNo(String paymentNo) { this.paymentNo = paymentNo; }

    public PartyType getPartyType() { return partyType; }
    public void setPartyType(PartyType partyType) { this.partyType = partyType; }

    public Integer getPartyId() { return partyId; }
    public void setPartyId(Integer partyId) { this.partyId = partyId; }

    public LocalDate getPaymentDay() { return paymentDay; }
    public void setPaymentDay(LocalDate paymentDay) { this.paymentDay = paymentDay; }

    public LocalDateTime getPaymentDate() { return paymentDate; }
    public void setPaymentDate(LocalDateTime paymentDate) { this.paymentDate = paymentDate; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getReferenceNo() { return referenceNo; }
    public void setReferenceNo(String referenceNo) { this.referenceNo = referenceNo; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID requestId) { this.requestId = requestId; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getPartyCode() { return partyCode; }
    public void setPartyCode(String partyCode) { this.partyCode = partyCode; }

    public String getPartyName() { return partyName; }
    public void setPartyName(String partyName) { this.partyName = partyName; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }

    @Override
    public String toString() {
        return paymentNo;
    }
}
