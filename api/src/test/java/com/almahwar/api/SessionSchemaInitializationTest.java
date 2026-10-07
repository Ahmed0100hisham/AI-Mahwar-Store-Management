package com.almahwar.api;

import com.almahwar.api.config.SessionDatabaseProperties;
import com.almahwar.api.session.ApiSessionSchemaRepository;
import com.almahwar.api.support.TemporaryApiDatabase;
import com.almahwar.api.support.TemporaryDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.assertj.core.api.Assertions.*;

/** Independent lifecycle tests run even when a Spring startup check correctly refuses a database. */
@EnabledIfSystemProperty(named="almahwar.it",matches="true")
class SessionSchemaInitializationTest {
    private SessionDatabaseProperties settings(boolean initialize) {
        return new SessionDatabaseProperties(TemporaryDatabase.host(),Integer.parseInt(TemporaryDatabase.port()),
                TemporaryApiDatabase.NAME,TemporaryDatabase.user(),TemporaryDatabase.password(),true,
                TemporaryDatabase.trustServerCertificate(),null,1,2,initialize);
    }
    @Test void emptyDatabaseRequiresOptInThenInitializesAndRechecksDeterministically() throws Exception {
        TemporaryApiDatabase.create();
        try {
            var props=settings(false);
            var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource(props.jdbcUrl(),props.user(),props.password());
            var verifier=new ApiSessionSchemaRepository(ds,props);
            assertThat(verifier.compatible()).isFalse();
            assertThatThrownBy(verifier::initializeOrVerify).isInstanceOf(IllegalStateException.class);
            var initializer=new ApiSessionSchemaRepository(ds,settings(true));
            initializer.initializeOrVerify();initializer.initializeOrVerify();verifier.initializeOrVerify();
            assertThat(verifier.compatible()).isTrue();
            TemporaryApiDatabase.exec("UPDATE dbo.api_schema_version SET script_sha256=REPLICATE('0',64) WHERE id=1");
            assertThatThrownBy(initializer::initializeOrVerify).isInstanceOf(IllegalStateException.class);
        } finally { TemporaryApiDatabase.drop(); }
    }
    @Test void partialDatabaseIsNotRepaired() throws Exception {
        TemporaryApiDatabase.create();
        try {
            TemporaryApiDatabase.exec("CREATE TABLE dbo.unrelated_table (id int)");
            var p=settings(true);
            var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource(p.jdbcUrl(),p.user(),p.password());
            assertThatThrownBy(()->new ApiSessionSchemaRepository(ds,p).initializeOrVerify()).isInstanceOf(IllegalStateException.class);
            assertThat(TemporaryApiDatabase.query("SELECT COUNT(*) FROM sys.tables")).isEqualTo(1);
        } finally { TemporaryApiDatabase.drop(); }
    }
}
