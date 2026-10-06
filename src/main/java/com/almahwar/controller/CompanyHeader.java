package com.almahwar.controller;

import com.almahwar.config.AppContext;
import com.almahwar.model.CompanySettings;
import com.almahwar.model.SystemSettings;
import com.almahwar.service.SettingsService;
import com.almahwar.util.PhoneNumbers;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The company block of every printed document (invoice, purchase, quotation, report), built from the central
 * company profile and logo ({@link SettingsService}). Load the data with {@link #load()} off the JavaFX thread,
 * then build the nodes on it.
 */
final class CompanyHeader {

    /** Company profile, system settings and logo bytes ({@code null} when no logo is stored). */
    record Data(CompanySettings company, SystemSettings system, byte[] logo) {
    }

    private CompanyHeader() {
    }

    /** Reads the current settings (database call: not on the JavaFX thread). */
    static Data load() {
        SettingsService s = AppContext.get().settings();
        return new Data(s.company(), s.system(), s.logo().orElse(null));
    }

    /** Logo + Arabic / English name, address, phones, tax and CR numbers. */
    static HBox brand(Data d) {
        CompanySettings c = d.company();
        Label name = new Label(c.nameAr());
        name.getStyleClass().add("print-company");
        name.setId("printCompanyName");
        VBox box = new VBox(2, name);
        List<String> lines = new ArrayList<>();
        String en = c.nameEn();
        String place = join(" — ", c.address(), c.address() != null && c.address().equals(c.country()) ? null : c.country());
        lines.add(join(" — ", en, place));
        List<String> phones = new ArrayList<>();
        if (c.phone() != null) {
            phones.add(PhoneNumbers.format(c.phone()));
        }
        if (c.phone2() != null) {
            phones.add(PhoneNumbers.format(c.phone2()));
        }
        lines.add(phones.isEmpty() ? null : "هاتف: " + String.join(" / ", phones));
        lines.add(c.email());
        lines.add(join("   ", c.taxNumber() == null ? null : "الرقم الضريبي: " + c.taxNumber(),
                c.crNumber() == null ? null : "السجل التجاري: " + c.crNumber()));
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                Label l = new Label(line);
                l.getStyleClass().add("print-muted");
                box.getChildren().add(l);
            }
        }
        HBox brand = new HBox(12, box);
        brand.setAlignment(Pos.CENTER_LEFT);
        ImageView logo = logo(d, 56);
        if (logo != null) {
            brand.getChildren().add(0, logo);
        }
        return brand;
    }

    /** The stored logo, or {@code null} when there is none (or it cannot be shown). */
    static ImageView logo(Data d, double height) {
        if (d.logo() == null) {
            return null;
        }
        Image image = new Image(new ByteArrayInputStream(d.logo()));
        if (image.isError()) {
            return null;
        }
        ImageView view = new ImageView(image);
        view.setFitHeight(height);
        view.setPreserveRatio(true);
        view.setId("printLogo");
        return view;
    }

    /** A footer line, or {@code null} when the text is empty. */
    static Label footer(String text, String id) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setAlignment(Pos.CENTER);
        l.getStyleClass().add("print-thanks");
        l.setId(id);
        return l;
    }

    private static String join(String sep, String a, String b) {
        boolean ha = a != null && !a.isBlank();
        boolean hb = b != null && !b.isBlank();
        return ha && hb ? a + sep + b : ha ? a : hb ? b : null;
    }
}
