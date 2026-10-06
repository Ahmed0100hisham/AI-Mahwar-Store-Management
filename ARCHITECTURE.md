# Architecture — نظام إدارة شركة المحور

هذا الملف يشرح كيف بُني النظام حاليًا، والقواعد التي تحافظ على قابليته للتوسع، وخطة الانتقال إلى
**Backend REST API** يخدم تطبيق **JavaFX** وتطبيق **Flutter** معًا.

---

## 1. الوضع الحالي

```
JavaFX (FXML + Controllers)
        │   يطلب الخدمات من AppContext فقط
        ▼
Service Layer   ← منطق العمل + الصلاحيات (Java فقط، بدون JavaFX)
        │
        ▼
DAO Layer       ← كل استعلامات SQL (JDBC + PreparedStatement)
        │
        ▼
SQL Server
```

التطبيق حاليًا برنامج Desktop واحد يتصل بقاعدة البيانات مباشرة عبر JDBC. لا يوجد Spring Boot ولا API بعد،
لكن الطبقات مفصولة بحيث يمكن نقل **Model / DAO / Service** كما هي إلى الـ API لاحقًا.

### الطبقات ومسؤولياتها

| الطبقة | Package | مسؤوليتها | ممنوع فيها |
|---|---|---|---|
| **Model** | `model` | بيانات النظام (Product, Customer, User…) و Records للوحة التحكم. POJOs/Records عادية | JavaFX، SQL، استدعاء Services |
| **DAO** | `dao` | كل SQL. تحويل الصفوف إلى Models. المعاملات عبر `TransactionManager` | JavaFX، منطق العمل، الصلاحيات |
| **Service** | `service` | منطق العمل، التحقق، الصلاحيات، تسجيل الدخول، الحسابات (صافي الربح…) | JavaFX، SQL مباشر، تنسيق العرض |
| **Controller** | `controller` | ربط الشاشات (FXML) بالخدمات، التنسيق والعرض فقط | SQL، DAO، `DatabaseConnection`، منطق العمل |
| **Controller support** | `controller.support` | أدوات الواجهة: `Navigator`, `ViewLoader`, `AlertUtil`, `Icons`, `DashboardCards` | — (جزء من طبقة الواجهة) |
| **Util** | `util` | أدوات Java عامة: `MoneyUtil`, `QuantityUtil`, `PasswordHasher` | JavaFX، DAO، Services |
| **Config** | `config` | الإعدادات (`AppConfig`)، الاتصال (`DatabaseConnection`)، وربط المكونات (`AppContext`) | JavaFX |

هذه القواعد **مفروضة آليًا** في `src/test/java/com/almahwar/ArchitectureTest.java`؛ أي مخالفة تُفشل الـ Build:
- لا JavaFX داخل `model`, `dao`, `service`, `config`, `util`.
- لا `java.sql` ولا `dao` ولا `DatabaseConnection` داخل `controller`.
- الاعتماديات تتجه للداخل فقط: Controller → Service → DAO → Model.

### `AppContext` — نقطة الربط الوحيدة (Composition Root)

```java
AuthService auth          = AppContext.get().auth();
DashboardService dashboard = AppContext.get().dashboard();
SecurityContext security   = AppContext.get().security();
```

- الـ Controllers **لا تنشئ** أي Service بنفسها (`new ...Service()` ممنوع)، بل تطلبها من `AppContext`.
- كل Service يستخدمها الواجهة لها **Interface** وتنفيذ حالي:

| Interface | التنفيذ الحالي | التنفيذ المستقبلي (مثال) |
|---|---|---|
| `AuthService` | `AuthServiceImpl` (SQL Server عبر DAO) | `RestAuthService` (`POST /api/auth/login`) |
| `DashboardService` | `DashboardServiceImpl` | `RestDashboardService` (`GET /api/dashboard`) |
| `SystemStatusService` | `SystemStatusServiceImpl` (فحص الاتصال بالقاعدة) | فحص الاتصال بالـ API |
| `CatalogService` | `CatalogServiceImpl` (الأقسام، الماركات، الوحدات) | `GET/POST /api/categories` … |
| `ProductService` | `ProductServiceImpl` | `GET /api/products?search=…`, `POST /api/products` |
| `InventoryService` | `InventoryServiceImpl` | `POST /api/inventory/adjustments`, `GET /api/inventory/movements` |
| `CustomerService` | `CustomerServiceImpl` | `GET /api/customers`, `GET /api/customers/{id}/statement` |
| `SupplierService` | `SupplierServiceImpl` | `GET /api/suppliers`, `GET /api/suppliers/{id}/statement` |
| `PurchaseService` | `PurchaseServiceImpl` | `POST /api/purchases`, `POST /api/purchases/{id}/post` |
| `SaleService` | `SaleServiceImpl` | `POST /api/sales`, `POST /api/sales/{id}/post`, `GET /api/sales` |
| `PaymentService` | `PaymentServiceImpl` | `POST /api/customers/{id}/payments`, `POST /api/suppliers/{id}/payments` |
| `ExpenseService` | `ExpenseServiceImpl` | `GET/POST /api/expenses` |
| `CashboxService` | `CashboxServiceImpl` | `GET /api/cashbox`, `POST /api/cashbox/deposits`, `POST /api/cashbox/withdrawals` |
| `ReturnService` | `ReturnServiceImpl` | `POST /api/sales/{id}/returns`, `POST /api/purchases/{id}/returns`, `GET /api/returns` |
| `SecurityContext` | `SessionManager` (مستخدم واحد لكل برنامج) | في الـ API: سياق لكل Request من الـ Token |

عند بناء الـ API، تغيير JavaFX من "قاعدة البيانات مباشرة" إلى "عبر الـ API" يكون في **`AppContext` فقط**،
دون تعديل أي Controller أو شاشة.

---

## 2. المصادقة والجلسة (Authentication & Session)

### الآن

