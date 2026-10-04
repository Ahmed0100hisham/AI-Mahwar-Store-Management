package com.almahwar.model;

import com.almahwar.util.MoneyUtil;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/** Table: Customers. {@code balance} is the amount the customer owes the company. */
public class Customer {

    /** customer_code of the default walk-in customer seeded by the SQL script. */
    public static final String CASH_CUSTOMER_CODE = "CASH";

    private Integer customerId;
    private String customerCode;
    private String name;
    private CustomerType customerType = CustomerType.RETAIL;
    private String phone;
    private String phone2;
    private String email;
    private String area;
    private String address;
    private BigDecimal creditLimit = MoneyUtil.ZERO;
    private BigDecimal openingBalance = MoneyUtil.ZERO;
    private BigDecimal balance = MoneyUtil.ZERO;
    private String notes;
    private boolean active = true;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Integer getCustomerId() { return customerId; }
    public void setCustomerId(Integer customerId) { this.customerId = customerId; }

    public String getCustomerCode() { return customerCode; }
    public void setCustomerCode(String customerCode) { this.customerCode = customerCode; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public CustomerType getCustomerType() { return customerType; }
    public void setCustomerType(CustomerType customerType) {
        this.customerType = customerType == null ? CustomerType.RETAIL : customerType;
    }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getPhone2() { return phone2; }
    public void setPhone2(String phone2) { this.phone2 = phone2; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public BigDecimal getCreditLimit() { return creditLimit; }
    public void setCreditLimit(BigDecimal creditLimit) { this.creditLimit = MoneyUtil.of(creditLimit); }

    public BigDecimal getOpeningBalance() { return openingBalance; }
    public void setOpeningBalance(BigDecimal openingBalance) { this.openingBalance = MoneyUtil.of(openingBalance); }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = MoneyUtil.of(balance); }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public boolean isCashCustomer() {
        return CASH_CUSTOMER_CODE.equals(customerCode);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Customer other && customerId != null && customerId.equals(other.customerId));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(customerId);
    }

    @Override
    public String toString() {
        return name;
    }
}
