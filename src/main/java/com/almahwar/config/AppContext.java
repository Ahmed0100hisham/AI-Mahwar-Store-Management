package com.almahwar.config;

import com.almahwar.dao.AccountLedgerDao;
import com.almahwar.dao.AuditLogDao;
import com.almahwar.dao.BrandDao;
import com.almahwar.dao.CashTransactionDao;
import com.almahwar.dao.CategoryDao;
import com.almahwar.dao.CustomerDao;
import com.almahwar.dao.DashboardDao;
import com.almahwar.dao.ExpenseDao;
import com.almahwar.dao.PaymentDao;
import com.almahwar.dao.ProductDao;
import com.almahwar.dao.PurchaseDao;
import com.almahwar.dao.ReturnDao;
import com.almahwar.dao.RoleDao;
import com.almahwar.dao.SaleDao;
import com.almahwar.dao.StockMovementDao;
import com.almahwar.dao.SupplierDao;
import com.almahwar.dao.UnitDao;
import com.almahwar.dao.UserDao;
import com.almahwar.service.AccountLedger;
import com.almahwar.service.AuthService;
import com.almahwar.service.AuthServiceImpl;
import com.almahwar.service.CatalogService;
import com.almahwar.service.CashboxService;
import com.almahwar.service.CashboxServiceImpl;
import com.almahwar.service.CatalogServiceImpl;
import com.almahwar.service.CostingPolicy;
import com.almahwar.service.CustomerService;
import com.almahwar.service.CustomerServiceImpl;
import com.almahwar.service.DashboardService;
import com.almahwar.service.DashboardServiceImpl;
import com.almahwar.service.ExpenseService;
import com.almahwar.service.ExpenseServiceImpl;
import com.almahwar.service.InventoryService;
import com.almahwar.service.InventoryServiceImpl;
import com.almahwar.service.LoginAttemptTracker;
import com.almahwar.service.PaymentService;
import com.almahwar.service.PaymentServiceImpl;
import com.almahwar.service.ProductService;
import com.almahwar.service.ProductServiceImpl;
import com.almahwar.service.PurchaseService;
import com.almahwar.service.PurchaseServiceImpl;
import com.almahwar.service.ReturnService;
import com.almahwar.service.ReturnServiceImpl;
import com.almahwar.service.SaleService;
import com.almahwar.service.SaleServiceImpl;
import com.almahwar.service.QuotationService;
import com.almahwar.service.ReportService;
import com.almahwar.service.SettingsService;
import com.almahwar.service.UserService;
import com.almahwar.service.BackupRestoreService;
import com.almahwar.service.BackupRestoreServiceImpl;
import com.almahwar.dao.BackupHistoryDao;
import com.almahwar.dao.DatabaseBackupDao;
import com.almahwar.dao.DatabaseHealthDao;
import com.almahwar.service.HealthCheckService;
import com.almahwar.service.HealthCheckServiceImpl;
import com.almahwar.service.UserServiceImpl;
import com.almahwar.service.SettingsServiceImpl;
import com.almahwar.dao.SettingsDao;
import com.almahwar.service.ReportServiceImpl;
import com.almahwar.dao.ReportDao;
import com.almahwar.service.QuotationServiceImpl;
import com.almahwar.dao.QuotationDao;
import com.almahwar.service.SecurityContext;
import com.almahwar.service.SessionManager;
import com.almahwar.service.StockLedger;
import com.almahwar.service.SupplierService;
import com.almahwar.service.SupplierServiceImpl;
import com.almahwar.service.SystemStatusService;
import com.almahwar.service.SystemStatusServiceImpl;

import java.time.Clock;
import java.time.Duration;

/**
 * Composition root: the single place that decides which implementation of each
 * service the application uses. Controllers ask this class for services and
 * never construct them.
 * <p>
 * Today every service is the local implementation (Service → DAO → SQL Server).
 * When the REST API exists, this is where the desktop app switches to the
 * HTTP-client implementations (e.g. a {@code RestAuthService}), with no changes
 * to controllers.
 */
public final class AppContext {

    private static final AppContext INSTANCE = new AppContext();