```
LoginController ──► AuthService.login(username, password)          [AuthServiceImpl]
                       ├─ UserDao.findByUsername                    (SQL Server)
                       ├─ PasswordHasher.verify (PBKDF2-SHA256)
                       ├─ قفل الحساب بعد المحاولات الخاطئة (Users.failed_login_attempts / locked_until)
                       ├─ AuditLogDao.log(LOGIN …)
                       └─ SessionManager.start(user)  ──► UserSession(user, permissions)
```

- **`SecurityContext`** (Interface): "من المستخدم الحالي وما صلاحياته؟". الخدمات تعتمد عليه وليس على
  `SessionManager` مباشرة، لذلك يمكن تشغيل نفس منطق الخدمات داخل API متعدد المستخدمين.
- **`UserSession`**: المستخدم + صلاحياته + وقت الدخول. لا يحتفظ بالـ password hash.
- **`RolePermissions`**: مصفوفة الأدوار ← الصلاحيات (ADMIN, CASHIER, STOREKEEPER, ACCOUNTANT).
- **`CredentialPolicy`**: قواعد اسم المستخدم وكلمة المرور، مشتركة بين كل الواجهات.
- التحقق من الصلاحية يتم **داخل الـ Service** (`security.requirePermission(...)`)، وإخفاء عناصر القائمة في
  الواجهة هو طبقة إضافية فقط.

### مستقبلًا (API Authentication)

```
JavaFX / Flutter ──► POST /api/auth/login ──► AuthServiceImpl (نفس المنطق على الخادم)
                                                 └─ يُصدر Access Token (مثل JWT) بدل SessionManager
       ◄── { token, user, permissions }

كل طلب لاحق:  Authorization: Bearer <token>
API ──► يبني SecurityContext للطلب من الـ Token ──► DashboardServiceImpl / … (بدون تعديل)
```

- في JavaFX: `RestAuthService` يحفظ الـ Token داخل الجلسة، و`RestDashboardService` يرسله مع كل طلب.
- قفل الحساب والـ Audit Log وتشفير كلمات المرور تبقى كما هي لأنها في الـ Service/DAO على الخادم.

---

## 3. Models و REST API

- كل القيم المالية `BigDecimal` بثلاث منازل عشرية (`MoneyUtil`) و`DECIMAL(18,3)` في SQL Server.
  الكميات أيضًا `BigDecimal` (`QuantityUtil`) لأن المواسير تباع بالمتر.
- Models هي POJOs (getters/setters + constructor فارغ) أو Java Records، بدون أي اعتماد على JavaFX،
  لذلك يمكن تحويلها إلى JSON مباشرة (Jackson) عند بناء الـ API.
- الـ Services تُرجع **قيمًا خام** وليس نصوصًا منسقة؛ مثال: `DashboardService.load()` تُرجع
  `MetricValue(metric, BigDecimal value, BigDecimal previousValue)`، والتنسيق ("1,250.750 د.ك"، الألوان،
  "صافي الخسارة") في `controller.support.DashboardCards`. تطبيق Flutter سيستقبل نفس القيم وينسقها بطريقته.
- الـ Service ترشّح البيانات حسب الصلاحيات قبل إرجاعها؛ العميل لا يستلم أرقامًا لا يحق له رؤيتها.

**إرشادات عند بناء الـ API:**
- لا تُرسل `User.passwordHash` أبدًا في JSON؛ استخدم DTO للمستخدم (أو `@JsonIgnore`).
- أرسل المبالغ كنص (`"1250.750"`) أو رقم عشري دقيق، وليس `double`، حتى لا تضيع الفلوس.
- التواريخ بصيغة ISO-8601 (`2026-10-05T14:30:00`)، و"اليوم/الشهر" تُحسب بتاريخ خادم قاعدة البيانات
  (كما هو الحال الآن في `DashboardDao`).

---

## 4. الخطة المستقبلية

### الهدف

```
Flutter App            JavaFX Desktop
     │                       │
     └──────────┬────────────┘
                ▼
          REST API  (Backend)
                ▼
          Service Layer   ← نفس الكود الحالي
                ▼
          DAO Layer       ← نفس الكود الحالي
                ▼
          SQL Server
```

### مراحل الانتقال المقترحة (عندما يحين الوقت)

1. **فصل الكود المشترك إلى Module**: تحويل المشروع إلى Maven multi-module:
   - `almahwar-core`: `model`, `dao`, `service`, `util`, `config` (بدون JavaFX — جاهزة الآن بفضل القواعد أعلاه).
   - `almahwar-desktop`: `controller`, FXML, CSS, `MainApp`.
   - `almahwar-api`: الـ Backend.
2. **بناء الـ API** (Spring Boot أو غيره) فوق `almahwar-core`:
   - Endpoints مثل: `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/dashboard?range=LAST_30_DAYS`,
     `GET /api/dashboard/sales-trend?range=…`.
   - `SecurityContext` لكل Request من الـ Token، وConnection pool (مثل HikariCP) بدل `DriverManager`.
3. **ربط JavaFX بالـ API**: كتابة `RestAuthService`, `RestDashboardService`, … وتبديلها في `AppContext`.
4. **تطبيق Flutter**: يستخدم نفس الـ Endpoints ونفس الصلاحيات.

حتى ذلك الحين يبقى التطبيق يعمل مباشرة مع SQL Server كما هو.

---

## 5. المنتجات والمخزون (المرحلة 5)

```
ProductsController / ProductFormController ──► ProductService ──► ProductDao ─┐
MasterDataPane (أقسام/ماركات/وحدات)        ──► CatalogService ──► Category/Brand/UnitDao
InventoryController                         ──► InventoryService ─┐            │
                                                                   ▼            ▼
                                                      StockLedger.post(con, …) ──► StockMovementDao
                                                      (كمية المنتج + حركة المخزون في نفس الـ Transaction)
```

- **`StockLedger`** هو الطريق الوحيد لتغيير الكمية: `UPDATE … OUTPUT` ذري يرفض الرصيد السالب، ثم يُكتب سطر
  `Stock_Movements` (قبل/الكمية/بعد/المستخدم/السبب/المرجع) على نفس الاتصال. المبيعات والمشتريات والمرتجعات
  في المراحل القادمة تستدعيه داخل معاملتها: `ledger.post(con, productId, MovementType.SALE, qty, "SALE", saleId, null, userId)`.
