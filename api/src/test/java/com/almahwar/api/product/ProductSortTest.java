package com.almahwar.api.product;

import com.almahwar.api.error.FieldValidationException;
import com.almahwar.api.web.PageQuery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Only allow-listed sort fields reach SQL; LIKE wildcards are escaped like the desktop; page bounds. */
class ProductSortTest {
    private static final class LikeProbe extends com.almahwar.dao.BaseDao {
        static String escape(String text) { return likeContains(text); }
    }

    @Test
    void allowedSorts() {
        assertThat(ProductSort.parse(null).sql()).isEqualTo("p.name_ar ASC, p.product_id ASC");
        assertThat(ProductSort.parse("").sql()).isEqualTo("p.name_ar ASC, p.product_id ASC");
        assertThat(ProductSort.parse("code").sql()).isEqualTo("p.product_code ASC, p.product_id ASC");
        assertThat(ProductSort.parse("salePrice,desc").sql()).isEqualTo("p.sale_price DESC, p.product_id DESC");
        assertThat(ProductSort.parse(" quantity , ASC ").sql()).isEqualTo("p.quantity ASC, p.product_id ASC");
    }

    @Test
    void everythingElseIsRefused() {
        for (String bad : new String[] {"purchasePrice", "p.name_ar", "name_ar", "NAME", "name,up", "name,asc,x",
                "name;DROP TABLE dbo.Users--", "name) UNION SELECT password_hash FROM dbo.Users--", "1", ","}) {
            assertThatThrownBy(() -> ProductSort.parse(bad)).as(bad).isInstanceOf(FieldValidationException.class)
                    .satisfies(e -> assertThat(((FieldValidationException) e).field()).isEqualTo("sort"));
        }
    }

    @Test
    void likeTextIsMatchedLiterallyAsOnTheDesktop() {
        assertThat(LikeProbe.escape(" 50%_[a] ")).isEqualTo("%50[%][_][[]a]%");
    }

    @Test
    void pageBounds() {
        assertThat(new PageQuery(3, 20).offset()).isEqualTo(60);
        assertThatThrownBy(() -> new PageQuery(-1, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageQuery(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageQuery(0, 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PageQuery(10_001, 20)).isInstanceOf(IllegalArgumentException.class);
    }
}
