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

## 5. إضافة Module جديدة (مثال: نقطة البيع — المرحلة 5)

1. **Model**: الكيانات (`Sale`, `SaleItem` …) — `BigDecimal` للمبالغ والكميات.
2. **DAO**: `SaleDao` — كل SQL هنا، والعمليات المتعددة داخل `TransactionManager.inTransaction(...)`
   (الفاتورة + خصم المخزون + حركة المخزون + الخزنة + رصيد العميل في معاملة واحدة).
3. **Service**: `SaleService` (Interface) + `SaleServiceImpl` — التحقق، الحسابات، `security.requirePermission(Permission.SALES)`.
4. **AppContext**: تسجيل `SaleService`.
5. **Controller + FXML**: يستدعي `AppContext.get().sales()` فقط، وينسق العرض.
6. **Tests**: Unit للمنطق، وIntegration على SQL Server (`-Ddb.it=true`)، و`ArchitectureTest` يجب أن ينجح.

---

## 6. الملفات الرئيسية

```
src/main/java/com/almahwar
├── MainApp.java, Launcher.java
├── config/       AppConfig, DatabaseConnection, AppContext
├── model/        User, Role, Product, Customer, Supplier, Category, Brand, Unit,
│                 Permission, NavigationItem, PaymentMethod, CustomerType,
│                 UserSession, DashboardStats, DashboardLists
├── dao/          BaseDao, RowMapper, TransactionManager, DataAccessException,
│                 UserDao, RoleDao, ProductDao, CustomerDao, SupplierDao, CategoryDao,
│                 BrandDao, UnitDao, AuditLogDao, DashboardDao
├── service/      AuthService (+Impl), DashboardService (+Impl), SystemStatusService (+Impl),
│                 SecurityContext, SessionManager, RolePermissions, CredentialPolicy,
│                 LoginAttemptTracker, AuthenticationException, AccessDeniedException
├── controller/   LoginController, MainController
│   └── support/  Navigator, ViewLoader, AlertUtil, Icons, DashboardCards
└── util/         MoneyUtil, QuantityUtil, PasswordHasher
database/         01_create_database.sql, 02_demo_data.sql (تجريبي فقط)
```