- `ProductService.create` يحفظ المنتج بكمية 0 ثم يسجل `OPENING_BALANCE` في نفس المعاملة؛ `update` لا يلمس الكمية.
- **`ValidationException`**: رسالة عربية لكل حقل (`getErrors()`)؛ الشاشة تعرضها تحت الحقل، والـ API لاحقًا يرجعها كـ 422.
- الصلاحيات: `PRODUCTS_VIEW` (عرض)، `PRODUCTS` (إدارة)، `PRODUCT_COST` (سعر الشراء — يُرجَع `null` لغيره)،
  `INVENTORY` (الأرصدة والسجل)، `INVENTORY_ADJUST` (التسوية). كلها تُفحص داخل الـ Service.
- قاعدة البيانات تحمي نفسها أيضًا: `CK_Products_quantity_non_negative`، و`quantity_before` عمود محسوب
  (`balance_after - quantity`) فلا يمكن أن يتعارض مع الرصيد بعد الحركة.

---

## 5b. العملاء والموردون ودفتر الحسابات (المرحلة 6)

```
CustomersController / CustomerDetails / CustomerForm ──► CustomerService ──► CustomerDao ─┐
SuppliersController / SupplierDetails / SupplierForm ──► SupplierService ──► SupplierDao ─┤
                                        AccountStatementPane ◄── statement()               │
                                                                                           ▼
                           AccountLedger.post(con, …) ──► AccountLedgerDao (Account_Ledger)
                           + CustomerDao/SupplierDao.adjustBalance (الرصيد المخزن) في نفس الـ Transaction
```

**قرار المحاسبة:** الرصيد مصدره الوحيد هو `Account_Ledger` (جدول موحد للعملاء والموردين بمفتاحين أجنبيين حقيقيين
`customer_id` / `supplier_id` و CHECK يربط كل نوع قيد بنوع الطرف). العمود `balance` في Customers/Suppliers
**Cached Balance** فقط: يُكتب حصريًا من `AccountLedger.post` داخل نفس المعاملة التي تكتب القيد، و`update()` في
الـ DAO لا يلمسه. سبب الإبقاء عليه: لوحة التحكم والفلاتر و POS (فحص الائتمان) تحتاجه سريعًا دون جمع كل القيود.
المراجعة: `balanceMismatches()` تقارن الرصيد المخزن بمجموع القيود ويجب أن تكون فارغة دائمًا (مختبرة).

- العميل: الرصيد = Σ(مدين − دائن) (البيع مدين، التحصيل والمرتجع دائن). المورد: الرصيد = Σ(دائن − مدين).
- القيود لا تُعدّل ولا تُحذف؛ التصحيح يكون بقيد `ADJUSTMENT` جديد، فكشف الحساب قابل لإعادة الإنتاج دائمًا.
- كشف الحساب: الرصيد الجاري يُحسب على كل تاريخ الحساب (`SUM() OVER`) ثم تُطبق فلاتر التاريخ والبحث، فكل
  سطر يعرض الرصيد الحقيقي في وقته، و"رصيد سابق" = مجموع ما قبل تاريخ البداية.
- المراحل القادمة تستدعي نفس الخدمة: البيع الآجل `ledger.post(con, CUSTOMER, id, SALE, total, "SALE", saleId, invoiceNo, …)`
  ثم الدفعة `PAYMENT` بقيمة سالبة، و POS يستخدم `customerService.checkCredit(...)` و`requireActive(...)`.
- الصلاحيات: `CUSTOMERS_VIEW/EDIT`, `CUSTOMER_BALANCE_VIEW`, `SUPPLIERS_VIEW/EDIT`, `SUPPLIER_BALANCE_VIEW`؛
  الأرصدة تُرجع `null` لمن لا يملك صلاحية الرصيد.

---

## 5c. المشتريات (المرحلة 7)

```
PurchasesController / PurchaseFormController / PurchaseDetailsController / PurchasePrintPage
        │  (PurchasePages: نفس الصفحات تُفتح من وحدة المشتريات ومن تبويب مشتريات المورد)
        ▼
PurchaseService.saveAndPost / post ── TransactionManager.inTransaction(con ->
        ├─ PurchaseDao.insert + replaceItems            (رقم PUR-xxxxxx بقفل UPDLOCK)
        ├─ PurchaseDao.lockStatus + markPosted           (DRAFT → POSTED مرة واحدة فقط)
        ├─ StockLedger.post(…, PURCHASE, qty, netUnitCost, "PURCHASE", id …)   لكل سطر
        ├─ CostingPolicy → ProductDao.updatePurchasePrice                         لكل سطر
        ├─ AccountLedger.post(SUPPLIER, PURCHASE, +total) و PAYMENT(−paid)
        ├─ CashTransactionDao.insert(OUT, paid, method, "PURCHASE", id)           إن وُجد مدفوع
        └─ AuditLogDao.log(CREATE_PURCHASE / POST_PURCHASE)
   ) ← أي استثناء = ROLLBACK كامل
```

- **الحالات:** `DRAFT` لا يؤثر على شيء ويمكن تعديله؛ `POSTED` نهائي (كل كتابات الـ DAO مشروطة بـ `status = 'DRAFT'`)؛
  `CANCELLED` مسودة ملغاة محفوظة. تصحيح فاتورة معتمدة سيكون بمرتجع مشتريات (قيود عكسية)، لا بالتعديل أو الحذف.
- **الدفع (`PaymentRules`، قابل لإعادة الاستخدام في POS):** فوري = المدفوع كامل الإجمالي، آجل = 0، جزئي = 0 < المدفوع < الإجمالي.
  `payment_status` عمود محسوب في SQL Server (PAID / PARTIAL / UNPAID).
