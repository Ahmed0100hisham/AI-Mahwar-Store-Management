# Al Mahwar Store Management API

REST API (Spring Boot 4.1, Java 17) for the Al Mahwar mobile clients — an independent project next to the frozen
desktop application. Design and security: [../docs/API_ARCHITECTURE.md](../docs/API_ARCHITECTURE.md).

```bash
# once, from the repository root: the released Desktop 1.0.1 shared core runtime dependency
mvn install

# from api/: build and run all tests that need no database
mvn verify

# SQL Server integration tests (temporary database, created and dropped by the test; never AlMahwarDB)
ALMAHWAR_IT_DB_HOST=localhost ALMAHWAR_IT_DB_PORT=1433 ALMAHWAR_IT_DB_USER=... ALMAHWAR_IT_DB_PASSWORD=... \
  mvn verify -Dalmahwar.it=true

# run (configuration from environment variables or api/config/application.properties — see the example file)
ALMAHWAR_DB_USER=... ALMAHWAR_DB_PASSWORD=... ALMAHWAR_API_JWT_SECRET="$(openssl rand -base64 48)" \
  java -jar target/almahwar-api-0.1.0-SNAPSHOT.jar            # add --spring.profiles.active=dev for Swagger UI
```

Endpoints (Phase 1): `GET /api/v1/health`, `GET /api/v1/health/ready`, `POST /api/v1/auth/login`,
`GET /api/v1/auth/me`, `GET /api/v1/products`.

The API consumes `com.almahwar:almahwar-store-management:jar:core:1.0.1` with JavaFX excluded. Apache POI remains for shared report-export classes. Install the core first, then build `api/`; no reactor or absolute JAR path is used. The core owns business transactions; do not add Spring `@Transactional` around shared-core services. Phase 2 and Flutter remain unimplemented. See the architecture document for adapters and the duplicate inventory.
