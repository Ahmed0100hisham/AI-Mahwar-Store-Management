package com.almahwar.model;

import com.almahwar.util.MoneyUtil;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/** Table: Suppliers. {@code balance} is the amount the company owes the supplier. */
public class Supplier {

    private Integer supplierId;
    private String supplierCode;
    private String name;
    private String contactPerson;
    private String phone;
    private String phone2;
    private String email;
    private String country;
    private String address;
    private BigDecimal openingBalance = MoneyUtil.ZERO;
    private BigDecimal balance = MoneyUtil.ZERO;
    private String notes;
    private boolean active = true;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Integer getSupplierId() { return supplierId; }
    public void setSupplierId(Integer supplierId) { this.supplierId = supplierId; }

    public String getSupplierCode() { return supplierCode; }
    public void setSupplierCode(String supplierCode) { this.supplierCode = supplierCode; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getContactPerson() { return contactPerson; }
    public void setContactPerson(String contactPerson) { this.contactPerson = contactPerson; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getPhone2() { return phone2; }
    public void setPhone2(String phone2) { this.phone2 = phone2; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

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

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Supplier other && supplierId != null && supplierId.equals(other.supplierId));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(supplierId);
    }

    @Override
    public String toString() {
        return name;
    }
}
