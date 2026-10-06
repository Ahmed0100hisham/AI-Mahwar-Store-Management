package com.almahwar.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * One row of {@code Cash_Transactions} as shown in the cashbox, or a manual deposit / withdrawal to record.
 * The cashbox balance is always Σ IN − Σ OUT of these rows; it is never stored.
 */
public class CashMovement {

    /** IN = money received, OUT = money paid. */
    public enum Direction {
        IN("وارد"),
        OUT("صادر");

        private final String labelAr;

        Direction(String labelAr) {
            this.labelAr = labelAr;
        }

        public String getLabelAr() {
            return labelAr;
        }
    }

    private Long transactionId;
    private LocalDateTime transactionDate;
    private Direction direction;
    private CashSource source;
    private Integer sourceId;
    private BigDecimal amount;
    private PaymentMethod paymentMethod = PaymentMethod.CASH;
    /** Number of the source document (invoice, receipt, expense ...), joined for display. */
    private String documentNo;
    /** For manual operations: the reason. */
    private String description;
    private String referenceNo;
    private String notes;
    private UUID requestId;
    private Integer userId;
    private String userName;

    public Long getTransactionId() { return transactionId; }
    public void setTransactionId(Long transactionId) { this.transactionId = transactionId; }

    /** e.g. {@code TRX-000123}. */
    public String getTransactionNo() {
        return transactionId == null ? null : String.format(Locale.ROOT, "TRX-%06d", transactionId);
    }

    public LocalDateTime getTransactionDate() { return transactionDate; }
    public void setTransactionDate(LocalDateTime transactionDate) { this.transactionDate = transactionDate; }

    public Direction getDirection() { return direction; }
    public void setDirection(Direction direction) { this.direction = direction; }

    public CashSource getSource() { return source; }
    public void setSource(CashSource source) { this.source = source; }

    public Integer getSourceId() { return sourceId; }
    public void setSourceId(Integer sourceId) { this.sourceId = sourceId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    /** Signed effect on the balance: + for IN, − for OUT. */
    public BigDecimal getSignedAmount() {
        return amount == null || direction == null ? null : direction == Direction.IN ? amount : amount.negate();
    }

    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getDocumentNo() { return documentNo; }
    public void setDocumentNo(String documentNo) { this.documentNo = documentNo; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

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
}
