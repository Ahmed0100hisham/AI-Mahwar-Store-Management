/* ==========================================================================
   Al Mahwar Store Management System - DEMO / TEST DATA ONLY
   بيانات تجريبية لتجربة لوحة التحكم - لا تُشغَّل على قاعدة بيانات حقيقية

   Fills an EMPTY database (created by 01_create_database.sql) with ~30 days of
   realistic activity: products, customers, suppliers, sales, purchases, a sale
   return, customer payments, expenses, stock movements and cash transactions.

   Safety: refuses to run if any product or sale already exists.
   Requires: an ADMIN user (create it from the app's first-run screen).

       sqlcmd -S localhost -U sa -P "YourPassword" -f 65001 -i 02_demo_data.sql
   ========================================================================== */

SET NOCOUNT ON;
SET XACT_ABORT ON;
SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET QUOTED_IDENTIFIER ON;
SET NUMERIC_ROUNDABORT OFF;
GO

USE AlMahwarDB;
GO

/* Everything below is one batch so RETURN stops the whole script. */
IF EXISTS (SELECT 1 FROM dbo.Products) OR EXISTS (SELECT 1 FROM dbo.Sales)
BEGIN
    RAISERROR (N'قاعدة البيانات تحتوي على منتجات أو مبيعات. البيانات التجريبية مخصصة لقاعدة اختبار فارغة فقط.', 16, 1);
    RETURN;
END

DECLARE @uid INT = (SELECT MIN(u.user_id) FROM dbo.Users u
                    JOIN dbo.Roles r ON r.role_id = u.role_id WHERE r.role_code = 'ADMIN');
IF @uid IS NULL
BEGIN
    RAISERROR (N'لا يوجد مستخدم بدور مدير النظام. شغّل البرنامج وأنشئ حساب المدير أولًا.', 16, 1);
    RETURN;
END

DECLARE @today DATE = CAST(SYSDATETIME() AS date);
DECLARE @cash INT = (SELECT customer_id FROM dbo.Customers WHERE customer_code = N'CASH');

BEGIN TRANSACTION;

/* ---------- Brands ---------- */
INSERT INTO dbo.Brands (name_ar, name_en, country) VALUES
    (N'جروهي',             'Grohe',          N'ألمانيا'),
    (N'هانزجروهي',          'Hansgrohe',      N'ألمانيا'),
    (N'أيديال ستاندرد',      'Ideal Standard', N'بلجيكا'),
    (N'بيدرولو',            'Pedrollo',       N'إيطاليا'),
    (N'جرندفوس',            'Grundfos',       N'الدنمارك'),
    (N'رأس الخيمة للسيراميك', 'RAK Ceramics',   N'الإمارات'),
    (N'كوزموبلاست',         'Cosmoplast',     N'الإمارات'),
    (N'أريستون',            'Ariston',        N'إيطاليا');

/* ---------- Products (some deliberately at/below minimum stock) ---------- */
INSERT INTO dbo.Products (barcode, product_code, name_ar, name_en, category_id, brand_id, unit_id, size, color,
                          purchase_price, sale_price, wholesale_price, quantity, minimum_stock, location, created_at)
SELECT v.barcode, v.code, v.name_ar, v.name_en, c.category_id, b.brand_id, u.unit_id, v.size, v.color,
       v.buy, v.sell, v.wholesale, v.qty, v.min_qty, v.loc, DATEADD(DAY, -31, SYSDATETIME())
