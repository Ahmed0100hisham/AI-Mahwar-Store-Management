package com.almahwar.model;

import com.almahwar.util.MoneyUtil;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Table: Account_Ledger. One movement on a customer's or supplier's account.
 * Exactly one of {@code debit} / {@code credit} is positive.
 */
public class LedgerEntry {

    private Long entryId;
    private PartyType partyType;
    private Integer partyId;
    private LocalDateTime entryDate;
    private LedgerEntryType entryType;
    private BigDecimal debit = MoneyUtil.ZERO;
    private BigDecimal credit = MoneyUtil.ZERO;
    private String referenceType;
    private Integer referenceId;
    private String referenceNo;
    private String description;
    private Integer userId;
    private LocalDateTime createdAt;

    // Read-only, for statements
    private String userName;
    /** Balance after this entry, from the party's point of view (see {@link PartyType}). */
    private BigDecimal runningBalance = MoneyUtil.ZERO;

    public Long getEntryId() { return entryId; }
    public void setEntryId(Long entryId) { this.entryId = entryId; }

    public PartyType getPartyType() { return partyType; }
    public void setPartyType(PartyType partyType) { this.partyType = partyType; }

    public Integer getPartyId() { return partyId; }
    public void setPartyId(Integer partyId) { this.partyId = partyId; }

    public LocalDateTime getEntryDate() { return entryDate; }
    public void setEntryDate(LocalDateTime entryDate) { this.entryDate = entryDate; }

    public LedgerEntryType getEntryType() { return entryType; }
    public void setEntryType(LedgerEntryType entryType) { this.entryType = entryType; }

    public BigDecimal getDebit() { return debit; }
    public void setDebit(BigDecimal debit) { this.debit = MoneyUtil.of(debit); }

    public BigDecimal getCredit() { return credit; }
    public void setCredit(BigDecimal credit) { this.credit = MoneyUtil.of(credit); }

    public String getReferenceType() { return referenceType; }
    public void setReferenceType(String referenceType) { this.referenceType = referenceType; }

    public Integer getReferenceId() { return referenceId; }
    public void setReferenceId(Integer referenceId) { this.referenceId = referenceId; }

    public String getReferenceNo() { return referenceNo; }
    public void setReferenceNo(String referenceNo) { this.referenceNo = referenceNo; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public BigDecimal getRunningBalance() { return runningBalance; }
    public void setRunningBalance(BigDecimal runningBalance) { this.runningBalance = MoneyUtil.of(runningBalance); }

    /** Effect of this entry on the party's balance. */
    public BigDecimal balanceEffect() {
        return partyType.balanceEffect(debit, credit);
    }
}
