package com.almahwar.controller;

import com.almahwar.config.AppConfig;
import com.almahwar.config.AppContext;
import com.almahwar.model.DashboardLists.LowStockItem;
import com.almahwar.model.DashboardLists.RecentInvoice;
import com.almahwar.model.DashboardLists.SalesPoint;
import com.almahwar.model.DashboardLists.TopProduct;
import com.almahwar.model.NavigationItem;
import com.almahwar.model.User;
import com.almahwar.model.UserSession;
import com.almahwar.service.AccessDeniedException;
import com.almahwar.service.DashboardService;
import com.almahwar.service.DashboardService.MetricValue;
import com.almahwar.service.DashboardService.DashboardData;
import com.almahwar.service.DashboardService.SalesTrend;
import com.almahwar.service.DashboardService.TrendRange;
import com.almahwar.service.RolePermissions;
import com.almahwar.service.SecurityContext;
import com.almahwar.service.SystemStatusService;
import com.almahwar.controller.support.DashboardCards;
import com.almahwar.controller.support.DashboardCards.CardView;
import com.almahwar.controller.support.AlertUtil;
import com.almahwar.controller.support.Icons;
import com.almahwar.util.MoneyUtil;
import com.almahwar.controller.support.Navigator;
import com.almahwar.controller.support.ViewLoader;
import com.almahwar.util.QuantityUtil;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controller for the main window: header with the logged-in user, role-based
 * side menu, and the dashboard (cards, sales chart, lists) loaded from SQL Server.
 */
public class MainController {

    private static final Logger LOG = Logger.getLogger(MainController.class.getName());

    private static final Locale AR = Locale.forLanguageTag("ar");
    private static final DateTimeFormatter HEADER_DATE = DateTimeFormatter.ofPattern("EEEE، d MMMM yyyy", AR);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("hh:mm a", AR);
    private static final DateTimeFormatter INVOICE_DATE = DateTimeFormatter.ofPattern("d/M  hh:mm a", AR);
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("d/M");
    private static final DateTimeFormatter DAY_LONG = DateTimeFormatter.ofPattern("EEEE d MMMM", AR);
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM", AR);
    private static final DateTimeFormatter MONTH_LONG = DateTimeFormatter.ofPattern("MMMM yyyy", AR);

    // Header / footer
    @FXML private Label appTitleLabel;
    @FXML private Label appSubtitleLabel;
    @FXML private Label dateLabel;
    @FXML private Label userInitialLabel;
    @FXML private Label userNameLabel;
    @FXML private Label userRoleLabel;
    @FXML private Label footerUserLabel;
    @FXML private Label footerDbLabel;
    @FXML private Label footerVersionLabel;
    @FXML private Label footerCurrencyLabel;

    @FXML private VBox navContainer;
    @FXML private StackPane contentArea;
    @FXML private ScrollPane dashboardView;

    // Dashboard
    @FXML private Label welcomeLabel;
    @FXML private Label dashboardTitleLabel;
    @FXML private Label lastUpdatedLabel;
    @FXML private Button refreshButton;
    @FXML private Label kpiStatusLabel;
    @FXML private TilePane kpiGrid;
    @FXML private GridPane sectionsGrid;

    @FXML private VBox chartCard;
    @FXML private Label chartSummaryLabel;
    @FXML private HBox trendToggleBox;
    @FXML private AreaChart<Number, Number> salesChart;
    @FXML private NumberAxis chartXAxis;
    @FXML private NumberAxis chartYAxis;

    @FXML private VBox topProductsCard;
    @FXML private VBox topProductsBox;

    @FXML private VBox recentInvoicesCard;
    @FXML private TableView<RecentInvoice> invoicesTable;
    @FXML private TableColumn<RecentInvoice, String> invNoColumn;
    @FXML private TableColumn<RecentInvoice, String> invCustomerColumn;
    @FXML private TableColumn<RecentInvoice, String> invDateColumn;
    @FXML private TableColumn<RecentInvoice, String> invTotalColumn;
    @FXML private TableColumn<RecentInvoice, RecentInvoice> invStatusColumn;