FROM (VALUES
    ('6291100010011', N'P-1001', N'ماسورة PPR 20 مم',        'PPR Pipe 20mm',        N'مواسير',      'Cosmoplast',     N'متر',  N'20mm',  N'أخضر',  0.180,  0.350,  0.300, 850, 100, N'مخزن A'),
    ('6291100010028', N'P-1002', N'ماسورة PPR 25 مم',        'PPR Pipe 25mm',        N'مواسير',      'Cosmoplast',     N'متر',  N'25mm',  N'أخضر',  0.250,  0.450,  0.390, 900, 100, N'مخزن A'),
    ('6291100010035', N'P-1003', N'ماسورة PVC 4 إنش',        'PVC Pipe 4in',         N'مواسير',      'Cosmoplast',     N'متر',  N'4"',    N'رمادي', 0.900,  1.500,  1.300, 320,  50, N'مخزن A'),
    ('6291100010042', N'P-1004', N'ماسورة PVC 2 إنش',        'PVC Pipe 2in',         N'مواسير',      'Cosmoplast',     N'متر',  N'2"',    N'رمادي', 0.400,  0.750,  0.650,  35,  60, N'مخزن A'),
    ('6291100010059', N'P-1005', N'كوع PPR 20 مم',           'PPR Elbow 20mm',       N'وصلات',       'Cosmoplast',     N'حبة',  N'20mm',  N'أخضر',  0.040,  0.100,  0.080, 1200, 200, N'رف B-1'),
    ('6291100010066', N'P-1006', N'تي PPR 25 مم',            'PPR Tee 25mm',         N'وصلات',       'Cosmoplast',     N'حبة',  N'25mm',  N'أخضر',  0.060,  0.150,  0.120, 900, 200, N'رف B-1'),
    ('6291100010073', N'P-1007', N'وصلة نحاس 1/2 إنش',       'Brass Coupling 1/2in', N'وصلات',       NULL,             N'حبة',  N'1/2"',  NULL,     0.350,  0.650,  0.550, 500,  50, N'رف B-2'),
    ('6291100010080', N'P-1008', N'محبس كرة 1/2 إنش',        'Ball Valve 1/2in',     N'محابس',       NULL,             N'حبة',  N'1/2"',  NULL,     1.200,  2.250,  1.950,  80,  20, N'رف B-3'),
    ('6291100010097', N'P-1009', N'محبس زاوية',              'Angle Valve',          N'محابس',       'Ideal Standard', N'حبة',  N'1/2"',  N'كروم',  0.850,  1.750,  1.500,  12,  25, N'رف B-3'),
    ('6291100010103', N'P-1010', N'خلاط مغسلة جروهي',        'Grohe Basin Mixer',    N'خلاطات',      'Grohe',          N'حبة',  NULL,     N'كروم', 18.500, 29.900, 26.500,  25,   5, N'معرض 1'),
    ('6291100010110', N'P-1011', N'خلاط مطبخ هانزجروهي',     'Hansgrohe Kitchen Mixer', N'خلاطات',   'Hansgrohe',      N'حبة',  NULL,     N'كروم', 32.000, 49.500, 44.000,   4,   5, N'معرض 1'),
    ('6291100010127', N'P-1012', N'خلاط دش',                 'Shower Mixer',         N'خلاطات',      'Ideal Standard', N'حبة',  NULL,     N'كروم', 14.000, 23.750, 21.000,  18,   5, N'معرض 1'),
    ('6291100010134', N'P-1013', N'طلمبة مياه 1 حصان',       'Water Pump 1HP',       N'طلمبات',      'Pedrollo',       N'حبة',  N'1HP',   NULL,    38.000, 55.000, 50.000,  16,   3, N'مخزن C'),
    ('6291100010141', N'P-1014', N'طلمبة ضغط جرندفوس',       'Grundfos Booster Pump', N'طلمبات',     'Grundfos',       N'حبة',  NULL,     NULL,    85.000,125.000,115.000,   2,   3, N'مخزن C'),
    ('6291100010158', N'P-1015', N'مرحاض أرضي',              'Floor WC',             N'أدوات صحية',  'RAK Ceramics',   N'طقم',  NULL,     N'أبيض', 22.000, 35.000, 31.500,  30,   4, N'معرض 2'),
    ('6291100010165', N'P-1016', N'مغسلة بعمود',             'Pedestal Basin',       N'أدوات صحية',  'RAK Ceramics',   N'طقم',  NULL,     N'أبيض', 16.500, 26.000, 23.500,  24,   4, N'معرض 2'),
    ('6291100010172', N'P-1017', N'شطاف حمام',               'Bidet Spray',          N'إكسسوارات',   NULL,             N'حبة',  NULL,     N'كروم',  1.100,  2.500,  2.000, 150,  30, N'رف D-1'),
    ('6291100010189', N'P-1018', N'حامل مناشف',              'Towel Holder',         N'إكسسوارات',   NULL,             N'حبة',  NULL,     N'كروم',  2.300,  4.750,  4.000,   0,  10, N'رف D-1'),
    ('6291100010196', N'P-1019', N'شريط تفلون',              'PTFE Tape',            N'أدوات سباكة', NULL,             N'لفة',  NULL,     N'أبيض',  0.050,  0.150,  0.120, 950, 100, N'رف D-2'),
    ('6291100010202', N'P-1020', N'مفتاح مواسير 14 إنش',     'Pipe Wrench 14in',     N'أدوات سباكة', NULL,             N'حبة',  N'14"',   NULL,     3.200,  5.500,  4.800,  80,   5, N'رف D-2'),
    ('6291100010219', N'P-1021', N'سخان مياه 50 جالون',      'Water Heater 50gal',   N'سخانات',      'Ariston',        N'حبة',  N'50gal', N'أبيض', 45.000, 68.000, 62.000,  14,   2, N'مخزن C')
) AS v (barcode, code, name_ar, name_en, cat, brand, unit, size, color, buy, sell, wholesale, qty, min_qty, loc)
JOIN dbo.Categories c ON c.name_ar = v.cat
JOIN dbo.Units u      ON u.name_ar = v.unit
LEFT JOIN dbo.Brands b ON b.name_en = v.brand;

