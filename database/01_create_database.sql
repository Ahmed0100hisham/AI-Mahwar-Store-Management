/* ==========================================================================
   Al Mahwar Store Management System - نظام إدارة شركة المحور
   Full database schema (SQL Server 2016+)

   Run in SSMS, or:
       sqlcmd -S localhost -U sa -P "YourPassword" -f 65001 -i 01_create_database.sql

   Conventions
   - Table names as specified (Sale_Items ...), columns in snake_case.
   - All money / prices: DECIMAL(18,3)  (Kuwaiti Dinar, 3 decimals = fils).
   - All quantities:    DECIMAL(18,3)  (pipes are sold by the metre).
   - Arabic text:       NVARCHAR.
   - Dates:             DATETIME2(0) / DATE, defaults to SYSDATETIME().
   - Line totals and remaining amounts are PERSISTED computed columns so they
     can never disagree with their inputs. Do not INSERT/UPDATE them.
   - The script is re-runnable: existing objects are left untouched.
   ========================================================================== */

SET NOCOUNT ON;
-- Required for filtered indexes and persisted computed columns.
-- SSMS sets these by default, sqlcmd does not.
SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET QUOTED_IDENTIFIER ON;
SET NUMERIC_ROUNDABORT OFF;
GO

IF DB_ID(N'AlMahwarDB') IS NULL
BEGIN
    CREATE DATABASE AlMahwarDB COLLATE Arabic_CI_AS;
END
GO

USE AlMahwarDB;
GO

/* ==========================================================================
   1. SECURITY
   ========================================================================== */

