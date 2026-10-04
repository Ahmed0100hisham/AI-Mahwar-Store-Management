package com.almahwar.model;

import java.time.LocalDateTime;
import java.util.Objects;

/** Table: Brands */
public class Brand {

    private Integer brandId;
    private String nameAr;
    private String nameEn;
    private String country;
    private boolean active = true;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Integer getBrandId() { return brandId; }
    public void setBrandId(Integer brandId) { this.brandId = brandId; }

    public String getNameAr() { return nameAr; }
    public void setNameAr(String nameAr) { this.nameAr = nameAr; }

    public String getNameEn() { return nameEn; }
    public void setNameEn(String nameEn) { this.nameEn = nameEn; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Brand other && brandId != null && brandId.equals(other.brandId));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(brandId);
    }

    @Override
    public String toString() {
        return nameAr;
    }
}