INSERT INTO dbo.Stock_Movements (product_id, movement_date, movement_type, quantity, balance_after, unit_cost, notes, user_id)
SELECT product_id, DATEADD(DAY, -31, SYSDATETIME()), 'OPENING_BALANCE', quantity, quantity, purchase_price, N'رصيد افتتاحي', @uid
FROM dbo.Products WHERE quantity > 0;

/* ---------- Customers & suppliers ---------- */
INSERT INTO dbo.Customers (customer_code, name, customer_type, phone, area, credit_limit) VALUES
    (N'C-001', N'مؤسسة الخليج للمقاولات', 'CONTRACTOR', N'99887766', N'الفروانية',   2000),
    (N'C-002', N'شركة البناء الحديث',     'COMPANY',    N'22445566', N'حولي',        3000),
    (N'C-003', N'أبو فهد للسباكة',        'CONTRACTOR', N'66554433', N'الجهراء',      800),
    (N'C-004', N'محمد العنزي',            'RETAIL',     N'55112233', N'الأحمدي',        0),
    (N'C-005', N'مؤسسة النور للصيانة',     'WHOLESALE',  N'99001122', N'مبارك الكبير', 1500);

INSERT INTO dbo.Suppliers (supplier_code, name, contact_person, phone, country) VALUES
    (N'S-001', N'الشركة المتحدة للأدوات الصحية', N'أبو محمد', N'22334455', N'الكويت'),
    (N'S-002', N'مصنع الخليج للبلاستيك',        N'سامي',     N'97150123', N'الإمارات'),
    (N'S-003', N'الوكيل الأوروبي للخلاطات',      N'كريم',     N'22998877', N'الكويت');

/* ---------- Opening cash ---------- */
INSERT INTO dbo.Cash_Transactions (transaction_date, transaction_type, amount, payment_method, source_type, description, user_id)
VALUES (DATEADD(DAY, -31, SYSDATETIME()), 'IN', 1500.000, 'CASH', 'OPENING_BALANCE', N'رصيد افتتاحي للخزنة', @uid);