    @FXML private VBox lowStockCard;
    @FXML private Label lowStockCountLabel;
    @FXML private Hyperlink lowStockAllLink;
    @FXML private TableView<LowStockItem> lowStockTable;
    @FXML private TableColumn<LowStockItem, LowStockItem> lowNameColumn;
    @FXML private TableColumn<LowStockItem, String> lowQtyColumn;
    @FXML private TableColumn<LowStockItem, String> lowMinColumn;

    // Database status
    @FXML private Circle dbStatusDot;
    @FXML private Label dbStatusLabel;
    @FXML private Label dbStatusDetailLabel;
    @FXML private Button retryDbButton;

    // Services come from the composition root; the controller never builds them or touches the database
    private final SecurityContext security = AppContext.get().security();
    private final DashboardService dashboardService = AppContext.get().dashboard();
    private final SystemStatusService systemStatus = AppContext.get().systemStatus();
    private final ToggleGroup trendGroup = new ToggleGroup();
    private TrendRange trendRange = TrendRange.LAST_30_DAYS;
    private Timeline autoRefresh;
    private boolean loading;
    private final Map<NavigationItem, Button> navButtons = new EnumMap<>(NavigationItem.class);

    @FXML
    private void initialize() {
        AppConfig cfg = AppConfig.getInstance();
        UserSession session = security.requireSession();
        User user = session.getUser();

        appTitleLabel.setText(cfg.appName());
        appSubtitleLabel.setText(cfg.appNameEn());
        dateLabel.setText(LocalDate.now().format(HEADER_DATE));   // replaced by the server date once loaded

        userInitialLabel.setText(user.getFullName().isBlank() ? "؟" : user.getFullName().substring(0, 1));
        userNameLabel.setText(user.getFullName());
        userRoleLabel.setText(user.getRoleName());
        welcomeLabel.setText("مرحبًا، " + user.getFullName());
        dashboardTitleLabel.setText(RolePermissions.dashboardTitle(user.getRoleCode()));

        footerUserLabel.setText("المستخدم: " + user.getUsername()
                + "  •  دخول " + session.getLoginAt().format(TIME));
        footerVersionLabel.setText("الإصدار " + ViewSupport.ltr(cfg.appVersion()));
        footerCurrencyLabel.setText("العملة: " + cfg.currencyCode() + " (" + cfg.currencySymbol() + ")");

        buildNavigation(session);
        // KPI cards fill equal columns: three on a normal / wide window (3 × 3 for the admin's nine cards, at 1366
        // and at 1920 alike), two on a narrow one — never a lone card on the last row because of a fixed width
        kpiGrid.widthProperty().addListener((o, old, width) -> {
            double w = width.doubleValue();
            int columns = w >= 900 ? 3 : 2;
            kpiGrid.setPrefTileWidth(Math.max(240, Math.floor((w - kpiGrid.getHgap() * (columns - 1)) / columns) - 1));
        });
        setUpChart();
        setUpTables();
        loadDashboard();
        checkDatabase();
        startAutoRefresh(cfg.getInt("app.dashboard.refresh-seconds", 120));
    }

    // ---------- Navigation ----------

    /**
     * Side-menu sections, in display order (presentation only: who sees an entry is still decided by
     * {@link NavigationItem#isVisibleTo}; a section without a visible entry is not shown).
     */
    private static final List<Map.Entry<String, List<NavigationItem>>> NAV_SECTIONS = List.of(
            Map.entry("", List.of(NavigationItem.HOME)),
            Map.entry("المبيعات", List.of(NavigationItem.POINT_OF_SALE, NavigationItem.SALES, NavigationItem.QUOTATIONS,
                    NavigationItem.RETURNS, NavigationItem.CUSTOMERS)),
            Map.entry("المخزون والمشتريات", List.of(NavigationItem.PRODUCTS, NavigationItem.INVENTORY,
                    NavigationItem.PURCHASES, NavigationItem.SUPPLIERS)),
            Map.entry("المالية", List.of(NavigationItem.CASH, NavigationItem.EXPENSES)),
            Map.entry("التقارير والإدارة", List.of(NavigationItem.REPORTS, NavigationItem.USERS, NavigationItem.SETTINGS,
                    NavigationItem.BACKUP)));