- **التكلفة (`CostingPolicy`):** السياسة الحالية "آخر تكلفة شراء" (صافي تكلفة الوحدة بعد خصم السطر). خصم الفاتورة
  الإجمالي يُعامل كخصم مالي لا يُوزَّع على الأصناف. `WEIGHTED_AVERAGE` جاهز ويُفعَّل بتغيير سطر في `AppContext`.
  `Purchase_Items` و `Stock_Movements.unit_cost` يحفظان التكلفة التاريخية دائمًا.
- **منع التكرار:** تعطيل الأزرار أثناء الحفظ + `request_id` فريد لكل فتح للنموذج (إعادة الطلب تُرجع نفس الفاتورة)
  + `UPDLOCK` على صف الفاتورة عند الاعتماد + فهرس فريد لرقم فاتورة المورد لكل مورد (عدا الملغاة).
- **الصلاحيات:** `PURCHASES_VIEW`, `PURCHASES_CREATE`, `PURCHASES_POST`, `PURCHASE_COST_VIEW`.

---

## 5d. نقطة البيع والمبيعات (المرحلة 8)

```
PosController (pos.fxml) / SalesController / SaleDetailsController / SalePrintPage
        │  (SalePages: نفس الصفحات تُفتح من نقطة البيع، ووحدة المبيعات، وتبويب فواتير العميل)
        ▼
SaleService.saveAndPost / post ── TransactionManager.inTransaction(con ->
        ├─ SaleDao.insert + replaceItems                 (رقم SAL-xxxxxx بقفل UPDLOCK, HOLDLOCK)
        ├─ SaleDao.lockStatus                            (UPDLOCK على الفاتورة: DRAFT فقط)
        ├─ CustomerDao.lockForSale                       (UPDLOCK على العميل: نشط + حد الائتمان)
        ├─ ProductDao.lockForSale                        (UPDLOCK على الأصناف بترتيب product_id: نشط، السعر، المخزون)
        ├─ SaleDao.setItemCost                           (التكلفة التاريخية = تكلفة الصنف الحالية)
        ├─ StockLedger.post(…, SALE, qty, unitCost, "SALE", id …)       لكل سطر (يرفض المخزون السالب أيضًا)
        ├─ AccountLedger.post(CUSTOMER, SALE, +total) و PAYMENT(−paid)  (لا شيء للعميل النقدي)
        ├─ CashTransactionDao.insert(IN, paid, method, "SALE", id)       إن وُجد مدفوع
        ├─ SaleDao.markPosted(cost_total)                (DRAFT → POSTED مرة واحدة فقط)
        └─ AuditLogDao.log(CREATE_SALE / POST_SALE / PRICE_OVERRIDE / CREDIT_OVERRIDE)
   ) ← أي استثناء = ROLLBACK كامل
```

- **التزامن:** ترتيب الأقفال ثابت لكل عملية بيع (الفاتورة ← العميل ← الأصناف تصاعديًا) فلا يحدث Deadlock بين
  عمليتي بيع. عمليتا بيع لآخر قطعة: الثانية تنتظر قفل الصنف، ثم ترى الرصيد 0 فتُرفض بـ `InsufficientStockException`
  (المتوفر والمطلوب). حد الائتمان يُفحص بعد قفل صف العميل، فلا تُقبل فاتورتان آجلتان على نفس الرصيد القديم.
- **ترتيب قفل الأصناف موحّد:** كل عملية تغيّر مخزون أكثر من صنف (المبيعات والمشتريات، ولاحقًا المرتجعات) تقفل كل
  أصنافها أولًا عبر `ProductDao.lockForStockChange` بترتيب `product_id` تصاعديًا، ثم تطبّق السطور بنفس الترتيب،
  مهما كان ترتيب السطور في الشاشة. لذلك لا يحدث Deadlock بين بيع وشراء على نفس الأصناف.
- **حماية السلة غير المحفوظة:** `Navigator` يمرّر كل تنقل (القائمة الجانبية، الرئيسية، تسجيل الخروج، انتهاء الجلسة،
  إغلاق النافذة، الرجوع من نقطة البيع) عبر `Navigator.leave(...)`. نقطة البيع تسجّل نفسها كـ `LeaveGuard` ما دامت
  معروضة، فتعرض: إتمام البيع، أو تعليق الفاتورة كمسودة، أو البقاء، أو مسح السلة بعد تأكيد. عند انتهاء الجلسة بسبب
  عدم النشاط تُعلَّق السلة كمسودة تلقائيًا قبل تسجيل الخروج.
- **الحالات:** `DRAFT` سلة معلّقة بلا أي تأثير؛ `POSTED` نهائية (كل كتابات الـ DAO مشروطة بـ `status = 'DRAFT'`)؛
  `CANCELLED` مسودة ملغاة. تصحيح فاتورة معتمدة سيكون بمرتجع مبيعات لاحقًا.
- **الدفع:** نفس `PaymentRules` الخاصة بالمشتريات. العميل النقدي (`CASH`) يدفع بالكامل فقط.
- **التكلفة والربح:** `Sale_Items.unit_cost` تُثبَّت لحظة الاعتماد من `Products.purchase_price` (التي تحافظ عليها
  `CostingPolicy`). `Sales.cost_total` = Σ الكمية × التكلفة (كل سطر مقرّب لـ 3 منازل)، و`gross_profit` عمود محسوب =
  الإجمالي − التكلفة؛ أي أن خصم السطر وخصم الفاتورة كلاهما ينقص الربح. شراء لاحق لا يغيّر ربح فاتورة قديمة.
- **منع التكرار:** تعطيل زر الإتمام و F10 أثناء الحفظ + `request_id` فريد لكل سلة + قفل الفاتورة + `markPosted` مرة واحدة.
- **الصلاحيات:** `SALES_VIEW`, `SALES_CREATE`, `SALES_POST`, `SALES_COST_VIEW`, `SALES_PROFIT_VIEW`,
  `SALES_PRICE_OVERRIDE`, `SALES_DISCOUNT`, `CUSTOMER_CREDIT_OVERRIDE` — والخدمة تعيد كل الفحوص ولا تثق في الشاشة.