/* ---------- Sales: 30 days ---------- */
DECLARE @day INT = 29, @k INT, @n INT, @inv INT = 0, @r INT, @items INT;
DECLARE @cust INT, @ctype VARCHAR(20), @pt VARCHAR(20), @method VARCHAR(20), @date DATETIME2(0), @sid INT, @invno NVARCHAR(30);
DECLARE @sub DECIMAL(18,3), @disc DECIMAL(18,3), @total DECIMAL(18,3), @paid DECIMAL(18,3);

WHILE @day >= 0
BEGIN
    SET @n = 3 + ABS(CHECKSUM(NEWID())) % 6;          -- 3..8 invoices a day
    IF DATEPART(WEEKDAY, DATEADD(DAY, -@day, @today)) = 6 SET @n = 1 + @n / 3;   -- quieter Fridays (default DATEFIRST 7)
    SET @k = 0;
    WHILE @k < @n
    BEGIN
        SET @inv += 1;
        SET @r = ABS(CHECKSUM(NEWID())) % 100;

        IF @r < 60
            SELECT @cust = @cash, @ctype = 'RETAIL';
        ELSE
            SELECT TOP 1 @cust = customer_id, @ctype = customer_type
            FROM dbo.Customers WHERE customer_code <> N'CASH' ORDER BY NEWID();

        SET @pt = CASE WHEN @ctype = 'RETAIL' THEN 'RETAIL' ELSE 'WHOLESALE' END;
        SET @method = CASE WHEN @cust = @cash THEN CASE WHEN @r % 2 = 0 THEN 'CASH' ELSE 'KNET' END
                           WHEN @r < 85 THEN 'CREDIT' ELSE 'KNET' END;

        IF @day = 0
            SET @date = DATEADD(MINUTE, -((@n - 1 - @k) * 9 + 2), SYSDATETIME());   -- today's invoices: the last hour or so, numbers in time order
        ELSE
            SET @date = DATEADD(MINUTE, 8 * 60 + @k * (13 * 60 / @n) + ABS(CHECKSUM(NEWID())) % (13 * 60 / @n),
                                CAST(DATEADD(DAY, -@day, @today) AS DATETIME2(0)));   -- 08:00-21:00, in number order

        SET @invno = CONCAT(N'SAL-', FORMAT(@inv, '000000'));   -- same numbering as the POS (oldest first)
        INSERT INTO dbo.Sales (invoice_no, sale_date, customer_id, user_id, price_type, payment_method, status,
                               posted_at, posted_by, created_at)
        VALUES (@invno, @date, @cust, @uid, @pt, @method, 'POSTED', @date, @uid, @date);
        SET @sid = SCOPE_IDENTITY();

        -- 1..4 distinct products that have enough stock for the quantity picked
        SET @items = 1 + ABS(CHECKSUM(NEWID())) % 4;
        INSERT INTO dbo.Sale_Items (sale_id, product_id, quantity, unit_price, unit_cost)
        SELECT TOP (@items) @sid, p.product_id,
               CASE WHEN u.allows_decimal = 1 THEN CAST((2 + ABS(CHECKSUM(NEWID())) % 39) / 2.0 AS DECIMAL(18,3))
                    WHEN p.sale_price < 1 THEN 1 + ABS(CHECKSUM(NEWID())) % 20
                    ELSE 1 + ABS(CHECKSUM(NEWID())) % 2 END,
               CASE WHEN @pt = 'WHOLESALE' THEN p.wholesale_price ELSE p.sale_price END,
               p.purchase_price
        FROM dbo.Products p
        JOIN dbo.Units u ON u.unit_id = p.unit_id
        -- metre/cheap items sell up to 20 units, so need 25+ in stock; others sell 1-2, so need 3+
        WHERE (p.quantity >= 25 OR (u.allows_decimal = 0 AND p.sale_price >= 1 AND p.quantity >= 3))
          AND (p.sale_price < 10 OR ABS(CHECKSUM(NEWID())) % 100 < 30)   -- big-ticket items sell less often
        ORDER BY NEWID();

        SET @sub = (SELECT SUM(line_total) FROM dbo.Sale_Items WHERE sale_id = @sid);
        SET @disc = CASE WHEN @sub > 20 AND @r % 5 = 0 THEN ROUND(@sub * 0.05, 3) ELSE 0 END;
        SET @total = @sub - @disc;
        SET @paid = CASE WHEN @method <> 'CREDIT' THEN @total
                         WHEN @r % 3 = 0 THEN ROUND(@total / 2, 3) ELSE 0 END;
        UPDATE dbo.Sales SET subtotal = @sub, discount_amount = @disc, total_amount = @total, paid_amount = @paid,
               -- payment_method = how the paid part was paid (a part-paid credit sale was paid in cash)
               payment_method = CASE WHEN @method = 'CREDIT' AND @paid > 0 THEN 'CASH' ELSE @method END,
               cost_total = (SELECT SUM(CAST(quantity * unit_cost AS DECIMAL(18,3))) FROM dbo.Sale_Items WHERE sale_id = @sid)
        WHERE sale_id = @sid;

        UPDATE p SET p.quantity = p.quantity - si.quantity, p.updated_at = @date
        FROM dbo.Products p JOIN dbo.Sale_Items si ON si.product_id = p.product_id
        WHERE si.sale_id = @sid;

        INSERT INTO dbo.Stock_Movements (product_id, movement_date, movement_type, quantity, balance_after, unit_cost,
                                         reference_type, reference_id, user_id)
        SELECT si.product_id, @date, 'SALE', -si.quantity, p.quantity, si.unit_cost, 'SALE', @sid, @uid
        FROM dbo.Sale_Items si JOIN dbo.Products p ON p.product_id = si.product_id
        WHERE si.sale_id = @sid;

        IF @paid > 0
            INSERT INTO dbo.Cash_Transactions (transaction_date, transaction_type, amount, payment_method,
                                               source_type, source_id, description, user_id)
            VALUES (@date, 'IN', @paid, CASE WHEN @method = 'KNET' THEN 'KNET' ELSE 'CASH' END,
                    'SALE', @sid, CONCAT(N'فاتورة بيع ', @invno), @uid);

        -- Named customers get the invoice on their account (the walk-in CASH customer has none)
        IF @cust <> @cash
        BEGIN
            INSERT INTO dbo.Account_Ledger (party_type, customer_id, entry_date, entry_type, debit, credit,
                                            reference_type, reference_id, reference_no, description, user_id, created_at)
            VALUES ('CUSTOMER', @cust, @date, 'SALE', @total, 0, 'SALE', @sid, @invno, N'فاتورة بيع', @uid, @date);
            IF @paid > 0
                INSERT INTO dbo.Account_Ledger (party_type, customer_id, entry_date, entry_type, debit, credit,
                                                reference_type, reference_id, reference_no, description, user_id, created_at)
                VALUES ('CUSTOMER', @cust, @date, 'PAYMENT', 0, @paid, 'SALE', @sid, @invno, N'مدفوع مع الفاتورة', @uid, @date);
        END

        IF @total > @paid
            UPDATE dbo.Customers SET balance = balance + (@total - @paid), updated_at = @date WHERE customer_id = @cust;

        SET @k += 1;
    END
    SET @day -= 1;
