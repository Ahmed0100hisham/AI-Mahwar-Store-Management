package com.almahwar.api.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SessionConfigurationTest {
    private DatabaseProperties business(String user,boolean encrypted,boolean trust) {
        return new DatabaseProperties("sql.local",1433,"AlMahwarDB",user,"test-business-secret",encrypted,trust,null,5,10);
    }
    private SessionDatabaseProperties sessions(String name,String user,boolean encrypted,boolean trust) {
        return new SessionDatabaseProperties("sql.local",1433,name,user,"test-session-secret",encrypted,trust,null,5,10,false);
    }
    @Test void independentCredentialsAndSafeTargetAreRequired() {
        try(var validator=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var v=validator.getValidator();
            assertThat(v.validate(sessions("AlMahwarDB","api",true,false))).isNotEmpty();
            assertThat(v.validate(sessions("AlMahwarApiDB;databaseName=AlMahwarDB","api",true,false))).isNotEmpty();
            assertThat(v.validate(sessions("AlMahwarApiDB","",true,false))).isNotEmpty();
            var missing=new SessionDatabaseProperties("sql.local",1433,"AlMahwarApiDB","api","",true,false,null,5,10,false);
            assertThat(v.validate(missing)).isNotEmpty();
        }
        assertThatThrownBy(()->sessions("AlMahwarDB","api",true,false).validateAgainst(business("biz",true,false),true))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void saIsRejectedForEitherDatabaseEvenInDevelopment() {
        assertThatThrownBy(()->sessions("AlMahwarApiDB"," sa ",true,false).validateAgainst(business("biz",true,false),true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->sessions("AlMahwarApiDB","api",true,false).validateAgainst(business("SA",true,false),true))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void productionRequiresTlsWithVerifiedCertificatesOnBothConnections() {
        for(var p:java.util.List.of(sessions("AlMahwarApiDB","api",false,false),sessions("AlMahwarApiDB","api",true,true)))
            assertThatThrownBy(()->p.validateAgainst(business("biz",true,false),false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->sessions("AlMahwarApiDB","api",true,false).validateAgainst(business("biz",true,true),false))
                .isInstanceOf(IllegalStateException.class);
        sessions("AlMahwarApiDB","api",true,true).validateAgainst(business("biz",true,true),true);
    }
    @Test void configurationDoesNotPrintPasswordsOrPutCredentialsInJdbcUrl() {
        var p=sessions("AlMahwarApiDB","api-runtime-user",true,false);
        assertThat(p.toString()).doesNotContain("test-session-secret");
        assertThat(p.jdbcUrl()).doesNotContain("test-session-secret","api-runtime-user");
        assertThat(p.initialize()).isFalse();
    }
    @Test void environmentBindingKeepsIndependentApiCredentialsAndHigherPriorityOverrides() {
        var env=new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().replace("systemEnvironment",new org.springframework.core.env.SystemEnvironmentPropertySource(
                "systemEnvironment",java.util.Map.of("ALMAHWAR_API_DB_HOST","sql.local","ALMAHWAR_API_DB_USER","session-user",
                "ALMAHWAR_API_DB_PASSWORD","session-env-secret","ALMAHWAR_DB_PASSWORD","business-env-secret")));
        var binder=org.springframework.boot.context.properties.bind.Binder.get(env);
        var bound=binder.bind("almahwar.api.db",SessionDatabaseProperties.class).get();
        assertThat(bound.user()).isEqualTo("session-user");
        assertThat(bound.password()).isEqualTo("session-env-secret").isNotEqualTo("business-env-secret");
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("commandLineArgs",
                java.util.Map.of("almahwar.api.db.password","command-line-test-secret")));
        assertThat(org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("almahwar.api.db",SessionDatabaseProperties.class).get().password()).isEqualTo("command-line-test-secret");
    }
}