---

## 5e. العمليات المالية (المرحلة 9)

لا يوجد نظام مالي ثانٍ: كل العمليات تستخدم نفس `AccountLedger` (أرصدة العملاء والموردين) ونفس `Cash_Transactions`
(الخزنة) اللذين تستخدمهما المبيعات والمشتريات.

```
PartyPaymentController (من صفحة العميل / المورد)  ExpensesController        CashboxController
        ▼                                          ▼                         ▼
PaymentService.record                     ExpenseService.create      CashboxService.deposit / withdraw
  TransactionManager.inTransaction(con ->   inTransaction(con ->       inTransaction(con ->
    CustomerDao/SupplierDao.lockForUpdate     ExpenseDao.nextNumber      CashTransactionDao.lockCashbox (sp_getapplock)
    (المبلغ ≤ المستحق، لا دفع زائد)           ExpenseDao.insert          (السحب ≤ الرصيد)
    PaymentDao.insert (RCV / PAY)             CashTransactionDao OUT     CashTransactionDao IN / OUT
    AccountLedger.post(PAYMENT, −amount)      AuditLog)                  AuditLog)
    CashTransactionDao IN / OUT (نفس التاريخ)
    AuditLog)
```

- **الرصيد لا يُعدَّل مباشرة أبدًا:** رصيد العميل/المورد يتغير فقط عبر `AccountLedger.post`، ورصيد الخزنة = Σ الوارد − Σ الصادر.
- **منع التكرار:** `request_id` فريد في `Customer_Payments` و `Supplier_Payments` و `Expenses` و `Cash_Transactions`،
  ويُعاد فحصه داخل المعاملة بعد القفل، فإعادة نفس الطلب (حتى بالتزامن) تُرجع العملية الأولى.
- **التزامن:** قفل صف العميل/المورد يجعل دفعتين متزامنتين تُفحصان واحدة بعد الأخرى؛ السحب اليدوي مقفول بـ application lock.
- **التاريخ:** العملية بتاريخ اليوم تأخذ وقت الخادم؛ التاريخ السابق يُحفظ الساعة 12:00؛ التاريخ المستقبلي مرفوض. قيد الحساب
  وحركة الخزنة يأخذان نفس تاريخ السند، فيظهر كشف الحساب بالترتيب الزمني الصحيح.
- **الصلاحيات:** `CUSTOMER_PAYMENTS`, `SUPPLIER_PAYMENTS`, `EXPENSES`, `CASH`, `CASH_ADJUST`.

---

## 5f. المرتجعات (المرحلة 10)

```
ReturnsController / ReturnFormController / ReturnDetailsPage  (+ زر "إنشاء مرتجع" في تفاصيل الفاتورة)
        ▼
ReturnService.create ── TransactionManager.inTransaction(con ->
   مرتجع مبيعات:  SaleDao.lockStatus (POSTED) → الكميات المتبقية → CustomerDao.lockForUpdate (ليس للنقدي)
                  → ProductDao.lockForStockChange (product_id ASC) → ReturnDao (SRN) → StockLedger SALE_RETURN
                  (بتكلفة سطر البيع) → AccountLedger SALE_RETURN (+ PAYMENT إن رُد مال) → Cash OUT إن رُد مال → Audit
   مرتجع مشتريات: PurchaseDao.lockStatus (POSTED) → الكميات المتبقية → ProductDao.lockForStockChange + فحص المخزون
                  → SupplierDao.lockForUpdate → ReturnDao (PRN) → StockLedger PURCHASE_RETURN → AccountLedger
                  PURCHASE_RETURN (+ PAYMENT إن استُرد مال) → Cash IN إن استُرد مال → Audit
   ) ← أي استثناء = ROLLBACK كامل
```

- **ترتيب الأقفال** هو نفس ترتيب اعتماد المستند الأصلي (بيع: الفاتورة ← العميل ← الأصناف؛ شراء: الفاتورة ← الأصناف ← المورد)،
  فلا يحدث Deadlock مع البيع والشراء والتحصيل. قفل الفاتورة الأصلية يجعل مرتجعين لنفس الفاتورة يُفحصان واحدًا بعد الآخر.
- **القيمة:** صافي سعر الوحدة كما فوتر (بعد خصم السطر وحصته من خصم الفاتورة، مقربًا لأسفل)، ولا تتجاوز ما تبقى من إجمالي الفاتورة.
- **التسوية:** القيمة تُخصم أولًا من رصيد الطرف؛ ما يزيد فقط يتحرك نقدًا (`RefundPlan`). العميل النقدي: كل القيمة نقدًا وبلا قيود.
- **التكلفة والربح:** `Sale_Return_Items.unit_cost` = تكلفة سطر البيع الأصلي (لا سعر الشراء الحالي)، و`Sale_Returns.cost_total`
  يُطرح مع القيمة من الربح الإجمالي في الـ Dashboard.
- **منع التكرار:** `request_id` فريد، ويُعاد فحصه داخل المعاملة بعد قفل الفاتورة الأصلية.
- **الصلاحيات:** `SALE_RETURNS`, `PURCHASE_RETURNS`.

## 5g. عروض الأسعار (المرحلة 11)

```
QuotationsController / QuotationFormController / QuotationDetailsController / QuotationPrintPage
   (+ تبويب "عروض الأسعار" في صفحة العميل — QuotationPages يربط الصفحات بالمضيف)
        ▼
QuotationService.save / send / accept / reject / reopen / deleteDraft
   ── TransactionManager.inTransaction(con -> QuotationDao.lockStatus (UPDLOCK) → تغيير الحالة → Audit)
   (لا StockLedger ولا AccountLedger ولا Cash: العرض لا يحرك شيئًا)
QuotationService.convert
   1) sale حي موجود؟ ← يُعاد كما هو (alreadyMade)
   2) قفل العرض: ACCEPTED وغير منتهٍ
   3) SaleService.saveDraft(… quotationId …)  ← نفس تحقق المبيعات؛ UX_Sales_quotation يمنع فاتورة ثانية
   4) linkSale + QUOTATION_CONVERSION_STARTED
SaleServiceImpl.applyPosting (عند إتمام البيع في نقطة البيع)
   … markPosted → QuotationDao.markConverted (ACCEPTED ∧ صالح) وإلا ValidationException = ROLLBACK كامل
```