END

/* ---------- Purchases (restocking, partly on credit) ---------- */
DECLARE @pid INT, @supplier INT;
DECLARE @purchases TABLE (no INT, days_ago INT, supplier NVARCHAR(30), paid_ratio DECIMAL(5,2));
INSERT INTO @purchases VALUES (1, 20, N'S-002', 1.00), (2, 6, N'S-001', 0.40), (3, 2, N'S-003', 0.00);
DECLARE @pno INT = 1;
WHILE @pno <= 3
BEGIN
    SELECT @day = days_ago, @supplier = s.supplier_id, @r = CAST(paid_ratio * 100 AS INT)
    FROM @purchases p JOIN dbo.Suppliers s ON s.supplier_code = p.supplier WHERE p.no = @pno;
    SET @date = DATEADD(HOUR, 10, CAST(DATEADD(DAY, -@day, @today) AS DATETIME2(0)));

    INSERT INTO dbo.Purchases (purchase_no, supplier_invoice_no, purchase_date, supplier_id, user_id, payment_method,
                               status, posted_at, posted_by, created_at)
    VALUES (CONCAT(N'PUR-', FORMAT(@date, 'yyMMdd'), N'-', @pno), CONCAT(N'SUP-', 7000 + @pno), @date, @supplier, @uid,
            CASE WHEN @r = 0 THEN 'CREDIT' ELSE 'BANK_TRANSFER' END, 'POSTED', @date, @uid, @date);
    SET @pid = SCOPE_IDENTITY();

    INSERT INTO dbo.Purchase_Items (purchase_id, product_id, quantity, unit_cost)
    SELECT @pid, p.product_id, v.qty, p.purchase_price
    FROM (VALUES (1, N'P-1001', 300), (1, N'P-1005', 400), (1, N'P-1006', 300),
                 (2, N'P-1003', 100), (2, N'P-1008', 40),  (2, N'P-1017', 60),
                 (3, N'P-1010', 10),  (3, N'P-1012', 8)) AS v (no, code, qty)
    JOIN dbo.Products p ON p.product_code = v.code
    WHERE v.no = @pno;

    SET @total = (SELECT SUM(line_total) FROM dbo.Purchase_Items WHERE purchase_id = @pid);
    SET @paid = ROUND(@total * @r / 100.0, 3);
    UPDATE dbo.Purchases SET subtotal = @total, total_amount = @total, paid_amount = @paid WHERE purchase_id = @pid;

    UPDATE p SET p.quantity = p.quantity + pi.quantity, p.updated_at = @date
    FROM dbo.Products p JOIN dbo.Purchase_Items pi ON pi.product_id = p.product_id WHERE pi.purchase_id = @pid;

    INSERT INTO dbo.Stock_Movements (product_id, movement_date, movement_type, quantity, balance_after, unit_cost,
                                     reference_type, reference_id, user_id)
    SELECT pi.product_id, @date, 'PURCHASE', pi.quantity, p.quantity, pi.unit_cost, 'PURCHASE', @pid, @uid
    FROM dbo.Purchase_Items pi JOIN dbo.Products p ON p.product_id = pi.product_id WHERE pi.purchase_id = @pid;

    IF @paid > 0
        INSERT INTO dbo.Cash_Transactions (transaction_date, transaction_type, amount, payment_method,
                                           source_type, source_id, description, user_id)
        VALUES (@date, 'OUT', @paid, 'BANK_TRANSFER', 'PURCHASE', @pid, N'سداد فاتورة مشتريات', @uid);
    INSERT INTO dbo.Account_Ledger (party_type, supplier_id, entry_date, entry_type, debit, credit,
                                    reference_type, reference_id, reference_no, description, user_id, created_at)
    SELECT 'SUPPLIER', @supplier, @date, 'PURCHASE', 0, @total, 'PURCHASE', @pid, purchase_no, N'فاتورة مشتريات', @uid, @date
    FROM dbo.Purchases WHERE purchase_id = @pid;
    IF @paid > 0
        INSERT INTO dbo.Account_Ledger (party_type, supplier_id, entry_date, entry_type, debit, credit,
                                        reference_type, reference_id, reference_no, description, user_id, created_at)
        SELECT 'SUPPLIER', @supplier, @date, 'PAYMENT', @paid, 0, 'PURCHASE', @pid, purchase_no, N'سداد مع الفاتورة', @uid, @date
        FROM dbo.Purchases WHERE purchase_id = @pid;
    IF @total > @paid
        UPDATE dbo.Suppliers SET balance = balance + (@total - @paid), updated_at = @date WHERE supplier_id = @supplier;

    SET @pno += 1;
