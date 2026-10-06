package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.model.ReportType;
import com.almahwar.service.ReportService;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/** Reports home: one tab per group with a tile per report the user may open; a report opens in place. */
public class ReportsController {

    @FXML private StackPane pageHost;
    @FXML private VBox homePage;
    @FXML private Label emptyLabel;
    @FXML private TabPane categoryTabs;

    private final ReportService reports = AppContext.get().reports();

    @FXML
    private void initialize() {
        List<ReportType> available = reports.availableReports();
        ViewSupport.show(emptyLabel, available.isEmpty());
        for (ReportType.Category category : ReportType.Category.values()) {
            List<ReportType> inGroup = available.stream().filter(t -> t.getCategory() == category).toList();
            if (inGroup.isEmpty()) {
                continue;
            }
            FlowPane tiles = new FlowPane(14, 14);
            tiles.getStyleClass().add("report-tiles");
            for (ReportType type : inGroup) {
                tiles.getChildren().add(tile(type));
            }
            ScrollPane scroll = new ScrollPane(tiles);
            scroll.setFitToWidth(true);
            scroll.getStyleClass().add("content-scroll");
            Tab tab = new Tab(category.getLabelAr(), scroll);
            tab.setId("tab-" + category.name());
            categoryTabs.getTabs().add(tab);
        }
    }

    private Button tile(ReportType type) {
        Label title = new Label(type.getTitle());
        title.getStyleClass().add("report-tile-title");
        Label text = new Label(type.getDescription());
        text.getStyleClass().add("muted");
        text.setWrapText(true);
        VBox box = new VBox(6, title, text);
        box.setAlignment(Pos.TOP_RIGHT);
        Button b = new Button(null, box);
        b.setId("report-" + type.name());
        b.getStyleClass().addAll("card", "report-tile");
        b.setPrefSize(280, 96);
        b.setOnAction(e -> open(type));
        return b;
    }

    /** Opens a report in place of the home page. */
    void open(ReportType type) {
        replacePage(ReportViewPage.create(this, type));
    }

    /** Back to the reports home. */
    void closeReport() {
        pageHost.getChildren().removeIf(n -> n != homePage);
        homePage.setVisible(true);
    }

    void replacePage(Parent page) {
        pageHost.getChildren().removeIf(n -> n != homePage);
        homePage.setVisible(false);
        pageHost.getChildren().add(page);
    }
}
