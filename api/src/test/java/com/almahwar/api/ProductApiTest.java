package com.almahwar.api;

import com.almahwar.api.product.ProductRepository.ProductRow;
import com.almahwar.api.product.ProductSort;
import com.almahwar.api.support.ApiWebTestBase;
import com.almahwar.api.web.PageQuery;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.UncategorizedSQLException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The proof-of-concept endpoint: authorization, cost visibility, paging validation, safe database errors. */
class ProductApiTest extends ApiWebTestBase {

    private static ProductRow product(BigDecimal cost) {
        return new ProductRow(10, "P-010", "6281000000010", "خلاط مغسلة", "Basin mixer", "خلاطات", "Grohe", "قطعة",
                null, "كروم", new BigDecimal("12.500"), new BigDecimal("11.000"), cost, new BigDecimal("7.000"),
                new BigDecimal("2.000"), true);
    }

    private void stubOneProduct(boolean withCost) {
        when(productRepository.count(any(), anyBoolean())).thenReturn(1L);
        when(productRepository.findPage(any(), anyBoolean(), eq(withCost), any(), any()))
                .thenReturn(List.of(product(withCost ? new BigDecimal("8.250") : null)));
    }

    @Test
    void cashierSeesProductsWithoutCost() throws Exception {
        stubOneProduct(false);
        String body = mvc.perform(get("/api/v1/products").header("Authorization", bearer(CASHIER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].code").value("P-010"))
                .andExpect(jsonPath("$.items[0].salePrice").value("12.500"))
                .andExpect(jsonPath("$.items[0].quantity").value("7.000"))
                .andExpect(jsonPath("$.items[0].purchasePrice").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("purchase").doesNotContain("8.250");
        // the cost is not even selected from the database
        verify(productRepository).findPage(isNull(), eq(true), eq(false), any(), any());
    }

    @Test
    void storekeeperAndAccountantAndAdminSeeCost() throws Exception {
        stubOneProduct(true);
        for (var user : List.of(STOREKEEPER, ACCOUNTANT, ADMIN)) {
            mvc.perform(get("/api/v1/products").header("Authorization", bearer(user)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].purchasePrice").value("8.250"));
        }
    }

    @Test
    void inactiveProductsOnlyForUsersWhoManageProducts() throws Exception {
        stubOneProduct(false);
        mvc.perform(get("/api/v1/products?includeInactive=true").header("Authorization", bearer(CASHIER)))
                .andExpect(status().isOk());
        verify(productRepository).count(isNull(), eq(true));   // view-only: active only, as on the desktop

        stubOneProduct(true);
        mvc.perform(get("/api/v1/products?includeInactive=true").header("Authorization", bearer(STOREKEEPER)))
                .andExpect(status().isOk());
        verify(productRepository).count(isNull(), eq(false));
    }

    @Test
    void searchSortAndPageReachTheRepositoryValidated() throws Exception {
        stubOneProduct(false);
        when(productRepository.count(any(), anyBoolean())).thenReturn(45L);
        mvc.perform(get("/api/v1/products?q= خلاط &sort=salePrice,desc&page=2&size=20")
                        .header("Authorization", bearer(CASHIER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalPages").value(3));
        verify(productRepository).findPage(eq("خلاط"), eq(true), eq(false),
                eq(new ProductSort.Order(ProductSort.SALE_PRICE, true)), eq(new PageQuery(2, 20)));
    }

    @Test
    void pageBeyondTheEndDoesNotQueryRows() throws Exception {
        when(productRepository.count(any(), anyBoolean())).thenReturn(5L);
        mvc.perform(get("/api/v1/products?page=3").header("Authorization", bearer(CASHIER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalItems").value(5));
        verify(productRepository, never()).findPage(any(), anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    void pagingAndSortAreValidated() throws Exception {
        String token = bearer(CASHIER);
        record Case(String query, String field) {
        }
        for (Case c : List.of(new Case("size=0", "size"), new Case("size=101", "size"), new Case("page=-1", "page"),
                new Case("page=10001", "page"), new Case("size=abc", "size"), new Case("sort=price", "sort"),
                new Case("sort=name,sideways", "sort"), new Case("sort=name;DROP TABLE dbo.Users", "sort"),
                new Case("sort=p.purchase_price", "sort"), new Case("q=" + "x".repeat(101), "q"))) {
            mvc.perform(get("/api/v1/products?" + c.query()).header("Authorization", token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value(c.field()));
        }
        verify(productRepository, never()).count(any(), anyBoolean());
        verify(productRepository, never()).findPage(any(), anyBoolean(), anyBoolean(), any(), any());
    }

    @Test
    void roleWithoutProductPermissionIs403() throws Exception {
        // the role was changed to one without permissions after the token was issued: applies at once
        mvc.perform(get("/api/v1/products").header("Authorization", bearer(MANAGER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verify(productRepository, never()).count(any(), anyBoolean());
    }

    @Test
    void databaseErrorsNeverLeakSqlOrDetails() throws Exception {
        when(productRepository.count(any(), anyBoolean())).thenThrow(new UncategorizedSQLException("count",
                "SELECT COUNT_BIG(*) FROM dbo.Products p WHERE password_hash = 'x'",
                new SQLException("Invalid column name 'secret_col' on server SQLPROD01\\MSSQL", "S0001", 207)));
        String body = mvc.perform(get("/api/v1/products").header("Authorization", bearer(CASHIER)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("SELECT").doesNotContain("dbo.").doesNotContain("secret_col")
                .doesNotContain("SQLPROD01").doesNotContain("SQLException").doesNotContain("at com.");

        doThrow(new DataIntegrityViolationException("Violation of UNIQUE KEY constraint 'UQ_Products_product_code'"))
                .when(productRepository).count(any(), anyBoolean());
        body = mvc.perform(get("/api/v1/products").header("Authorization", bearer(CASHIER)))
                .andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("UQ_Products").doesNotContain("Violation");

        doThrow(new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection",
                new SQLException("Login failed for user 'sa'."))).when(productRepository).count(any(), anyBoolean());
        body = mvc.perform(get("/api/v1/products").header("Authorization", bearer(CASHIER)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Login failed").doesNotContain("'sa'").doesNotContain("JDBC");
    }

    @Test
    void unexpectedErrorsAreGeneric() throws Exception {
        when(productRepository.count(anyString(), anyBoolean())).thenThrow(
                new IllegalStateException("C:\\secret\\path\\config.properties not readable"));
        String body = mvc.perform(get("/api/v1/products?q=a").header("Authorization", bearer(CASHIER)))
                .andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("secret").doesNotContain("IllegalState").doesNotContain("config.properties");
    }

    @Test
    void coreErrorsKeepTheSafePhase1HttpModel() throws Exception {
        when(productRepository.count(any(), anyBoolean())).thenThrow(new com.almahwar.dao.DataAccessException(
                "SELECT private_column FROM dbo.Products", new SQLException("private_server", "S0001", 207)));
        String body = mvc.perform(get("/api/v1/products").header("Authorization", bearer(CASHIER)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("SELECT", "private_column", "private_server", "dbo.", "DataAccessException");
        doThrow(new com.almahwar.service.AccessDeniedException("private denial details"))
                .when(productRepository).count(any(), anyBoolean());
        body = mvc.perform(get("/api/v1/products").header("Authorization", bearer(CASHIER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("private denial");
    }
}