END

/* ---------- One sale return (yesterday, one unit of a recent line) ---------- */
DECLARE @siid INT, @rqty DECIMAL(18,3), @rprice DECIMAL(18,3), @rsale INT, @rcust INT, @rprod INT, @retid INT;
SELECT TOP 1 @siid = si.sale_item_id, @rqty = 1, @rprice = si.unit_price, @rsale = s.sale_id,
             @rcust = s.customer_id, @rprod = si.product_id
FROM dbo.Sale_Items si
JOIN dbo.Sales s ON s.sale_id = si.sale_id
JOIN dbo.Units u ON u.unit_id = (SELECT unit_id FROM dbo.Products WHERE product_id = si.product_id)
WHERE s.payment_method IN ('CASH', 'KNET') AND u.allows_decimal = 0 AND si.quantity >= 1
  AND s.sale_date < CAST(@today AS DATETIME2(0)) AND s.sale_date >= DATEADD(DAY, -5, CAST(@today AS DATETIME2(0)))
ORDER BY si.unit_price DESC;

IF @siid IS NOT NULL
BEGIN
    SET @date = DATEADD(HOUR, 12, CAST(DATEADD(DAY, -1, @today) AS DATETIME2(0)));
    INSERT INTO dbo.Sale_Returns (return_no, sale_id, customer_id, return_date, total_amount, refund_amount,
                                  refund_method, cost_total, reason_code, reason, user_id, created_at)
    SELECT N'SRN-000001', @rsale, @rcust, @date, @rprice, @rprice, 'CASH', unit_cost, 'DEFECTIVE', N'عيب في المنتج',
           @uid, @date
    FROM dbo.Sale_Items WHERE sale_item_id = @siid;   -- same numbering as the app
    SET @retid = SCOPE_IDENTITY();
    INSERT INTO dbo.Sale_Return_Items (return_id, sale_item_id, product_id, quantity, unit_price, unit_cost)
    SELECT @retid, @siid, @rprod, @rqty, @rprice, unit_cost FROM dbo.Sale_Items WHERE sale_item_id = @siid;
    UPDATE dbo.Products SET quantity = quantity + @rqty, updated_at = @date WHERE product_id = @rprod;
    INSERT INTO dbo.Stock_Movements (product_id, movement_date, movement_type, quantity, balance_after, unit_cost,
                                     reference_type, reference_id, user_id)
    SELECT @rprod, @date, 'SALE_RETURN', @rqty, p.quantity, si.unit_cost, 'SALE_RETURN', @retid, @uid
    FROM dbo.Products p JOIN dbo.Sale_Items si ON si.sale_item_id = @siid WHERE p.product_id = @rprod;
    INSERT INTO dbo.Cash_Transactions (transaction_date, transaction_type, amount, payment_method,
                                       source_type, source_id, description, user_id)
    VALUES (@date, 'OUT', @rprice, 'CASH', 'SALE_RETURN', @retid, N'مرتجع مبيعات', @uid);
    -- A named customer's account shows the return and the cash refund (net zero)
    IF @rcust <> @cash
        INSERT INTO dbo.Account_Ledger (party_type, customer_id, entry_date, entry_type, debit, credit,
                                        reference_type, reference_id, reference_no, description, user_id, created_at)
        SELECT 'CUSTOMER', @rcust, @date, v.t, v.d, v.c, 'SALE_RETURN', @retid, return_no, v.descr, @uid, @date
        FROM dbo.Sale_Returns
        CROSS JOIN (VALUES ('SALE_RETURN', 0, @rprice, N'مرتجع مبيعات'),
                           ('PAYMENT', @rprice, 0, N'رد المبلغ نقدًا')) v (t, d, c, descr)
        WHERE return_id = @retid;