    private void buildNavigation(UserSession session) {
        navContainer.getChildren().clear();
        java.util.Set<NavigationItem> placed = java.util.EnumSet.noneOf(NavigationItem.class);
        for (Map.Entry<String, List<NavigationItem>> section : NAV_SECTIONS) {
            List<NavigationItem> visible = section.getValue().stream().filter(i -> i.isVisibleTo(session)).toList();
            placed.addAll(section.getValue());
            addNavSection(section.getKey(), visible);
        }
        // any entry not assigned to a section above still appears (never hidden by the layout)
        addNavSection("أخرى", java.util.Arrays.stream(NavigationItem.values())
                .filter(i -> !placed.contains(i) && i.isVisibleTo(session)).toList());
        setActiveNav(NavigationItem.HOME);
        lowStockAllLink.setVisible(NavigationItem.INVENTORY.isVisibleTo(session)
                || NavigationItem.PRODUCTS.isVisibleTo(session));
    }

    private void addNavSection(String title, List<NavigationItem> items) {
        if (items.isEmpty()) {
            return;
        }
        if (!title.isEmpty()) {
            Label header = new Label(title);
            header.getStyleClass().add("sidebar-section");
            navContainer.getChildren().add(header);
        }
        for (NavigationItem item : items) {
            Button button = new Button(item.getLabelAr(), Icons.node(Icons.of(item), "nav-icon"));
            button.getStyleClass().add("nav-button");
            button.setGraphicTextGap(12);
            button.setMaxWidth(Double.MAX_VALUE);
            button.setOnAction(e -> onNavigate(item));
            navButtons.put(item, button);
            navContainer.getChildren().add(button);
        }
    }

    /** Access is re-checked here, not only by hiding buttons (the services check it again). */
    private void onNavigate(NavigationItem item) {
        UserSession session = security.getSession().orElse(null);
        if (!item.isVisibleTo(session)) {
            AlertUtil.warning("غير مصرح", "ليس لديك صلاحية الوصول إلى: " + item.getLabelAr());
            return;
        }
        switch (item) {
            case HOME -> showHome();
            case PRODUCTS -> openModule(item, "products.fxml", null);
            case INVENTORY -> openModule(item, "inventory.fxml", null);
            case CUSTOMERS -> openModule(item, "customers.fxml", null);
            case SUPPLIERS -> openModule(item, "suppliers.fxml", null);
            case PURCHASES -> openModule(item, "purchases.fxml", null);
            case POINT_OF_SALE -> this.<PosController>openModule(item, "pos.fxml", c -> c.open(null, null));
            case SALES -> openModule(item, "sales.fxml", null);
            case CASH -> openModule(item, "cashbox.fxml", null);
            case EXPENSES -> openModule(item, "expenses.fxml", null);
            case RETURNS -> openModule(item, "returns.fxml", null);
            case QUOTATIONS -> openModule(item, "quotations.fxml", null);
            case REPORTS -> openModule(item, "reports.fxml", null);
            case SETTINGS -> openModule(item, "settings.fxml", null);
            case USERS -> openModule(item, "users.fxml", null);
            case BACKUP -> openModule(item, "backup.fxml", null);
            default -> AlertUtil.info("قيد التطوير", "وحدة \"" + item.getLabelAr() + "\" سيتم تطويرها في المراحل القادمة.");
        }
    }

    private void showHome() {
        Navigator.leave(() -> {
            contentArea.getChildren().setAll(dashboardView);
            setActiveNav(NavigationItem.HOME);
            loadDashboard();   // figures may have changed in another module
        });
    }

