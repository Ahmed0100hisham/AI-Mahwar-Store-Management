package com.almahwar.api.core;

import com.almahwar.api.product.ProductQueryService;
import com.almahwar.dao.ConnectionSource;
import com.almahwar.service.SettingsService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.*;

class SharedCoreRuntimeTest {
    @Test
    void runtimeUsesTheOfficialClassifierWithoutDesktopClassesOrResources() throws Exception {
        Path artifact = Path.of(ConnectionSource.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertThat(artifact.getFileName().toString()).isEqualTo("almahwar-store-management-1.0.1-core.jar");
        try (JarFile jar = new JarFile(artifact.toFile())) {
            var entries = jar.stream().map(e -> e.getName()).toList();
            assertThat(entries).contains("com/almahwar/dao/ConnectionSource.class", "com/almahwar/service/ProductServiceImpl.class");
            assertThat(entries).noneMatch(n -> n.contains("/controller/") || n.endsWith(".fxml") || n.endsWith(".css")
                    || n.startsWith("javafx/") || n.startsWith("org/springframework/") || n.startsWith("com/almahwar/api/")
                    || n.contains("SessionManager") || n.contains("AuthServiceImpl")
                    || n.contains("BackupRestoreServiceImpl") || n.contains("HealthCheckServiceImpl")
                    || n.contains("SystemStatusServiceImpl") || n.contains("DatabaseBackupDao")
                    || n.contains("BackupHistoryDao") || n.contains("DatabaseHealthDao"));
        }
        assertThatThrownBy(() -> Class.forName("javafx.application.Application")).isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName("com.almahwar.controller.LoginController")).isInstanceOf(ClassNotFoundException.class);
        assertThat(SettingsService.REQUIRED_SCHEMA_VERSION).isEqualTo("1.10.0");
    }

    @Test
    void coreCallsHaveNoSpringTransactionWrapper() {
        assertThat(ProductQueryService.class.getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNull();
        for (var method : ProductQueryService.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNull();
        }
    }
}