- **الحياد المالي:** إنشاء العرض وإرساله وقبوله لا يكتب أي حركة مخزون أو قيد أو حركة خزنة أو مبيعات (اختبار تكامل يقارن
  الكميات والأرصدة والخزنة وعدد المبيعات والربح قبل وبعد).
- **الانتهاء بلا Scheduler:** `expireOverdue()` (UPDATE … OUTPUT) قبل كل قراءة أو تغيير حالة، ويُسجَّل `QUOTATION_EXPIRED`.
- **السعر المتفق عليه:** `SaleServiceImpl` يقبل سعر بند العرض المرتبط دون `SALES_PRICE_OVERRIDE` (في التحقق المبكر وداخل
  معاملة الاعتماد)، ويسجله في Audit كـ `PRICE_OVERRIDE` مع رقم العرض. فرق السعر الحالي أو نقص المخزون يظهر تحذيرًا في نقطة البيع.
- **المسودة والمخزون:** مسودة البيع لا ترفض بسبب نقص المخزون (لا تحرك شيئًا)، والاعتماد يرفض كالمعتاد.
- **منع التكرار والتزامن:** الترقيم `MAX … WITH (UPDLOCK, HOLDLOCK)`؛ `request_id` فريد للعرض وللفاتورة؛ `UX_Sales_quotation`
  (فاتورة حية واحدة لكل عرض)؛ قفل صف العرض لتغيير الحالة؛ تحويل العرض داخل معاملة اعتماد البيع (قفل الفاتورة أولًا ثم العرض).
- **ترتيب الأقفال:** عمليات العرض تقفل صف العرض فقط؛ اعتماد البيع: الفاتورة ← العميل ← الأصناف ← العرض، ولا تقرأ عمليات
  العرض جدول المبيعات وهي تحمل قفل العرض، فلا Deadlock.
- **الصلاحيات:** `QUOTATIONS_VIEW`, `QUOTATIONS_CREATE`, `QUOTATIONS_EDIT`, `QUOTATIONS_SEND`, `QUOTATIONS_ACCEPT`,
  `QUOTATIONS_CONVERT`, `QUOTATIONS_DISCOUNT`, `QUOTATIONS_PRICE_OVERRIDE`.

## 5h. التقارير والتحليلات (المرحلة 12)

```
ReportsController (الصفحة الرئيسية: تبويب لكل مجموعة) → ReportViewPage (فلاتر + بطاقات + جدول + طباعة + تصدير)
        ▼                                                 ReportPrintPage (صفحات A4 أفقية)
ReportService (+Impl)   ← الصلاحيات، التحقق من الفلاتر، حذف التكلفة/الربح من البيانات نفسها
   ├─ sales / profit / purchases / expenses / cashbox / inventory / … → DTOs مكتوبة (model.Reports.*)
   ├─ table(type, filter)      → ReportTable عام (ReportTables) للشاشة والطباعة
   └─ exportXlsx(type, filter) → XlsxReportWriter (Apache POI, SXSSF) — يحتاج REPORTS_EXPORT
        ▼
ReportDao  ← SELECT فقط؛ التجميع والفلترة والترقيم (OFFSET/FETCH) في SQL Server
```

- **للقراءة فقط:** لا يكتب أي استعلام في `ReportDao`؛ حالة "منتهي الصلاحية" للعروض تُحسب داخل الاستعلام (لا `expireOverdue`).
  اختبار تكامل يقارن عدد الصفوف و`CHECKSUM_AGG` لكل الجداول قبل وبعد فتح وتصدير كل التقارير.
- **الملخص = التفاصيل:** كل ملخص يُحسب بنفس `FROM / WHERE` المستخدم في صفوف التفاصيل (اختبار Σ التفاصيل = الملخص).
- **التواريخ:** أيام شاملة على تقويم الخادم، `col >= from AND col < to + 1` (تستخدم فهارس التاريخ الموجودة).
- **سياسة المرتجعات:** المستند بتاريخ اعتماده والمرتجع بتاريخ المرتجع (مبيعات ومشتريات).
- **التكلفة التاريخية:** `Sales.cost_total` و`Sale_Returns.cost_total` (تكلفة سطر البيع الأصلي)، ولا يُستخدم `Products.purchase_price`
  إلا لتقييم المخزون الحالي.
- **لوحة التحكم:** `DashboardStats` يحمل الإجمالي والمرتجعات، وبطاقات المبيعات تعرض الصافي بنفس قواعد التقرير.
- **الصلاحيات:** `REPORTS_*` (انظر README)؛ `ReportType` يحدد صلاحية كل تقرير وفلاتره، ولا يعتمد الـ Service على اسم الدور.

## 5i. الإعدادات وبيانات الشركة (المرحلة 13A)

```
SettingsController (settings.fxml: بيانات الشركة / إعدادات النظام / حول البرنامج) ── LeaveGuard (حفظ / تجاهل / بقاء)
        ▼
SettingsService (+Impl) ── الصلاحيات، التحقق، معاملة واحدة للحفظ، Audit
   company() / system() / logo()  ← أي مستخدم مسجّل (للطباعة)
   load() / about()               ← SETTINGS_VIEW
   save() / changeLogo() / removeLogo() ← SETTINGS_EDIT
        ▼
SettingsDao ── System_Settings (key/value) · Company_Logo (صف واحد، VARBINARY) · Schema_Info
CompanyHeader (controller) ← ترويسة موحدة لطباعة الفاتورة والمشتريات وعرض السعر والتقارير
```

- **التخزين:** key/value بسيط في `System_Settings` (لا يحتاج أعمدة جديدة لكل إعداد)، والقيم الافتراضية في السكربت وفي
  `SettingsServiceImpl.DEFAULTS` (احتياط لقاعدة لم تُرقَّ بعد).