    /** Replaces the page in the content area; {@code setup} receives the module's controller. */
    private <C> void openModule(NavigationItem item, String fxml, java.util.function.Consumer<C> setup) {
        // every page change passes the leave guard (an unsaved POS cart is never lost silently)
        Navigator.leave(() -> {
            Node page = ViewLoader.<C>load(fxml, c -> {
                if (setup != null) {
                    setup.accept(c);
                }
            });
            contentArea.getChildren().setAll(page);
            setActiveNav(item);
        });
    }

    private void setActiveNav(NavigationItem active) {
        navButtons.forEach((item, button) -> {
            button.getStyleClass().remove("nav-button-active");
            if (item == active) {
                button.getStyleClass().add("nav-button-active");
            }
        });
    }

    private boolean isDashboardShown() {
        return contentArea.getChildren().contains(dashboardView);
    }

    /** "عرض الكل" on the low-stock list: the full list in the inventory (or products) module. */
    @FXML
    private void onShowAllLowStock() {
        UserSession session = security.getSession().orElse(null);
        if (NavigationItem.INVENTORY.isVisibleTo(session)) {
            this.<InventoryController>openModule(NavigationItem.INVENTORY, "inventory.fxml",
                    InventoryController::showLowStockOnly);
        } else if (NavigationItem.PRODUCTS.isVisibleTo(session)) {
            this.<ProductsController>openModule(NavigationItem.PRODUCTS, "products.fxml",
                    ProductsController::showLowStockOnly);
        }
    }

    /** Any logged-in user may change their own password (unsaved work is protected first). */
    @FXML
    private void onChangePassword() {
        Navigator.leave(() -> Navigator.showChangePassword(false));
    }

    @FXML
    private void onLogout() {
        // with an unsaved cart the POS itself asks (complete / hold / stay / clear) instead of this question
        if (Navigator.hasUnsavedWork() || AlertUtil.confirm("تسجيل الخروج", "هل تريد تسجيل الخروج من النظام؟")) {
            Navigator.logout();
        }
    }

    // ---------- Dashboard loading ----------

    @FXML
    private void onRefresh() {
        loadDashboard();
    }