    private final SessionManager sessionManager;
    private final AuthService authService;
    private final DashboardService dashboardService;
    private final SystemStatusService systemStatusService;
    private final CatalogService catalogService;
    private final ProductService productService;
    private final InventoryService inventoryService;
    private final CustomerService customerService;
    private final SupplierService supplierService;
    private final PurchaseService purchaseService;
    private final SaleService saleService;
    private final PaymentService paymentService;
    private final ExpenseService expenseService;
    private final CashboxService cashboxService;
    private final ReturnService returnService;
    private final QuotationService quotationService;
    private final ReportService reportService;
    private final SettingsService settingsService;
    private final UserService userService;
    private final BackupRestoreService backupService;
    private final HealthCheckService healthService;

    private AppContext() {
        AppConfig cfg = AppConfig.getInstance();
        sessionManager = SessionManager.getInstance();

        LoginAttemptTracker attemptTracker = new LoginAttemptTracker(
                cfg.getInt("security.login.max-attempts", 5),
                Duration.ofSeconds(cfg.getInt("security.login.lock-seconds", 300)),
                Clock.systemUTC());
        authService = new AuthServiceImpl(new UserDao(), new RoleDao(), new AuditLogDao(),
                attemptTracker, sessionManager);
        dashboardService = new DashboardServiceImpl(new DashboardDao(), sessionManager);
        systemStatusService = new SystemStatusServiceImpl(cfg);

        ProductDao productDao = new ProductDao();
        CategoryDao categoryDao = new CategoryDao();
        BrandDao brandDao = new BrandDao();
        UnitDao unitDao = new UnitDao();
        AuditLogDao auditLogDao = new AuditLogDao();
        StockMovementDao movementDao = new StockMovementDao();
        StockLedger ledger = new StockLedger(movementDao);
        catalogService = new CatalogServiceImpl(categoryDao, brandDao, unitDao, auditLogDao, sessionManager);
        productService = new ProductServiceImpl(productDao, categoryDao, brandDao, unitDao, ledger, auditLogDao,
                sessionManager);
        inventoryService = new InventoryServiceImpl(productDao, unitDao, movementDao, ledger, auditLogDao,
                sessionManager);

        CustomerDao customerDao = new CustomerDao();
        SupplierDao supplierDao = new SupplierDao();
        AccountLedger accountLedger = new AccountLedger(new AccountLedgerDao(), customerDao, supplierDao);
        customerService = new CustomerServiceImpl(customerDao, accountLedger, auditLogDao, sessionManager);
        supplierService = new SupplierServiceImpl(supplierDao, accountLedger, auditLogDao, sessionManager);

        // Costing: last posted purchase cost (switch to CostingPolicy.WEIGHTED_AVERAGE here if ever needed)
        CashTransactionDao cashDao = new CashTransactionDao();
        purchaseService = new PurchaseServiceImpl(new PurchaseDao(), supplierDao, productDao, unitDao, ledger,
                accountLedger, cashDao, auditLogDao, CostingPolicy.LAST_PURCHASE_COST, sessionManager);
        // A sale line's historical cost is the product's current cost, kept up to date by the costing policy above
        QuotationDao quotationDao = new QuotationDao();
        SaleDao saleDao = new SaleDao();
        saleService = new SaleServiceImpl(saleDao, customerDao, productDao, unitDao, ledger, accountLedger,
                cashDao, auditLogDao, sessionManager, quotationDao);
        // Quotations: financially neutral; converted through the sales service above
        quotationService = new QuotationServiceImpl(quotationDao, customerDao, productDao, unitDao, saleDao, saleService,
                auditLogDao, sessionManager);

        // Financial operations: the same AccountLedger and Cash_Transactions as the documents above
        paymentService = new PaymentServiceImpl(new PaymentDao(), customerDao, supplierDao, accountLedger, cashDao,
                auditLogDao, sessionManager);
        expenseService = new ExpenseServiceImpl(new ExpenseDao(), cashDao, auditLogDao, sessionManager);
        cashboxService = new CashboxServiceImpl(cashDao, auditLogDao, sessionManager);

        // Returns: against the original documents, through the same stock / account / cash ledgers
        returnService = new ReturnServiceImpl(new ReturnDao(), new SaleDao(), new PurchaseDao(), customerDao, supplierDao,
                productDao, ledger, accountLedger, cashDao, auditLogDao, sessionManager);

        // Reports: read-only queries over the same tables; statements reuse the account ledger
        // Users: administration of accounts (admin only); passwords only as hashes
        userService = new UserServiceImpl(new UserDao(), new RoleDao(), auditLogDao, sessionManager);

        // Settings: the central company profile / system settings (read by every print and the report export)
        settingsService = new SettingsServiceImpl(new SettingsDao(), auditLogDao, sessionManager, systemStatusService,
                cfg.appName(), cfg.appNameEn(), cfg.appVersion());
        // Backup / restore: SQL Server writes the files on its own machine (backup.server-directory, as the server
        // sees it); restore runs from master and ends the session afterwards
        // an invalid db.name must not stop the program here: the health check reports it and blocks the login
        String configuredDb = DatabaseConnection.databaseName();
        String dbName = configuredDb.matches("[A-Za-z0-9_]{1,100}") ? configuredDb : "INVALID_DATABASE_NAME";
        backupService = new BackupRestoreServiceImpl(new BackupRestoreServiceImpl.Config(
                cfg.getBoolean("backup.enabled", true), cfg.get("backup.server-directory"),
                cfg.get("backup.file-prefix"), dbName, SettingsService.REQUIRED_SCHEMA_VERSION),
                new DatabaseBackupDao(dbName), new BackupHistoryDao(dbName, auditLogDao), sessionManager,
                () -> authService.logout(AuthService.LogoutReason.DATABASE_RESTORED));
        // Startup health check (read only): settings, server, database, schema version, tables, active admin
        healthService = new HealthCheckServiceImpl(cfg::databaseProblems, new DatabaseHealthDao(dbName), dbName,
                SettingsService.REQUIRED_SCHEMA_VERSION);
        reportService = new ReportServiceImpl(new ReportDao(), accountLedger, customerDao, supplierDao, productDao,
                sessionManager, () -> settingsService.company().nameAr());
    }

