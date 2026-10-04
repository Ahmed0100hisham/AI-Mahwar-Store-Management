package com.almahwar.model;

import java.time.LocalDateTime;
import java.util.Objects;

/** Table: Units (حبة، متر، لفة، طقم ...) */
public class Unit {

    private Integer unitId;
    private String nameAr;
    private String nameEn;
    private String symbol;
    /** {@code true} for units sold in fractions, such as metre or kilogram. */
    private boolean allowsDecimal;
    private boolean active = true;
    private LocalDateTime createdAt;

    public Integer getUnitId() { return unitId; }
    public void setUnitId(Integer unitId) { this.unitId = unitId; }

    public String getNameAr() { return nameAr; }
    public void setNameAr(String nameAr) { this.nameAr = nameAr; }

    public String getNameEn() { return nameEn; }
    public void setNameEn(String nameEn) { this.nameEn = nameEn; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public boolean isAllowsDecimal() { return allowsDecimal; }
    public void setAllowsDecimal(boolean allowsDecimal) { this.allowsDecimal = allowsDecimal; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Unit other && unitId != null && unitId.equals(other.unitId));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(unitId);
    }

    @Override
    public String toString() {
        return nameAr;
    }
}