    private void startAutoRefresh(int seconds) {
        if (seconds <= 0) {
            return;
        }
        autoRefresh = new Timeline(new KeyFrame(Duration.seconds(seconds), e -> {
            if (isDashboardShown()) {
                loadDashboard();
            }
        }));
        autoRefresh.setCycleCount(Timeline.INDEFINITE);
        autoRefresh.play();
        // Stop when this view is replaced (logout / timeout), so no queries run after the session ends
        navContainer.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                autoRefresh.stop();
            }
        });
    }

    private void loadDashboard() {
        if (loading || !security.isLoggedIn()) {
            return;
        }
        loading = true;
        refreshButton.setDisable(true);
        if (kpiGrid.getChildren().isEmpty()) {
            setKpiStatus("جارٍ تحميل البيانات...");
        }

        TrendRange range = trendRange;
        Task<DashboardData> task = new Task<>() {
            @Override
            protected DashboardData call() {
                return dashboardService.load(range);
            }
        };
        task.setOnSucceeded(e -> {
            loading = false;
            refreshButton.setDisable(false);
            showDashboard(task.getValue());
        });
        task.setOnFailed(e -> {
            loading = false;
            refreshButton.setDisable(false);
            Throwable error = task.getException();
            if (error instanceof AccessDeniedException) {
                return;   // session ended while loading
            }
            LOG.log(Level.WARNING, "Loading dashboard failed", error);
            setKpiStatus("تعذّر تحميل بيانات لوحة التحكم. تحقق من الاتصال بقاعدة البيانات ثم اضغط تحديث.");
        });
        runInBackground(task, "dashboard-load");
    }

    private void showDashboard(DashboardData data) {
        // Same clock as the figures ("today", "this month"): the database server's date
        dateLabel.setText(data.serverDate().format(HEADER_DATE));
        kpiGrid.getChildren().setAll(data.metrics().stream().map(MainController::buildCard).toList());
        setKpiStatus(data.metrics().isEmpty() ? "لا توجد مؤشرات متاحة لصلاحياتك." : null);

        if (data.salesTrend() != null) {
            showTrend(data.salesTrend());
        }
        if (data.topProducts() != null) {
            showTopProducts(data.topProducts());
        }
        if (data.recentInvoices() != null) {
            invoicesTable.getItems().setAll(data.recentInvoices());
        }
        if (data.lowStock() != null) {
            lowStockTable.getItems().setAll(data.lowStock());
        }
        layoutSections(data);
        lastUpdatedLabel.setText("آخر تحديث " + LocalTime.now().format(TIME));
    }

    /** Places the permitted sections two per row; a section alone in its row takes the full width. */
    private void layoutSections(DashboardData data) {
        sectionsGrid.getChildren().clear();
        int row = 0;
        row = addRow(row, data.salesTrend() != null ? chartCard : null,
                data.topProducts() != null ? topProductsCard : null);
        addRow(row, data.recentInvoices() != null ? recentInvoicesCard : null,
                data.lowStock() != null ? lowStockCard : null);
        boolean any = !sectionsGrid.getChildren().isEmpty();
        sectionsGrid.setVisible(any);
        sectionsGrid.setManaged(any);
    }

    private int addRow(int row, Node first, Node second) {
        if (first == null && second == null) {
            return row;
        }
        if (first != null && second != null) {
            sectionsGrid.add(first, 0, row);
            sectionsGrid.add(second, 1, row);
        } else {
            sectionsGrid.add(first != null ? first : second, 0, row, 2, 1);
        }
        return row + 1;
    }

    private void setKpiStatus(String text) {
        boolean show = text != null;
        kpiStatusLabel.setText(show ? text : "");
        kpiStatusLabel.setVisible(show);
        kpiStatusLabel.setManaged(show);
    }

    // ---------- KPI cards ----------

    private static Region buildCard(MetricValue metricValue) {
        CardView card = DashboardCards.view(metricValue);
        StackPane icon = new StackPane(Icons.node(Icons.of(card.metric()), "kpi-icon"));
        icon.getStyleClass().add("kpi-icon-box");

        Label title = new Label(card.title());
        title.getStyleClass().add("kpi-title");
        Label value = new Label(card.value());
        value.getStyleClass().add("kpi-value");
        if (card.money()) {
            value.getStyleClass().add("money");
        }
        if (card.negative()) {
            value.getStyleClass().add("negative");
        }
        Label caption = new Label(card.caption());
        caption.getStyleClass().add("kpi-caption");
        caption.setWrapText(true);

        VBox text = new VBox(4, title, value, caption);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox box = new HBox(14, icon, text);
        box.getStyleClass().addAll("card", "kpi-card", card.styleClass());
        return box;
    }

    // ---------- Sales chart ----------

    private void setUpChart() {
        for (TrendRange range : TrendRange.values()) {
            ToggleButton button = new ToggleButton(range.getLabelAr());
            button.setToggleGroup(trendGroup);
            button.setUserData(range);
            button.getStyleClass().add("segment");
            button.setSelected(range == trendRange);
            trendToggleBox.getChildren().add(button);
        }
        trendGroup.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null) {
                old.setSelected(true);   // keep one range selected
                return;
            }
            trendRange = (TrendRange) selected.getUserData();
            reloadTrend();
        });

        DecimalFormat amount = new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(Locale.US));
        chartYAxis.setTickLabelFormatter(new StringConverter<>() {
            @Override public String toString(Number n) { return amount.format(n); }
            @Override public Number fromString(String s) { return null; }
        });
    }

    private void reloadTrend() {
        TrendRange range = trendRange;
        Task<SalesTrend> task = new Task<>() {
            @Override
            protected SalesTrend call() {
                return dashboardService.loadSalesTrend(range);
            }
        };
        task.setOnSucceeded(e -> showTrend(task.getValue()));
        task.setOnFailed(e -> LOG.log(Level.WARNING, "Loading sales trend failed", task.getException()));
        runInBackground(task, "dashboard-trend");
    }

    private void showTrend(SalesTrend trend) {
        List<SalesPoint> points = trend.points();
        boolean monthly = trend.range().isMonthly();

        // X axis = index of the day/month; labels are formatted from the dates
        chartXAxis.setLowerBound(0);
        chartXAxis.setUpperBound(points.size() - 1);
        chartXAxis.setTickUnit(points.size() > 12 ? 5 : 1);
        chartXAxis.setTickLabelFormatter(new StringConverter<>() {
            @Override
            public String toString(Number n) {
                int i = (int) Math.round(n.doubleValue());
                if (i < 0 || i >= points.size()) {
                    return "";
                }
                LocalDate d = points.get(i).period();
                return monthly ? d.format(MONTH_LABEL) : d.format(DAY_LABEL);
            }

            @Override
            public Number fromString(String s) {
                return null;
            }
        });

        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        for (int i = 0; i < points.size(); i++) {
            series.getData().add(new XYChart.Data<>(i, points.get(i).total()));
        }
        salesChart.getData().setAll(List.of(series));

        // Tooltips: nodes exist once the series is in the chart
        for (int i = 0; i < points.size(); i++) {
            SalesPoint p = points.get(i);
            Node node = series.getData().get(i).getNode();
            if (node != null) {
                String when = monthly ? p.period().format(MONTH_LONG) : p.period().format(DAY_LONG);
                Tooltip tip = new Tooltip(when + "\n" + MoneyUtil.formatWithCurrency(p.total())
                        + "\n" + p.invoices() + " فاتورة");
                tip.setShowDelay(Duration.millis(100));
                Tooltip.install(node, tip);
            }
        }

        BigDecimal total = points.stream().map(SalesPoint::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        long invoices = points.stream().mapToLong(SalesPoint::invoices).sum();
        chartSummaryLabel.setText("الإجمالي خلال " + trend.range().getLabelAr() + ": "
                + MoneyUtil.formatWithCurrency(total) + "  •  " + invoices + " فاتورة");
    }

    // ---------- Lists ----------

    private void setUpTables() {
        invoicesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        invoicesTable.setPlaceholder(placeholder("لا توجد فواتير بعد"));
        invNoColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().invoiceNo()));
        invCustomerColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().customerName()));
        invDateColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                c.getValue().saleDate() == null ? "" : c.getValue().saleDate().format(INVOICE_DATE)));
        invTotalColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(MoneyUtil.format(c.getValue().totalAmount())));
        invTotalColumn.setCellFactory(col -> styledCell(Function.identity(), "money-cell"));
        invStatusColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        invStatusColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(RecentInvoice inv, boolean empty) {
                super.updateItem(inv, empty);
                setText(null);
                if (empty || inv == null) {
                    setGraphic(null);
                    return;
                }
                Label badge;
                if (inv.isCancelled()) {
                    badge = badge("ملغاة", "badge-danger");
                } else if (inv.remainingAmount().signum() > 0) {
                    badge = badge("آجل " + MoneyUtil.format(inv.remainingAmount()), "badge-warning");
                } else {
                    badge = badge(inv.paymentMethod().getLabelAr(), "badge-success");
                }
                setGraphic(badge);
            }
        });
        invNoColumn.setMinWidth(130);
        invCustomerColumn.setPrefWidth(110);
        invDateColumn.setMinWidth(100);
        invTotalColumn.setMinWidth(70);
        invStatusColumn.setMinWidth(100);   // "آجل 12.345" must stay readable

        lowStockTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        lowStockTable.setPlaceholder(placeholder("لا توجد منتجات تحت الحد الأدنى"));
        lowNameColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        lowNameColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(LowStockItem item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                Label name = new Label(item.nameAr());
                name.getStyleClass().add("cell-primary");
                Label code = new Label(item.productCode());
                code.getStyleClass().add("cell-secondary");
                setGraphic(new VBox(1, name, code));
            }
        });
        lowQtyColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(
                QuantityUtil.format(c.getValue().quantity()) + " " + c.getValue().unitName()));
        lowQtyColumn.setCellFactory(col -> styledCell(Function.identity(), "qty-low"));
        lowMinColumn.setCellValueFactory(c -> new ReadOnlyStringWrapper(QuantityUtil.format(c.getValue().minimumStock())));
        lowNameColumn.setPrefWidth(190);
        lowQtyColumn.setPrefWidth(90);
        lowStockTable.getItems().addListener((javafx.collections.ListChangeListener<LowStockItem>) c ->
                lowStockCountLabel.setText(String.valueOf(lowStockTable.getItems().size())));
    }

    private void showTopProducts(List<TopProduct> products) {
        topProductsBox.getChildren().clear();
        if (products.isEmpty()) {
            topProductsBox.getChildren().add(placeholder("لا توجد مبيعات خلال آخر 30 يومًا"));
            return;
        }
        BigDecimal max = products.get(0).salesAmount();
        int rank = 1;
        for (TopProduct p : products) {
            Label number = new Label(String.valueOf(rank++));
            number.getStyleClass().add("rank-badge");

            Label name = new Label(p.nameAr());
            name.getStyleClass().add("cell-primary");
            Label qty = new Label(QuantityUtil.format(p.quantity()) + " " + p.unitName());
            qty.getStyleClass().add("cell-secondary");
            Label amount = new Label(MoneyUtil.formatWithCurrency(p.salesAmount()));
            amount.getStyleClass().add("top-amount");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox line = new HBox(8, name, spacer, amount);

            ProgressBar bar = new ProgressBar(max.signum() == 0 ? 0
                    : p.salesAmount().divide(max, 4, RoundingMode.HALF_UP).doubleValue());
            bar.getStyleClass().add("top-bar");
            bar.setMaxWidth(Double.MAX_VALUE);

            VBox info = new VBox(3, line, bar, qty);
            HBox.setHgrow(info, Priority.ALWAYS);
            HBox row = new HBox(10, number, info);
            topProductsBox.getChildren().add(row);
        }
    }

    private static <S> TableCell<S, String> styledCell(Function<String, String> text, String styleClass) {
        TableCell<S, String> cell = new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : text.apply(item));
            }
        };
        cell.getStyleClass().add(styleClass);
        return cell;
    }

    private static Label badge(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().addAll("badge", styleClass);
        return label;
    }

    private static Label placeholder(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted");
        return label;
    }

    // ---------- Database status ----------

    @FXML
    private void onRetryDatabase() {
        checkDatabase();
    }

    private void checkDatabase() {
        setDbStatus(DbState.CHECKING);

        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                return systemStatus.isBackendReachable();
            }
        };
        task.setOnSucceeded(e -> setDbStatus(task.getValue() ? DbState.CONNECTED : DbState.FAILED));
        task.setOnFailed(e -> setDbStatus(DbState.FAILED));
        runInBackground(task, "db-connection-check");
    }

    private void setDbStatus(DbState state) {
        dbStatusDot.getStyleClass().removeAll("status-ok", "status-error", "status-pending");
        dbStatusDot.getStyleClass().add(state.styleClass);
        dbStatusLabel.setText(state.title);
        dbStatusDetailLabel.setText(state == DbState.FAILED
                ? "تحقق من تشغيل SQL Server وإعدادات ملف application.properties"
                : systemStatus.backendDescription());
        retryDbButton.setDisable(state == DbState.CHECKING);
        footerDbLabel.setText("قاعدة البيانات: " + state.title);
    }

    private static void runInBackground(Task<?> task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
    }

    private enum DbState {
        CHECKING("جارٍ الاتصال بقاعدة البيانات...", "status-pending"),
        CONNECTED("متصل بقاعدة البيانات", "status-ok"),
        FAILED("غير متصل بقاعدة البيانات", "status-error");

        final String title;
        final String styleClass;

        DbState(String title, String styleClass) {
            this.title = title;
            this.styleClass = styleClass;
        }
    }
}
