package com.almahwar.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** Table: Expenses (مصروف). Recorded together with its Cash_Transactions OUT row, never in a party ledger. */
public class Expense {

    private Integer expenseId;
    private String expenseNo;
    /** The day chosen on screen (today, or an earlier day); {@code null} = today. */
    private LocalDate expenseDay;
    private LocalDateTime expenseDate;
    private ExpenseCategory category = ExpenseCategory.OTHER;
    private String description;
    private BigDecimal amount;
    private PaymentMethod paymentMethod = PaymentMethod.CASH;
    private String referenceNo;
    private String notes;
    private UUID requestId;
    /** created_by ({@code Expenses.user_id}). */
    private Integer userId;
    private String userName;
    private LocalDateTime createdAt;

    public Integer getExpenseId() { return expenseId; }
    public void setExpenseId(Integer expenseId) { this.expenseId = expenseId; }

    public String getExpenseNo() { return expenseNo; }
    public void setExpenseNo(String expenseNo) { this.expenseNo = expenseNo; }

    public LocalDate getExpenseDay() { return expenseDay; }
    public void setExpenseDay(LocalDate expenseDay) { this.expenseDay = expenseDay; }

    public LocalDateTime getExpenseDate() { return expenseDate; }
    public void setExpenseDate(LocalDateTime expenseDate) { this.expenseDate = expenseDate; }

    public ExpenseCategory getCategory() { return category; }
    public void setCategory(ExpenseCategory category) { this.category = category; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

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

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return expenseNo;
    }
}