IF OBJECT_ID(N'dbo.Roles', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Roles (
        role_id      INT IDENTITY(1,1) NOT NULL,
        role_code    VARCHAR(30)       NOT NULL,
        role_name    NVARCHAR(50)      NOT NULL,
        description  NVARCHAR(250)     NULL,
        is_active    BIT               NOT NULL CONSTRAINT DF_Roles_is_active  DEFAULT (1),
        created_at   DATETIME2(0)      NOT NULL CONSTRAINT DF_Roles_created_at DEFAULT (SYSDATETIME()),
        CONSTRAINT PK_Roles PRIMARY KEY (role_id),
        CONSTRAINT UQ_Roles_role_code UNIQUE (role_code),
        CONSTRAINT UQ_Roles_role_name UNIQUE (role_name)
    );
END
GO

IF OBJECT_ID(N'dbo.Users', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Users (
        user_id        INT IDENTITY(1,1) NOT NULL,
        username       NVARCHAR(50)      NOT NULL,
        password_hash  NVARCHAR(255)     NOT NULL,
        full_name      NVARCHAR(100)     NOT NULL,
        phone          NVARCHAR(20)      NULL,
        email          NVARCHAR(100)     NULL,
        role_id        INT               NOT NULL,
        is_active      BIT               NOT NULL CONSTRAINT DF_Users_is_active  DEFAULT (1),
        last_login_at  DATETIME2(0)      NULL,
        failed_login_attempts INT        NOT NULL CONSTRAINT DF_Users_failed_login_attempts DEFAULT (0),
        locked_until   DATETIME2(0)      NULL,   -- login refused until this server time
        created_at     DATETIME2(0)      NOT NULL CONSTRAINT DF_Users_created_at DEFAULT (SYSDATETIME()),
        updated_at     DATETIME2(0)      NOT NULL CONSTRAINT DF_Users_updated_at DEFAULT (SYSDATETIME()),
        CONSTRAINT PK_Users PRIMARY KEY (user_id),
        CONSTRAINT UQ_Users_username UNIQUE (username),
        CONSTRAINT FK_Users_Roles FOREIGN KEY (role_id) REFERENCES dbo.Roles (role_id)
    );
    CREATE INDEX IX_Users_role_id ON dbo.Users (role_id);
END
GO

/* Upgrade for databases created before the account-lock columns existed (v1.0.0 schema) */
IF COL_LENGTH(N'dbo.Users', N'failed_login_attempts') IS NULL
    ALTER TABLE dbo.Users ADD failed_login_attempts INT NOT NULL
        CONSTRAINT DF_Users_failed_login_attempts DEFAULT (0);
GO
IF COL_LENGTH(N'dbo.Users', N'locked_until') IS NULL
    ALTER TABLE dbo.Users ADD locked_until DATETIME2(0) NULL;
GO

/* ==========================================================================
   2. MASTER DATA
   ========================================================================== */

IF OBJECT_ID(N'dbo.Categories', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Categories (
        category_id         INT IDENTITY(1,1) NOT NULL,
        name_ar             NVARCHAR(100)     NOT NULL,
        name_en             NVARCHAR(100)     NULL,
        parent_category_id  INT               NULL,   -- e.g. مواسير > مواسير PPR
        description         NVARCHAR(250)     NULL,
        is_active           BIT               NOT NULL CONSTRAINT DF_Categories_is_active  DEFAULT (1),
        created_at          DATETIME2(0)      NOT NULL CONSTRAINT DF_Categories_created_at DEFAULT (SYSDATETIME()),
        updated_at          DATETIME2(0)      NOT NULL CONSTRAINT DF_Categories_updated_at DEFAULT (SYSDATETIME()),
        CONSTRAINT PK_Categories PRIMARY KEY (category_id),
        CONSTRAINT UQ_Categories_name_ar UNIQUE (name_ar),
        CONSTRAINT FK_Categories_Parent FOREIGN KEY (parent_category_id) REFERENCES dbo.Categories (category_id),
        CONSTRAINT CK_Categories_not_self_parent CHECK (parent_category_id IS NULL OR parent_category_id <> category_id)
    );
    CREATE INDEX IX_Categories_parent ON dbo.Categories (parent_category_id);
END
GO

IF OBJECT_ID(N'dbo.Brands', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Brands (
        brand_id    INT IDENTITY(1,1) NOT NULL,
        name_ar     NVARCHAR(100)     NOT NULL,
        name_en     NVARCHAR(100)     NULL,
        country     NVARCHAR(50)      NULL,
        is_active   BIT               NOT NULL CONSTRAINT DF_Brands_is_active  DEFAULT (1),
        created_at  DATETIME2(0)      NOT NULL CONSTRAINT DF_Brands_created_at DEFAULT (SYSDATETIME()),
        updated_at  DATETIME2(0)      NOT NULL CONSTRAINT DF_Brands_updated_at DEFAULT (SYSDATETIME()),
        CONSTRAINT PK_Brands PRIMARY KEY (brand_id),
        CONSTRAINT UQ_Brands_name_ar UNIQUE (name_ar)
    );
END
GO

IF OBJECT_ID(N'dbo.Units', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Units (
        unit_id         INT IDENTITY(1,1) NOT NULL,
        name_ar         NVARCHAR(50)      NOT NULL,
        name_en         NVARCHAR(50)      NULL,
        symbol          NVARCHAR(10)      NULL,
        allows_decimal  BIT               NOT NULL CONSTRAINT DF_Units_allows_decimal DEFAULT (0), -- metre/kg = 1, piece = 0
        is_active       BIT               NOT NULL CONSTRAINT DF_Units_is_active      DEFAULT (1),
        created_at      DATETIME2(0)      NOT NULL CONSTRAINT DF_Units_created_at     DEFAULT (SYSDATETIME()),
        CONSTRAINT PK_Units PRIMARY KEY (unit_id),
        CONSTRAINT UQ_Units_name_ar UNIQUE (name_ar)
    );
END
GO

IF OBJECT_ID(N'dbo.Products', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Products (
        product_id       INT IDENTITY(1,1) NOT NULL,
        barcode          NVARCHAR(50)      NULL,
        product_code     NVARCHAR(30)      NOT NULL,
        name_ar          NVARCHAR(200)     NOT NULL,
        name_en          NVARCHAR(200)     NULL,
        category_id      INT               NOT NULL,
        brand_id         INT               NULL,
        unit_id          INT               NOT NULL,
        size             NVARCHAR(50)      NULL,   -- e.g. 1/2", 20mm, 110mm
        color            NVARCHAR(50)      NULL,
        purchase_price   DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Products_purchase_price  DEFAULT (0),
        sale_price       DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Products_sale_price      DEFAULT (0),
        wholesale_price  DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Products_wholesale_price DEFAULT (0),
        quantity         DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Products_quantity        DEFAULT (0),
        minimum_stock    DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Products_minimum_stock   DEFAULT (0),
        location         NVARCHAR(100)     NULL,   -- shelf / store location
        is_active        BIT               NOT NULL CONSTRAINT DF_Products_is_active       DEFAULT (1),
        created_at       DATETIME2(0)      NOT NULL CONSTRAINT DF_Products_created_at      DEFAULT (SYSDATETIME()),
        updated_at       DATETIME2(0)      NOT NULL CONSTRAINT DF_Products_updated_at      DEFAULT (SYSDATETIME()),
        CONSTRAINT PK_Products PRIMARY KEY (product_id),
        CONSTRAINT UQ_Products_product_code UNIQUE (product_code),
        CONSTRAINT FK_Products_Categories FOREIGN KEY (category_id) REFERENCES dbo.Categories (category_id),
        CONSTRAINT FK_Products_Brands     FOREIGN KEY (brand_id)    REFERENCES dbo.Brands (brand_id),
        CONSTRAINT FK_Products_Units      FOREIGN KEY (unit_id)     REFERENCES dbo.Units (unit_id),
        CONSTRAINT CK_Products_prices_non_negative
            CHECK (purchase_price >= 0 AND sale_price >= 0 AND wholesale_price >= 0),
        CONSTRAINT CK_Products_minimum_stock CHECK (minimum_stock >= 0)
    );
    -- Barcode is optional but must be unique when present
    CREATE UNIQUE INDEX UX_Products_barcode ON dbo.Products (barcode) WHERE barcode IS NOT NULL;
    CREATE INDEX IX_Products_category ON dbo.Products (category_id);
    CREATE INDEX IX_Products_brand    ON dbo.Products (brand_id);
    CREATE INDEX IX_Products_unit     ON dbo.Products (unit_id);
    CREATE INDEX IX_Products_name_ar  ON dbo.Products (name_ar);
    CREATE INDEX IX_Products_name_en  ON dbo.Products (name_en);
    CREATE INDEX IX_Products_active_stock ON dbo.Products (is_active) INCLUDE (quantity, minimum_stock);
END
GO

IF OBJECT_ID(N'dbo.Customers', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Customers (
        customer_id      INT IDENTITY(1,1) NOT NULL,
        customer_code    NVARCHAR(30)      NOT NULL,
        name             NVARCHAR(150)     NOT NULL,
        customer_type    VARCHAR(20)       NOT NULL CONSTRAINT DF_Customers_type            DEFAULT ('RETAIL'),
        phone            NVARCHAR(20)      NULL,
        phone2           NVARCHAR(20)      NULL,
        email            NVARCHAR(100)     NULL,
        area             NVARCHAR(100)     NULL,   -- Kuwait area / governorate
        address          NVARCHAR(250)     NULL,
        credit_limit     DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Customers_credit_limit    DEFAULT (0),
        opening_balance  DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Customers_opening_balance DEFAULT (0),
        balance          DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Customers_balance         DEFAULT (0), -- amount the customer owes us
        notes            NVARCHAR(500)     NULL,
        is_active        BIT               NOT NULL CONSTRAINT DF_Customers_is_active       DEFAULT (1),
        created_at       DATETIME2(0)      NOT NULL CONSTRAINT DF_Customers_created_at      DEFAULT (SYSDATETIME()),
        updated_at       DATETIME2(0)      NOT NULL CONSTRAINT DF_Customers_updated_at      DEFAULT (SYSDATETIME()),
        CONSTRAINT PK_Customers PRIMARY KEY (customer_id),
        CONSTRAINT UQ_Customers_code UNIQUE (customer_code),
        CONSTRAINT CK_Customers_type CHECK (customer_type IN ('RETAIL', 'WHOLESALE', 'CONTRACTOR', 'COMPANY')),
        CONSTRAINT CK_Customers_credit_limit CHECK (credit_limit >= 0)
    );
    CREATE INDEX IX_Customers_name  ON dbo.Customers (name);
    CREATE INDEX IX_Customers_phone ON dbo.Customers (phone);
END
GO

IF OBJECT_ID(N'dbo.Suppliers', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Suppliers (
        supplier_id      INT IDENTITY(1,1) NOT NULL,
        supplier_code    NVARCHAR(30)      NOT NULL,
        name             NVARCHAR(150)     NOT NULL,
        contact_person   NVARCHAR(100)     NULL,
        phone            NVARCHAR(20)      NULL,
        phone2           NVARCHAR(20)      NULL,
        email            NVARCHAR(100)     NULL,
        country          NVARCHAR(50)      NULL,
        address          NVARCHAR(250)     NULL,
        opening_balance  DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Suppliers_opening_balance DEFAULT (0),
        balance          DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Suppliers_balance         DEFAULT (0), -- amount we owe the supplier
        notes            NVARCHAR(500)     NULL,
        is_active        BIT               NOT NULL CONSTRAINT DF_Suppliers_is_active       DEFAULT (1),
        created_at       DATETIME2(0)      NOT NULL CONSTRAINT DF_Suppliers_created_at      DEFAULT (SYSDATETIME()),
        updated_at       DATETIME2(0)      NOT NULL CONSTRAINT DF_Suppliers_updated_at      DEFAULT (SYSDATETIME()),
        CONSTRAINT PK_Suppliers PRIMARY KEY (supplier_id),
        CONSTRAINT UQ_Suppliers_code UNIQUE (supplier_code)
    );
    CREATE INDEX IX_Suppliers_name  ON dbo.Suppliers (name);
    CREATE INDEX IX_Suppliers_phone ON dbo.Suppliers (phone);
END
GO

/* ==========================================================================
   3. SALES
   Payment methods (Kuwait): CASH, KNET, CREDIT_CARD, BANK_TRANSFER, CHEQUE,
   CREDIT (آجل - on account), MIXED.
   ========================================================================== */

IF OBJECT_ID(N'dbo.Sales', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Sales (
        sale_id           INT IDENTITY(1,1) NOT NULL,
        invoice_no        NVARCHAR(30)      NOT NULL,
        sale_date         DATETIME2(0)      NOT NULL CONSTRAINT DF_Sales_sale_date       DEFAULT (SYSDATETIME()),
        customer_id       INT               NOT NULL,   -- walk-in sales use the cash customer
        user_id           INT               NOT NULL,
        price_type        VARCHAR(20)       NOT NULL CONSTRAINT DF_Sales_price_type      DEFAULT ('RETAIL'),
        payment_method    VARCHAR(20)       NOT NULL CONSTRAINT DF_Sales_payment_method  DEFAULT ('CASH'),
        subtotal          DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Sales_subtotal        DEFAULT (0),
        discount_amount   DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Sales_discount_amount DEFAULT (0),
        tax_amount        DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Sales_tax_amount      DEFAULT (0),
        total_amount      DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Sales_total_amount    DEFAULT (0),
        paid_amount       DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Sales_paid_amount     DEFAULT (0),
        remaining_amount  AS CAST(total_amount - paid_amount AS DECIMAL(18,3)) PERSISTED,
        status            VARCHAR(20)       NOT NULL CONSTRAINT DF_Sales_status          DEFAULT ('COMPLETED'),
        notes             NVARCHAR(500)     NULL,
        created_at        DATETIME2(0)      NOT NULL CONSTRAINT DF_Sales_created_at      DEFAULT (SYSDATETIME()),
        updated_at        DATETIME2(0)      NOT NULL CONSTRAINT DF_Sales_updated_at      DEFAULT (SYSDATETIME()),
        CONSTRAINT PK_Sales PRIMARY KEY (sale_id),
        CONSTRAINT UQ_Sales_invoice_no UNIQUE (invoice_no),
        CONSTRAINT FK_Sales_Customers FOREIGN KEY (customer_id) REFERENCES dbo.Customers (customer_id),
        CONSTRAINT FK_Sales_Users     FOREIGN KEY (user_id)     REFERENCES dbo.Users (user_id),
        CONSTRAINT CK_Sales_price_type     CHECK (price_type IN ('RETAIL', 'WHOLESALE')),
        CONSTRAINT CK_Sales_payment_method CHECK (payment_method IN ('CASH', 'KNET', 'CREDIT_CARD', 'BANK_TRANSFER', 'CHEQUE', 'CREDIT', 'MIXED')),
        CONSTRAINT CK_Sales_status         CHECK (status IN ('COMPLETED', 'CANCELLED')),
        CONSTRAINT CK_Sales_amounts_non_negative
            CHECK (subtotal >= 0 AND discount_amount >= 0 AND tax_amount >= 0 AND total_amount >= 0 AND paid_amount >= 0),
        CONSTRAINT CK_Sales_total   CHECK (total_amount = subtotal - discount_amount + tax_amount),
        CONSTRAINT CK_Sales_paid    CHECK (paid_amount <= total_amount)
    );
    CREATE INDEX IX_Sales_sale_date ON dbo.Sales (sale_date) INCLUDE (total_amount, status);
    CREATE INDEX IX_Sales_customer  ON dbo.Sales (customer_id, sale_date);
    CREATE INDEX IX_Sales_user      ON dbo.Sales (user_id);
END
GO

IF OBJECT_ID(N'dbo.Sale_Items', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Sale_Items (
        sale_item_id     INT IDENTITY(1,1) NOT NULL,
        sale_id          INT               NOT NULL,
        product_id       INT               NOT NULL,
        quantity         DECIMAL(18,3)     NOT NULL,
        unit_price       DECIMAL(18,3)     NOT NULL,
        purchase_price   DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Sale_Items_purchase_price  DEFAULT (0), -- cost at time of sale, for profit
        discount_amount  DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Sale_Items_discount_amount DEFAULT (0),
        line_total       AS CAST(quantity * unit_price - discount_amount AS DECIMAL(18,3)) PERSISTED,
        CONSTRAINT PK_Sale_Items PRIMARY KEY (sale_item_id),
        CONSTRAINT FK_Sale_Items_Sales    FOREIGN KEY (sale_id)    REFERENCES dbo.Sales (sale_id) ON DELETE CASCADE,
        CONSTRAINT FK_Sale_Items_Products FOREIGN KEY (product_id) REFERENCES dbo.Products (product_id),
        CONSTRAINT CK_Sale_Items_quantity CHECK (quantity > 0),
        CONSTRAINT CK_Sale_Items_prices   CHECK (unit_price >= 0 AND purchase_price >= 0),
        CONSTRAINT CK_Sale_Items_discount CHECK (discount_amount >= 0 AND discount_amount <= quantity * unit_price)
    );
    CREATE INDEX IX_Sale_Items_sale    ON dbo.Sale_Items (sale_id);
    CREATE INDEX IX_Sale_Items_product ON dbo.Sale_Items (product_id);
END
GO

/* ==========================================================================
   4. PURCHASES
   ========================================================================== */

IF OBJECT_ID(N'dbo.Purchases', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Purchases (
        purchase_id          INT IDENTITY(1,1) NOT NULL,
        purchase_no          NVARCHAR(30)      NOT NULL,
        supplier_invoice_no  NVARCHAR(50)      NULL,
        purchase_date        DATETIME2(0)      NOT NULL CONSTRAINT DF_Purchases_purchase_date   DEFAULT (SYSDATETIME()),
        supplier_id          INT               NOT NULL,
        user_id              INT               NOT NULL,
        payment_method       VARCHAR(20)       NOT NULL CONSTRAINT DF_Purchases_payment_method  DEFAULT ('CASH'),
        subtotal             DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Purchases_subtotal        DEFAULT (0),
        discount_amount      DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Purchases_discount_amount DEFAULT (0),
        total_amount         DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Purchases_total_amount    DEFAULT (0),
        paid_amount          DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Purchases_paid_amount     DEFAULT (0),
        remaining_amount     AS CAST(total_amount - paid_amount AS DECIMAL(18,3)) PERSISTED,
        status               VARCHAR(20)       NOT NULL CONSTRAINT DF_Purchases_status          DEFAULT ('COMPLETED'),
        notes                NVARCHAR(500)     NULL,
        created_at           DATETIME2(0)      NOT NULL CONSTRAINT DF_Purchases_created_at      DEFAULT (SYSDATETIME()),
        updated_at           DATETIME2(0)      NOT NULL CONSTRAINT DF_Purchases_updated_at      DEFAULT (SYSDATETIME()),
        CONSTRAINT PK_Purchases PRIMARY KEY (purchase_id),
        CONSTRAINT UQ_Purchases_purchase_no UNIQUE (purchase_no),
        CONSTRAINT FK_Purchases_Suppliers FOREIGN KEY (supplier_id) REFERENCES dbo.Suppliers (supplier_id),
        CONSTRAINT FK_Purchases_Users     FOREIGN KEY (user_id)     REFERENCES dbo.Users (user_id),
        CONSTRAINT CK_Purchases_payment_method CHECK (payment_method IN ('CASH', 'KNET', 'CREDIT_CARD', 'BANK_TRANSFER', 'CHEQUE', 'CREDIT', 'MIXED')),
        CONSTRAINT CK_Purchases_status         CHECK (status IN ('COMPLETED', 'CANCELLED')),
        CONSTRAINT CK_Purchases_amounts_non_negative
            CHECK (subtotal >= 0 AND discount_amount >= 0 AND total_amount >= 0 AND paid_amount >= 0),
        CONSTRAINT CK_Purchases_total CHECK (total_amount = subtotal - discount_amount),
        CONSTRAINT CK_Purchases_paid  CHECK (paid_amount <= total_amount)
    );
    -- The same supplier invoice must not be entered twice
    CREATE UNIQUE INDEX UX_Purchases_supplier_invoice
        ON dbo.Purchases (supplier_id, supplier_invoice_no) WHERE supplier_invoice_no IS NOT NULL;
    CREATE INDEX IX_Purchases_purchase_date ON dbo.Purchases (purchase_date) INCLUDE (total_amount, status);
    CREATE INDEX IX_Purchases_user          ON dbo.Purchases (user_id);
END
GO

IF OBJECT_ID(N'dbo.Purchase_Items', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Purchase_Items (
        purchase_item_id  INT IDENTITY(1,1) NOT NULL,
        purchase_id       INT               NOT NULL,
        product_id        INT               NOT NULL,
        quantity          DECIMAL(18,3)     NOT NULL,
        unit_cost         DECIMAL(18,3)     NOT NULL,
        discount_amount   DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Purchase_Items_discount_amount DEFAULT (0),
        line_total        AS CAST(quantity * unit_cost - discount_amount AS DECIMAL(18,3)) PERSISTED,
        CONSTRAINT PK_Purchase_Items PRIMARY KEY (purchase_item_id),
        CONSTRAINT FK_Purchase_Items_Purchases FOREIGN KEY (purchase_id) REFERENCES dbo.Purchases (purchase_id) ON DELETE CASCADE,
        CONSTRAINT FK_Purchase_Items_Products  FOREIGN KEY (product_id)  REFERENCES dbo.Products (product_id),
        CONSTRAINT CK_Purchase_Items_quantity  CHECK (quantity > 0),
        CONSTRAINT CK_Purchase_Items_unit_cost CHECK (unit_cost >= 0),
        CONSTRAINT CK_Purchase_Items_discount  CHECK (discount_amount >= 0 AND discount_amount <= quantity * unit_cost)
    );
    CREATE INDEX IX_Purchase_Items_purchase ON dbo.Purchase_Items (purchase_id);
    CREATE INDEX IX_Purchase_Items_product  ON dbo.Purchase_Items (product_id);
END
GO

/* ==========================================================================
   5. PAYMENTS, EXPENSES, CASH
   ========================================================================== */

IF OBJECT_ID(N'dbo.Customer_Payments', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Customer_Payments (
        payment_id      INT IDENTITY(1,1) NOT NULL,
        payment_no      NVARCHAR(30)      NOT NULL,
        customer_id     INT               NOT NULL,
        sale_id         INT               NULL,   -- optional: payment against a specific invoice
        payment_date    DATETIME2(0)      NOT NULL CONSTRAINT DF_Customer_Payments_payment_date   DEFAULT (SYSDATETIME()),
        amount          DECIMAL(18,3)     NOT NULL,
        payment_method  VARCHAR(20)       NOT NULL CONSTRAINT DF_Customer_Payments_payment_method DEFAULT ('CASH'),
        reference_no    NVARCHAR(50)      NULL,   -- KNET ref / cheque no / transfer ref
        user_id         INT               NOT NULL,
        notes           NVARCHAR(500)     NULL,
        created_at      DATETIME2(0)      NOT NULL CONSTRAINT DF_Customer_Payments_created_at     DEFAULT (SYSDATETIME()),
        CONSTRAINT PK_Customer_Payments PRIMARY KEY (payment_id),
        CONSTRAINT UQ_Customer_Payments_payment_no UNIQUE (payment_no),
        CONSTRAINT FK_Customer_Payments_Customers FOREIGN KEY (customer_id) REFERENCES dbo.Customers (customer_id),
        CONSTRAINT FK_Customer_Payments_Sales     FOREIGN KEY (sale_id)     REFERENCES dbo.Sales (sale_id),
        CONSTRAINT FK_Customer_Payments_Users     FOREIGN KEY (user_id)     REFERENCES dbo.Users (user_id),
        CONSTRAINT CK_Customer_Payments_amount CHECK (amount > 0),
        CONSTRAINT CK_Customer_Payments_method CHECK (payment_method IN ('CASH', 'KNET', 'CREDIT_CARD', 'BANK_TRANSFER', 'CHEQUE'))
    );
    CREATE INDEX IX_Customer_Payments_customer ON dbo.Customer_Payments (customer_id, payment_date);
    CREATE INDEX IX_Customer_Payments_sale     ON dbo.Customer_Payments (sale_id);
    CREATE INDEX IX_Customer_Payments_date     ON dbo.Customer_Payments (payment_date);
END
GO

IF OBJECT_ID(N'dbo.Supplier_Payments', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Supplier_Payments (
        payment_id      INT IDENTITY(1,1) NOT NULL,
        payment_no      NVARCHAR(30)      NOT NULL,
        supplier_id     INT               NOT NULL,
        purchase_id     INT               NULL,
        payment_date    DATETIME2(0)      NOT NULL CONSTRAINT DF_Supplier_Payments_payment_date   DEFAULT (SYSDATETIME()),
        amount          DECIMAL(18,3)     NOT NULL,
        payment_method  VARCHAR(20)       NOT NULL CONSTRAINT DF_Supplier_Payments_payment_method DEFAULT ('CASH'),
        reference_no    NVARCHAR(50)      NULL,
        user_id         INT               NOT NULL,
        notes           NVARCHAR(500)     NULL,
        created_at      DATETIME2(0)      NOT NULL CONSTRAINT DF_Supplier_Payments_created_at     DEFAULT (SYSDATETIME()),
        CONSTRAINT PK_Supplier_Payments PRIMARY KEY (payment_id),
        CONSTRAINT UQ_Supplier_Payments_payment_no UNIQUE (payment_no),
        CONSTRAINT FK_Supplier_Payments_Suppliers FOREIGN KEY (supplier_id) REFERENCES dbo.Suppliers (supplier_id),
        CONSTRAINT FK_Supplier_Payments_Purchases FOREIGN KEY (purchase_id) REFERENCES dbo.Purchases (purchase_id),
        CONSTRAINT FK_Supplier_Payments_Users     FOREIGN KEY (user_id)     REFERENCES dbo.Users (user_id),
        CONSTRAINT CK_Supplier_Payments_amount CHECK (amount > 0),
        CONSTRAINT CK_Supplier_Payments_method CHECK (payment_method IN ('CASH', 'KNET', 'CREDIT_CARD', 'BANK_TRANSFER', 'CHEQUE'))
    );
    CREATE INDEX IX_Supplier_Payments_supplier ON dbo.Supplier_Payments (supplier_id, payment_date);
    CREATE INDEX IX_Supplier_Payments_purchase ON dbo.Supplier_Payments (purchase_id);
    CREATE INDEX IX_Supplier_Payments_date     ON dbo.Supplier_Payments (payment_date);
END
GO

IF OBJECT_ID(N'dbo.Expenses', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Expenses (
        expense_id      INT IDENTITY(1,1) NOT NULL,
        expense_no      NVARCHAR(30)      NOT NULL,
        expense_date    DATETIME2(0)      NOT NULL CONSTRAINT DF_Expenses_expense_date   DEFAULT (SYSDATETIME()),
        expense_type    NVARCHAR(100)     NOT NULL,   -- إيجار، رواتب، كهرباء، نقل ...
        amount          DECIMAL(18,3)     NOT NULL,
        payment_method  VARCHAR(20)       NOT NULL CONSTRAINT DF_Expenses_payment_method DEFAULT ('CASH'),
        reference_no    NVARCHAR(50)      NULL,
        description     NVARCHAR(500)     NULL,
        user_id         INT               NOT NULL,
        created_at      DATETIME2(0)      NOT NULL CONSTRAINT DF_Expenses_created_at     DEFAULT (SYSDATETIME()),
        CONSTRAINT PK_Expenses PRIMARY KEY (expense_id),
        CONSTRAINT UQ_Expenses_expense_no UNIQUE (expense_no),
        CONSTRAINT FK_Expenses_Users FOREIGN KEY (user_id) REFERENCES dbo.Users (user_id),
        CONSTRAINT CK_Expenses_amount CHECK (amount > 0),
        CONSTRAINT CK_Expenses_method CHECK (payment_method IN ('CASH', 'KNET', 'CREDIT_CARD', 'BANK_TRANSFER', 'CHEQUE'))
    );
    CREATE INDEX IX_Expenses_date ON dbo.Expenses (expense_date) INCLUDE (amount, expense_type);
    CREATE INDEX IX_Expenses_type ON dbo.Expenses (expense_type);
END
GO

/* Every movement of money in or out of the shop's cash/bank.
   source_type + source_id point to the originating document. */
IF OBJECT_ID(N'dbo.Cash_Transactions', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Cash_Transactions (
        transaction_id    BIGINT IDENTITY(1,1) NOT NULL,
        transaction_date  DATETIME2(0)         NOT NULL CONSTRAINT DF_Cash_Transactions_date           DEFAULT (SYSDATETIME()),
        transaction_type  VARCHAR(3)           NOT NULL,   -- IN / OUT
        amount            DECIMAL(18,3)        NOT NULL,
        payment_method    VARCHAR(20)          NOT NULL CONSTRAINT DF_Cash_Transactions_payment_method DEFAULT ('CASH'),
        source_type       VARCHAR(30)          NOT NULL,
        source_id         INT                  NULL,
        description       NVARCHAR(250)        NULL,
        user_id           INT                  NOT NULL,
        created_at        DATETIME2(0)         NOT NULL CONSTRAINT DF_Cash_Transactions_created_at     DEFAULT (SYSDATETIME()),
        CONSTRAINT PK_Cash_Transactions PRIMARY KEY (transaction_id),
        CONSTRAINT FK_Cash_Transactions_Users FOREIGN KEY (user_id) REFERENCES dbo.Users (user_id),
        CONSTRAINT CK_Cash_Transactions_type   CHECK (transaction_type IN ('IN', 'OUT')),
        CONSTRAINT CK_Cash_Transactions_amount CHECK (amount > 0),
        CONSTRAINT CK_Cash_Transactions_method CHECK (payment_method IN ('CASH', 'KNET', 'CREDIT_CARD', 'BANK_TRANSFER', 'CHEQUE')),
        CONSTRAINT CK_Cash_Transactions_source CHECK (source_type IN (
            'SALE', 'PURCHASE', 'CUSTOMER_PAYMENT', 'SUPPLIER_PAYMENT', 'EXPENSE',
            'SALE_RETURN', 'PURCHASE_RETURN', 'OPENING_BALANCE', 'DEPOSIT', 'WITHDRAWAL', 'ADJUSTMENT'))
    );
    CREATE INDEX IX_Cash_Transactions_date   ON dbo.Cash_Transactions (transaction_date) INCLUDE (transaction_type, amount, payment_method);
    CREATE INDEX IX_Cash_Transactions_source ON dbo.Cash_Transactions (source_type, source_id);
END
GO

/* ==========================================================================
   6. INVENTORY
   quantity is signed: positive = stock in, negative = stock out.
   ========================================================================== */

IF OBJECT_ID(N'dbo.Stock_Movements', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Stock_Movements (
        movement_id     BIGINT IDENTITY(1,1) NOT NULL,
        product_id      INT                  NOT NULL,
        movement_date   DATETIME2(0)         NOT NULL CONSTRAINT DF_Stock_Movements_date       DEFAULT (SYSDATETIME()),
        movement_type   VARCHAR(30)          NOT NULL,
        quantity        DECIMAL(18,3)        NOT NULL,
        balance_after   DECIMAL(18,3)        NOT NULL,   -- product quantity after this movement
        unit_cost       DECIMAL(18,3)        NOT NULL CONSTRAINT DF_Stock_Movements_unit_cost  DEFAULT (0),
        reference_type  VARCHAR(30)          NULL,
        reference_id    INT                  NULL,
        notes           NVARCHAR(250)        NULL,
        user_id         INT                  NOT NULL,
        created_at      DATETIME2(0)         NOT NULL CONSTRAINT DF_Stock_Movements_created_at DEFAULT (SYSDATETIME()),
        CONSTRAINT PK_Stock_Movements PRIMARY KEY (movement_id),
        CONSTRAINT FK_Stock_Movements_Products FOREIGN KEY (product_id) REFERENCES dbo.Products (product_id),
        CONSTRAINT FK_Stock_Movements_Users    FOREIGN KEY (user_id)    REFERENCES dbo.Users (user_id),
        CONSTRAINT CK_Stock_Movements_quantity CHECK (quantity <> 0),
        CONSTRAINT CK_Stock_Movements_type CHECK (movement_type IN (
            'OPENING', 'PURCHASE', 'SALE', 'SALE_RETURN', 'PURCHASE_RETURN',
            'ADJUSTMENT_IN', 'ADJUSTMENT_OUT', 'DAMAGED'))
    );
    CREATE INDEX IX_Stock_Movements_product   ON dbo.Stock_Movements (product_id, movement_date);
    CREATE INDEX IX_Stock_Movements_reference ON dbo.Stock_Movements (reference_type, reference_id);
    CREATE INDEX IX_Stock_Movements_date      ON dbo.Stock_Movements (movement_date);
END
GO

/* ==========================================================================
   7. RETURNS
   ========================================================================== */

IF OBJECT_ID(N'dbo.Sale_Returns', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Sale_Returns (
        return_id      INT IDENTITY(1,1) NOT NULL,
        return_no      NVARCHAR(30)      NOT NULL,
        sale_id        INT               NOT NULL,
        customer_id    INT               NOT NULL,
        return_date    DATETIME2(0)      NOT NULL CONSTRAINT DF_Sale_Returns_return_date   DEFAULT (SYSDATETIME()),
        total_amount   DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Sale_Returns_total_amount  DEFAULT (0),
        refund_amount  DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Sale_Returns_refund_amount DEFAULT (0), -- cash paid back; the rest reduces the customer balance
        refund_method  VARCHAR(20)       NOT NULL CONSTRAINT DF_Sale_Returns_refund_method DEFAULT ('CASH'),
        reason         NVARCHAR(250)     NULL,
        user_id        INT               NOT NULL,
        created_at     DATETIME2(0)      NOT NULL CONSTRAINT DF_Sale_Returns_created_at    DEFAULT (SYSDATETIME()),
        CONSTRAINT PK_Sale_Returns PRIMARY KEY (return_id),
        CONSTRAINT UQ_Sale_Returns_return_no UNIQUE (return_no),
        CONSTRAINT FK_Sale_Returns_Sales     FOREIGN KEY (sale_id)     REFERENCES dbo.Sales (sale_id),
        CONSTRAINT FK_Sale_Returns_Customers FOREIGN KEY (customer_id) REFERENCES dbo.Customers (customer_id),
        CONSTRAINT FK_Sale_Returns_Users     FOREIGN KEY (user_id)     REFERENCES dbo.Users (user_id),
        CONSTRAINT CK_Sale_Returns_amounts CHECK (total_amount >= 0 AND refund_amount >= 0 AND refund_amount <= total_amount),
        CONSTRAINT CK_Sale_Returns_method  CHECK (refund_method IN ('CASH', 'KNET', 'CREDIT_CARD', 'BANK_TRANSFER', 'CHEQUE', 'CREDIT'))
    );
    CREATE INDEX IX_Sale_Returns_sale     ON dbo.Sale_Returns (sale_id);
    CREATE INDEX IX_Sale_Returns_customer ON dbo.Sale_Returns (customer_id);
    CREATE INDEX IX_Sale_Returns_date     ON dbo.Sale_Returns (return_date);
END
GO

IF OBJECT_ID(N'dbo.Sale_Return_Items', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Sale_Return_Items (
        return_item_id  INT IDENTITY(1,1) NOT NULL,
        return_id       INT               NOT NULL,
        sale_item_id    INT               NOT NULL,   -- original invoice line
        product_id      INT               NOT NULL,
        quantity        DECIMAL(18,3)     NOT NULL,
        unit_price      DECIMAL(18,3)     NOT NULL,
        line_total      AS CAST(quantity * unit_price AS DECIMAL(18,3)) PERSISTED,
        CONSTRAINT PK_Sale_Return_Items PRIMARY KEY (return_item_id),
        CONSTRAINT FK_Sale_Return_Items_Returns    FOREIGN KEY (return_id)    REFERENCES dbo.Sale_Returns (return_id) ON DELETE CASCADE,
        CONSTRAINT FK_Sale_Return_Items_Sale_Items FOREIGN KEY (sale_item_id) REFERENCES dbo.Sale_Items (sale_item_id),
        CONSTRAINT FK_Sale_Return_Items_Products   FOREIGN KEY (product_id)   REFERENCES dbo.Products (product_id),
        CONSTRAINT CK_Sale_Return_Items_quantity   CHECK (quantity > 0),
        CONSTRAINT CK_Sale_Return_Items_unit_price CHECK (unit_price >= 0)
    );
    CREATE INDEX IX_Sale_Return_Items_return    ON dbo.Sale_Return_Items (return_id);
    CREATE INDEX IX_Sale_Return_Items_sale_item ON dbo.Sale_Return_Items (sale_item_id);
    CREATE INDEX IX_Sale_Return_Items_product   ON dbo.Sale_Return_Items (product_id);
END
GO

IF OBJECT_ID(N'dbo.Purchase_Returns', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Purchase_Returns (
        return_id      INT IDENTITY(1,1) NOT NULL,
        return_no      NVARCHAR(30)      NOT NULL,
        purchase_id    INT               NOT NULL,
        supplier_id    INT               NOT NULL,
        return_date    DATETIME2(0)      NOT NULL CONSTRAINT DF_Purchase_Returns_return_date   DEFAULT (SYSDATETIME()),
        total_amount   DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Purchase_Returns_total_amount  DEFAULT (0),
        refund_amount  DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Purchase_Returns_refund_amount DEFAULT (0),
        refund_method  VARCHAR(20)       NOT NULL CONSTRAINT DF_Purchase_Returns_refund_method DEFAULT ('CASH'),
        reason         NVARCHAR(250)     NULL,
        user_id        INT               NOT NULL,
        created_at     DATETIME2(0)      NOT NULL CONSTRAINT DF_Purchase_Returns_created_at    DEFAULT (SYSDATETIME()),
        CONSTRAINT PK_Purchase_Returns PRIMARY KEY (return_id),
        CONSTRAINT UQ_Purchase_Returns_return_no UNIQUE (return_no),
        CONSTRAINT FK_Purchase_Returns_Purchases FOREIGN KEY (purchase_id) REFERENCES dbo.Purchases (purchase_id),
        CONSTRAINT FK_Purchase_Returns_Suppliers FOREIGN KEY (supplier_id) REFERENCES dbo.Suppliers (supplier_id),
        CONSTRAINT FK_Purchase_Returns_Users     FOREIGN KEY (user_id)     REFERENCES dbo.Users (user_id),
        CONSTRAINT CK_Purchase_Returns_amounts CHECK (total_amount >= 0 AND refund_amount >= 0 AND refund_amount <= total_amount),
        CONSTRAINT CK_Purchase_Returns_method  CHECK (refund_method IN ('CASH', 'KNET', 'CREDIT_CARD', 'BANK_TRANSFER', 'CHEQUE', 'CREDIT'))
    );
    CREATE INDEX IX_Purchase_Returns_purchase ON dbo.Purchase_Returns (purchase_id);
    CREATE INDEX IX_Purchase_Returns_supplier ON dbo.Purchase_Returns (supplier_id);
    CREATE INDEX IX_Purchase_Returns_date     ON dbo.Purchase_Returns (return_date);
END
GO

IF OBJECT_ID(N'dbo.Purchase_Return_Items', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Purchase_Return_Items (
        return_item_id    INT IDENTITY(1,1) NOT NULL,
        return_id         INT               NOT NULL,
        purchase_item_id  INT               NOT NULL,
        product_id        INT               NOT NULL,
        quantity          DECIMAL(18,3)     NOT NULL,
        unit_cost         DECIMAL(18,3)     NOT NULL,
        line_total        AS CAST(quantity * unit_cost AS DECIMAL(18,3)) PERSISTED,
        CONSTRAINT PK_Purchase_Return_Items PRIMARY KEY (return_item_id),
        CONSTRAINT FK_Purchase_Return_Items_Returns        FOREIGN KEY (return_id)        REFERENCES dbo.Purchase_Returns (return_id) ON DELETE CASCADE,
        CONSTRAINT FK_Purchase_Return_Items_Purchase_Items FOREIGN KEY (purchase_item_id) REFERENCES dbo.Purchase_Items (purchase_item_id),
        CONSTRAINT FK_Purchase_Return_Items_Products       FOREIGN KEY (product_id)       REFERENCES dbo.Products (product_id),
        CONSTRAINT CK_Purchase_Return_Items_quantity  CHECK (quantity > 0),
        CONSTRAINT CK_Purchase_Return_Items_unit_cost CHECK (unit_cost >= 0)
    );
    CREATE INDEX IX_Purchase_Return_Items_return        ON dbo.Purchase_Return_Items (return_id);
    CREATE INDEX IX_Purchase_Return_Items_purchase_item ON dbo.Purchase_Return_Items (purchase_item_id);
    CREATE INDEX IX_Purchase_Return_Items_product       ON dbo.Purchase_Return_Items (product_id);
END
GO

/* ==========================================================================
   8. QUOTATIONS (عروض الأسعار - common for contractors)
   ========================================================================== */

IF OBJECT_ID(N'dbo.Quotations', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Quotations (
        quotation_id       INT IDENTITY(1,1) NOT NULL,
        quotation_no       NVARCHAR(30)      NOT NULL,
        quotation_date     DATETIME2(0)      NOT NULL CONSTRAINT DF_Quotations_quotation_date  DEFAULT (SYSDATETIME()),
        valid_until        DATE              NULL,
        customer_id        INT               NULL,   -- registered customer, or
        customer_name      NVARCHAR(150)     NULL,   -- free-text name for prospects
        customer_phone     NVARCHAR(20)      NULL,
        price_type         VARCHAR(20)       NOT NULL CONSTRAINT DF_Quotations_price_type      DEFAULT ('RETAIL'),
        subtotal           DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Quotations_subtotal        DEFAULT (0),
        discount_amount    DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Quotations_discount_amount DEFAULT (0),
        total_amount       DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Quotations_total_amount    DEFAULT (0),
        status             VARCHAR(20)       NOT NULL CONSTRAINT DF_Quotations_status          DEFAULT ('DRAFT'),
        converted_sale_id  INT               NULL,
        notes              NVARCHAR(500)     NULL,
        user_id            INT               NOT NULL,
        created_at         DATETIME2(0)      NOT NULL CONSTRAINT DF_Quotations_created_at      DEFAULT (SYSDATETIME()),
        updated_at         DATETIME2(0)      NOT NULL CONSTRAINT DF_Quotations_updated_at      DEFAULT (SYSDATETIME()),
        CONSTRAINT PK_Quotations PRIMARY KEY (quotation_id),
        CONSTRAINT UQ_Quotations_quotation_no UNIQUE (quotation_no),
        CONSTRAINT FK_Quotations_Customers FOREIGN KEY (customer_id)       REFERENCES dbo.Customers (customer_id),
        CONSTRAINT FK_Quotations_Sales     FOREIGN KEY (converted_sale_id) REFERENCES dbo.Sales (sale_id),
        CONSTRAINT FK_Quotations_Users     FOREIGN KEY (user_id)           REFERENCES dbo.Users (user_id),
        CONSTRAINT CK_Quotations_customer   CHECK (customer_id IS NOT NULL OR customer_name IS NOT NULL),
        CONSTRAINT CK_Quotations_price_type CHECK (price_type IN ('RETAIL', 'WHOLESALE')),
        CONSTRAINT CK_Quotations_status     CHECK (status IN ('DRAFT', 'SENT', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'CONVERTED')),
        CONSTRAINT CK_Quotations_amounts    CHECK (subtotal >= 0 AND discount_amount >= 0 AND total_amount >= 0),
        CONSTRAINT CK_Quotations_total      CHECK (total_amount = subtotal - discount_amount)
    );
    CREATE INDEX IX_Quotations_date     ON dbo.Quotations (quotation_date);
    CREATE INDEX IX_Quotations_customer ON dbo.Quotations (customer_id);
    CREATE INDEX IX_Quotations_status   ON dbo.Quotations (status);
END
GO

IF OBJECT_ID(N'dbo.Quotation_Items', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Quotation_Items (
        quotation_item_id  INT IDENTITY(1,1) NOT NULL,
        quotation_id       INT               NOT NULL,
        product_id         INT               NOT NULL,
        quantity           DECIMAL(18,3)     NOT NULL,
        unit_price         DECIMAL(18,3)     NOT NULL,
        discount_amount    DECIMAL(18,3)     NOT NULL CONSTRAINT DF_Quotation_Items_discount_amount DEFAULT (0),
        line_total         AS CAST(quantity * unit_price - discount_amount AS DECIMAL(18,3)) PERSISTED,
        CONSTRAINT PK_Quotation_Items PRIMARY KEY (quotation_item_id),
        CONSTRAINT FK_Quotation_Items_Quotations FOREIGN KEY (quotation_id) REFERENCES dbo.Quotations (quotation_id) ON DELETE CASCADE,
        CONSTRAINT FK_Quotation_Items_Products   FOREIGN KEY (product_id)   REFERENCES dbo.Products (product_id),
        CONSTRAINT CK_Quotation_Items_quantity   CHECK (quantity > 0),
        CONSTRAINT CK_Quotation_Items_unit_price CHECK (unit_price >= 0),
        CONSTRAINT CK_Quotation_Items_discount   CHECK (discount_amount >= 0 AND discount_amount <= quantity * unit_price)
    );
    CREATE INDEX IX_Quotation_Items_quotation ON dbo.Quotation_Items (quotation_id);
    CREATE INDEX IX_Quotation_Items_product   ON dbo.Quotation_Items (product_id);
END
GO

/* ==========================================================================
   9. AUDIT
   ========================================================================== */

IF OBJECT_ID(N'dbo.Audit_Log', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Audit_Log (
        log_id        BIGINT IDENTITY(1,1) NOT NULL,
        user_id       INT                  NULL,   -- NULL for system actions / failed logins
        action        VARCHAR(30)          NOT NULL,   -- INSERT, UPDATE, DELETE, LOGIN, LOGOUT, LOGIN_FAILED, ...
        table_name    NVARCHAR(100)        NULL,
        record_id     NVARCHAR(50)         NULL,
        old_values    NVARCHAR(MAX)        NULL,   -- JSON
        new_values    NVARCHAR(MAX)        NULL,   -- JSON
        description   NVARCHAR(500)        NULL,
        machine_name  NVARCHAR(100)        NULL,
        created_at    DATETIME2(0)         NOT NULL CONSTRAINT DF_Audit_Log_created_at DEFAULT (SYSDATETIME()),
        CONSTRAINT PK_Audit_Log PRIMARY KEY (log_id),
        CONSTRAINT FK_Audit_Log_Users FOREIGN KEY (user_id) REFERENCES dbo.Users (user_id)
    );
    CREATE INDEX IX_Audit_Log_created_at ON dbo.Audit_Log (created_at);
    CREATE INDEX IX_Audit_Log_user       ON dbo.Audit_Log (user_id, created_at);
    CREATE INDEX IX_Audit_Log_record     ON dbo.Audit_Log (table_name, record_id);
END
GO

/* ==========================================================================
   10. SEED DATA (only inserted if missing)
   ========================================================================== */

INSERT INTO dbo.Roles (role_code, role_name, description)
SELECT v.role_code, v.role_name, v.description
FROM (VALUES
    ('ADMIN',       N'مدير النظام', N'صلاحيات كاملة على النظام'),
    ('MANAGER',     N'مدير المحل',  N'إدارة المبيعات والمشتريات والتقارير'),
    ('CASHIER',     N'كاشير',       N'إصدار فواتير البيع وتحصيل المبالغ'),
    ('STOREKEEPER', N'أمين مخزن',   N'إدارة الأصناف والمخزون والاستلام'),
    ('ACCOUNTANT',  N'محاسب',       N'الحسابات والمدفوعات والمصروفات')
) AS v (role_code, role_name, description)
WHERE NOT EXISTS (SELECT 1 FROM dbo.Roles r WHERE r.role_code = v.role_code);
GO

INSERT INTO dbo.Units (name_ar, name_en, symbol, allows_decimal)
SELECT v.name_ar, v.name_en, v.symbol, v.allows_decimal
FROM (VALUES
    (N'حبة',   N'Piece',  N'pc',  0),
    (N'متر',   N'Metre',  N'm',   1),
    (N'لفة',   N'Roll',   N'roll', 0),
    (N'طقم',   N'Set',    N'set', 0),
    (N'كرتون', N'Carton', N'ctn', 0),
    (N'علبة',  N'Box',    N'box', 0),
    (N'كيلو',  N'Kilogram', N'kg', 1),
    (N'جالون', N'Gallon', N'gal', 0)
) AS v (name_ar, name_en, symbol, allows_decimal)
WHERE NOT EXISTS (SELECT 1 FROM dbo.Units u WHERE u.name_ar = v.name_ar);
GO

INSERT INTO dbo.Categories (name_ar, name_en)
SELECT v.name_ar, v.name_en
FROM (VALUES
    (N'مواسير',         N'Pipes'),
    (N'وصلات',          N'Fittings'),
    (N'محابس',          N'Valves'),
    (N'خلاطات',         N'Mixers & Taps'),
    (N'طلمبات',         N'Pumps'),
    (N'أدوات صحية',     N'Sanitary Ware'),
    (N'إكسسوارات',      N'Accessories'),
    (N'أدوات سباكة',    N'Plumbing Tools'),
    (N'سخانات',         N'Water Heaters')
) AS v (name_ar, name_en)
WHERE NOT EXISTS (SELECT 1 FROM dbo.Categories c WHERE c.name_ar = v.name_ar);
GO

-- Default walk-in customer used for cash sales
IF NOT EXISTS (SELECT 1 FROM dbo.Customers WHERE customer_code = N'CASH')
    INSERT INTO dbo.Customers (customer_code, name, customer_type, notes)
    VALUES (N'CASH', N'عميل نقدي', 'RETAIL', N'عميل افتراضي لفواتير البيع النقدي');
GO

PRINT N'AlMahwarDB schema is ready.';
GO