END

/* ---------- Customer payments (40% of each open balance, 3 days ago) ---------- */
SET @date = DATEADD(HOUR, 11, CAST(DATEADD(DAY, -3, @today) AS DATETIME2(0)));
INSERT INTO dbo.Customer_Payments (payment_no, customer_id, payment_date, amount, payment_method, user_id, notes, created_at)
SELECT CONCAT(N'RCV-', FORMAT(ROW_NUMBER() OVER (ORDER BY customer_id), '000000')),   -- same numbering as the app
       customer_id, @date, ROUND(balance * 0.4, 3), 'CASH', @uid, N'دفعة من الحساب', @date
FROM dbo.Customers WHERE balance > 10;

INSERT INTO dbo.Cash_Transactions (transaction_date, transaction_type, amount, payment_method, source_type, source_id, description, user_id)
SELECT @date, 'IN', amount, 'CASH', 'CUSTOMER_PAYMENT', payment_id, CONCAT(N'تحصيل من عميل - سند ', payment_no), @uid
FROM dbo.Customer_Payments;

INSERT INTO dbo.Account_Ledger (party_type, customer_id, entry_date, entry_type, debit, credit,
                                reference_type, reference_id, reference_no, description, user_id, created_at)
SELECT 'CUSTOMER', customer_id, @date, 'PAYMENT', 0, amount, 'CUSTOMER_PAYMENT', payment_id, payment_no,
       N'سند قبض', @uid, @date