- **الشعار في قاعدة البيانات** وليس ملفًا: لا مسارات مطلقة، ويظهر على كل الأجهزة المتصلة بنفس الخادم.
- **لا Cache:** القراءة استعلام صغير عند كل طباعة، فتظهر التعديلات فورًا دون إعادة تشغيل.
- **إصدار المخطط:** `Schema_Info` (1.8.0 في هذه المرحلة؛ 1.9.0 في 13B؛ حاليًا 1.10.0 بعد 13C)، والبرنامج يطلب `SettingsService.REQUIRED_SCHEMA_VERSION` ويعرض التوافق في "حول البرنامج".
- **الإصدار:** `version.properties` (Maven resource filtering) هو المصدر الوحيد لـ `AppConfig.appVersion()`.

## 5j. المستخدمون والأمان (المرحلة 13B)

```
UsersController (users.fxml: القائمة + مصفوفة الصلاحيات) → UserFormController (إنشاء / تعديل / إعادة تعيين، LeaveGuard)
ChangePasswordController (change-password.fxml: إجباري بعد إعادة التعيين، أو اختياري من الشريط العلوي)
        ▼
UserService (+Impl) ── USERS_* ، قواعد المدير داخل معاملة تقفل المديرين الفعالين (UPDLOCK, HOLDLOCK)
AuthService.changePassword ── للمستخدم نفسه (بلا صلاحية إدارية) ثم إنهاء الجلسة
        ▼
UserDao ── Users (must_change_password, password_changed_at)
```

- **لا كلمات مرور خارج الـ hash:** `UserAccount` (ما تُرجعه الخدمة) لا يحتوي حقل كلمة مرور ولا hash أصلًا.
- **الجلسة المقيدة:** `UserSession(passwordChangeRequired = true)` بلا أي صلاحية؛ `Navigator.showMain` يحوّل لصفحة التغيير،
  والخدمات ترفض حتى لو تجاوزت الواجهة.
- **أول مدير:** `UserDao.insertFirst` يفحص "لا يوجد مستخدم" ويُدرج في جملة واحدة مع قفل الجدول — لا مدير ثانٍ أبدًا.
- **الإعدادات:** `AppConfig` يقرأ: System properties ← متغيرات البيئة `ALMAHWAR_*` ← `config/application.properties`
  ← الافتراضيات المضمّنة (بلا بيانات اعتماد).

## 5k. النسخ الاحتياطي والاستعادة (المرحلة 13C)

```
BackupController (backup.fxml)  ── لا SQL، كل عملية في الخلفية (Async) مع حالة انشغال؛ LeaveGuard أثناء الاستعادة
        ▼
BackupRestoreService (+Impl) ── BACKUP_* ، قفل واحد للعملية: داخل البرنامج + sp_getapplock على الخادم (كل الأجهزة)
BackupPolicy                 ── قواعد بلا قاعدة بيانات: أسماء الملفات، مسار الخادم، اسم القاعدة، التأكيد، التوافق، رسائل الأخطاء
        ▼
DatabaseBackupDao ── BACKUP / RESTORE / HEADERONLY / FILELISTONLY / VERIFYONLY عبر اتصال بـ master (قيم كمعاملات فقط)
BackupHistoryDao  ── Backup_History + Audit_Log في القاعدة المستهدفة
        ▼
SQL Server (يكتب ويقرأ الملفات على جهازه هو: backup.server-directory كما يراه الخادم)
```

- **مساران منفصلان:** مسار الملف يخص جهاز SQL Server فقط ويأتي من الإعدادات الموثوقة؛ البرنامج لا يقرأ الملفات ولا ينسخها
  ولا يقبل مسارًا من الشاشة.
- **التاريخ في جدول (`Backup_History`, v1.10.0):** حالة العملية (CREATING/COMPLETED/FAILED) منفصلة عن حالة التحقق
  (NOT_VERIFIED/VERIFIED/VERIFY_FAILED)؛ الاسم UNIQUE يمنع تكرار الاسم بين الأجهزة، و `NOINIT` + فحص HEADERONLY يمنعان الكتابة فوق ملف.
- **الاستعادة:** تأكيد باسم القاعدة ← تحقق ← نسخة وقائية متحقق منها (بدونها لا استعادة) ← من master:
  SINGLE_USER + RESTORE … REPLACE, CHECKSUM, MOVE ← MULTI_USER دائمًا ← إنهاء الجلسة ← فحص سلامة للقراءة فقط ←
  نقل السجل والتدقيق إلى القاعدة المستعادة. فشل يترك القاعدة غير صالحة ← إعادة النسخة الوقائية تلقائيًا.
- **قابل لـ REST لاحقًا:** الخدمة بلا JavaFX، والنتائج Records (`BackupInfo`, `BackupResult`, `BackupVerificationResult`,
  `RestoreResult`, `BackupSettings`)، وإنهاء الجلسة يُمرَّر كـ `Runnable` من `AppContext`.

## 5l. الجاهزية للإنتاج (المرحلة 13D)

```
MainApp.main ── AppLogging.init (ملفات دوّارة في %LOCALAPPDATA%\AlMahwar\logs) + معالج عام للأخطاء غير المتوقعة
LoginController ── HealthCheckService (+Impl) ── DatabaseHealthDao (قراءة فقط) ── SQL Server
                    └─ DatabaseHealth (Status: HEALTHY / CONFIGURATION_ERROR / SERVER_UNREACHABLE / LOGIN_FAILED /
                       DATABASE_UNAVAILABLE / SCHEMA_MISSING / SCHEMA_OUTDATED / SCHEMA_TOO_NEW /
                       CRITICAL_TABLE_MISSING / NO_ACTIVE_ADMIN)
```

- **فحص الإعدادات** في `AppConfig.databaseProblems` (يذكر اسم الإعداد فقط، لا قيمته)، و**فحص الصحة** قبل الدخول:
  لا يصلح ولا يرقّي ولا ينشئ مستخدمين.