    public static AppContext get() {
        return INSTANCE;
    }

    public AppConfig config() {
        return AppConfig.getInstance();
    }

    /** The current user and permissions of this desktop session. */
    public SecurityContext security() {
        return sessionManager;
    }

    public AuthService auth() {
        return authService;
    }

    public DashboardService dashboard() {
        return dashboardService;
    }

    public SystemStatusService systemStatus() {
        return systemStatusService;
    }

    /** Categories, brands and units. */
    public CatalogService catalog() {
        return catalogService;
    }

    public ProductService products() {
        return productService;
    }

    public InventoryService inventory() {
        return inventoryService;
    }

    public CustomerService customers() {
        return customerService;
    }

    public SupplierService suppliers() {
        return supplierService;
    }

    public PurchaseService purchases() {
        return purchaseService;
    }

    /** Point of sale and sales invoices. */
    public SaleService sales() {
        return saleService;
    }

    /** Customer collections and supplier payments. */
    public PaymentService payments() {
        return paymentService;
    }

    public ExpenseService expenses() {
        return expenseService;
    }

    /** The treasury: balance, movements, manual deposits / withdrawals. */
    public CashboxService cashbox() {
        return cashboxService;
    }

    /** Sales and purchase returns. */
    public ReturnService returns() {
        return returnService;
    }

    /** User administration (admin only). */
    public UserService users() {
        return userService;
    }

    /** Read-only startup health check of the database. */
    public HealthCheckService health() {
        return healthService;
    }

    /** Database backup, verification and restore (admin only). */
    public BackupRestoreService backups() {
        return backupService;
    }

    /** Company profile, system settings, logo, about. */
    public SettingsService settings() {
        return settingsService;
    }

    /** Reports & analytics (read-only). */
    public ReportService reports() {
        return reportService;
    }

    /** Quotations (عروض الأسعار). */
    public QuotationService quotations() {
        return quotationService;
    }
}