FROM dbo.Customer_Payments;

UPDATE c SET c.balance = c.balance - cp.amount, c.updated_at = @date
FROM dbo.Customers c JOIN dbo.Customer_Payments cp ON cp.customer_id = c.customer_id;

/* ---------- Expenses ---------- */
INSERT INTO dbo.Expenses (expense_no, expense_date, expense_type, category, amount, payment_method, description, user_id)
SELECT CONCAT(N'EXP-', FORMAT(ROW_NUMBER() OVER (ORDER BY v.days_ago DESC), '000000')),   -- same numbering as the app
       DATEADD(HOUR, 13, CAST(DATEADD(DAY, -v.days_ago, @today) AS DATETIME2(0))),
       v.kind, v.category, v.amount, v.method, v.descr, @uid
FROM (VALUES
    (28, N'إيجار',     'RENT',        450.000, 'BANK_TRANSFER', N'إيجار المحل'),
    (25, N'رواتب',     'SALARIES',   1200.000, 'BANK_TRANSFER', N'رواتب الموظفين'),
    (21, N'نقل وتوصيل', 'TRANSPORT',    12.500, 'CASH',          N'توصيل طلبية مقاول'),
    (15, N'كهرباء',    'ELECTRICITY',  35.750, 'CASH',          N'فاتورة الكهرباء والماء'),
    (11, N'نقل وتوصيل', 'TRANSPORT',     8.000, 'CASH',          N'توصيل'),
    (4,  N'صيانة',     'MAINTENANCE',  18.000, 'CASH',          N'صيانة مكيف المعرض'),
    (2,  N'نقل وتوصيل', 'TRANSPORT',    15.250, 'CASH',          N'توصيل طلبية الجهراء'),
    (0,  N'أخرى',      'OTHER',         3.500, 'CASH',          N'ضيافة')
) AS v (days_ago, kind, category, amount, method, descr);

INSERT INTO dbo.Cash_Transactions (transaction_date, transaction_type, amount, payment_method, source_type, source_id, description, user_id)
SELECT expense_date, 'OUT', amount, payment_method, 'EXPENSE', expense_id, CONCAT(N'مصروف ', expense_no, N' - ', description), @uid
FROM dbo.Expenses;

COMMIT TRANSACTION;

SELECT (SELECT COUNT(*) FROM dbo.Products) AS products, (SELECT COUNT(*) FROM dbo.Sales) AS sales,
       (SELECT COUNT(*) FROM dbo.Purchases) AS purchases, (SELECT COUNT(*) FROM dbo.Expenses) AS expenses,
       (SELECT COUNT(*) FROM dbo.Products WHERE quantity <= minimum_stock) AS low_stock;
PRINT N'Demo data loaded.';
GO