- **TLS:** مشفّر وشهادة الخادم يُتحقق منها افتراضيًا؛ `db.trust-server-certificate=true` للتطوير فقط مع تحذير في السجل.
- **ملف الإعدادات الخارجي:** المسار الصريح ← مجلد التشغيل ← مجلد البرنامج (صورة jpackage أو مجلد الـ JAR).
- **السجل التقني منفصل عن Audit_Log**، مع إخفاء أي قيمة تشبه كلمة مرور.
- **عمليات النسخ الاحتياطي:** لا مغادرة ولا إغلاق للنافذة أثناءها، وخروج عدم النشاط ينتظر انتهاءها.

---

## 6. إضافة Module جديدة

المبيعات (5d) مثال كامل على هذه الخطوات:

1. **Model**: الكيانات (`Sale`, `SaleItem` …) — `BigDecimal` للمبالغ والكميات.
2. **DAO**: `SaleDao` — كل SQL هنا، والعمليات المتعددة داخل `TransactionManager.inTransaction(...)`
   (الفاتورة + `StockLedger.post(… SALE …)` + الخزنة + رصيد العميل في معاملة واحدة).
3. **Service**: `SaleService` (Interface) + `SaleServiceImpl` — التحقق، الحسابات، `security.requirePermission(Permission.SALES_POST)`.
4. **AppContext**: تسجيل `SaleService`.
5. **Controller + FXML**: يستدعي `AppContext.get().sales()` فقط، وينسق العرض.
6. **Tests**: Unit للمنطق، وIntegration على SQL Server (`-Ddb.it=true`)، و`ArchitectureTest` يجب أن ينجح.

---

## 7. الملفات الرئيسية

```
src/main/java/com/almahwar
├── MainApp.java, Launcher.java
├── config/       AppConfig, DatabaseConnection, AppContext, AppLogging
├── model/        User, Role, Product, Customer, Supplier, Category, Brand, Unit,
│                 Permission, NavigationItem, PaymentMethod, CustomerType,
│                 UserSession, DashboardStats, DashboardLists,
│                 StockMovement, MovementType, StockAdjustment, ProductFilter, MovementFilter,
│                 PartyType, LedgerEntryType, LedgerEntry, AccountStatement, PartyFilter, CreditStatus,
│                 Purchase, PurchaseItem, PurchaseStatus, PurchaseFilter, PaymentType, PaymentStatus,
│                 Sale, SaleItem, SaleStatus, SaleType, SaleFilter,
│                 PartyPayment, OutstandingParty, Expense, ExpenseCategory, ExpenseFilter,
│                 CashMovement, CashSource, CashFilter, CashSummary,
│                 ReturnDocument, ReturnLine, ReturnKind, ReturnReason, ReturnFilter, RefundPlan,
│                 Quotation, QuotationItem, QuotationStatus, QuotationFilter, QuotationConversion,
│                 ReportType, ReportPeriod, ReportFilter, Reports (DTOs), ReportTable,
│                 CompanySettings, SystemSettings, Settings (LogoInfo, Snapshot, About), UserAccount,
│                 BackupInfo, BackupResult, BackupVerificationResult, RestoreResult, BackupSettings,
│                 DatabaseHealth
├── dao/          BaseDao, RowMapper, TransactionManager, DataAccessException,
│                 UserDao, RoleDao, ProductDao, CustomerDao, SupplierDao, CategoryDao,
│                 BrandDao, UnitDao, AuditLogDao, DashboardDao, StockMovementDao, AccountLedgerDao,
│                 PurchaseDao, CashTransactionDao, SaleDao, PaymentDao, ExpenseDao, ReturnDao,
│                 QuotationDao, ReportDao, SettingsDao, DatabaseBackupDao, BackupHistoryDao, DatabaseHealthDao
├── service/      AuthService (+Impl), DashboardService (+Impl), SystemStatusService (+Impl),
│                 CatalogService, ProductService, InventoryService (+Impl), StockLedger,
│                 CustomerService, SupplierService (+Impl), AccountLedger,
│                 PurchaseService (+Impl), PaymentRules, CostingPolicy,
│                 SaleService (+Impl), InsufficientStockException, CreditLimitExceededException,
│                 PaymentService, ExpenseService, CashboxService (+Impl), FinanceRules,
│                 ReturnService (+Impl), QuotationService (+Impl),
│                 ReportService (+Impl), ReportTables, XlsxReportWriter,
│                 SettingsService (+Impl), UserService (+Impl),
│                 BackupRestoreService (+Impl), BackupPolicy, BackupException, HealthCheckService (+Impl),
│                 SecurityContext, SessionManager, RolePermissions, CredentialPolicy,
│                 LoginAttemptTracker, AuthenticationException, AccessDeniedException,
│                 ValidationException
├── controller/   LoginController, MainController, ProductsController, ProductFormController,
│   │             InventoryController, MasterDataPane, Customers/Suppliers (+Details, +Form)Controller,
│   │             AccountStatementPane, Purchases/PurchaseForm/PurchaseDetailsController,
│   │             PurchasePrintPage, PurchasePages,
│   │             PosController, SalesController, SaleDetailsController, SalePrintPage, SalePages,
│   │             PartyPaymentController, PaymentHistoryPane, ExpensesController, CashboxController,
│   │             ReturnsController, ReturnFormController, ReturnDetailsPage, ReturnHistoryPane,
│   │             QuotationsController, QuotationFormController, QuotationDetailsController,
│   │             QuotationPrintPage, QuotationPages,
│   │             ReportsController, ReportViewPage, ReportPrintPage,
│   │             SettingsController, CompanyHeader,
│   │             UsersController, UserFormController, ChangePasswordController,
│   │             BackupController
│   └── support/  Navigator, ViewLoader, AlertUtil, Icons, DashboardCards,
│                 Async, ErrorMessages, FormErrors, NumberInput, ProductPicker
└── util/         MoneyUtil, QuantityUtil, PasswordHasher, PhoneNumbers
database/         01_create_database.sql, 02_demo_data.sql (تجريبي فقط)
```
