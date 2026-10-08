# Manager backend v1 readiness review

Phase 5 completes the requested read surface on the accepted Phase 4 baseline. This is an implementation/review candidate; it does not deploy the API, approve production infrastructure or start Flutter. Verification results are recorded in `API_PHASE5_FINAL_MANAGER_REPORT.md`; the client-facing freeze is `API_MANAGER_CONTRACT_V1.md`.

| Area | Contract/readiness criterion |
|---|---|
| Authentication and sessions | Released password hashing/lock rules, signed short-lived access, live identity/session checks, refresh rotation/replay handling, own-session ownership and logout-all |
| Passwords and account management | Must-change restricted sessions, own password change, administrative reset/disable invalidation, enable cannot restore old sessions; live role changes |
| Administration and audit | ADMIN permissions, core last-active-admin/self/concurrency protections, no permanent deletion, safe audit projection |
| Manager reads | Existing dashboard/sales/profit/expenses/cash/inventory/product movement/customer/supplier/account routes preserved; five new GET routes |
| Documents | Stored invoice header/tax/payment snapshots, paged items/returns, historical cost permissions; six real quotation statuses, pure expiry tracking and actual sale references |
| Daily summary | Phase 3 accounting reused by activity date; independent permission filtering; current inventory explicitly dated |
| DTO/precision/time/paging | Purpose-built projections, three-decimal strings, explicit SQL-local dates versus UTC security timestamps, common zero-based pages, bounded detail collections |
| Search/query safety | Literal LIKE escaping, bound values, allowlisted ordering with unique IDs, explicit projections, no per-row DAO calls/full-table Java aggregation/dirty reads |
| Errors/cache/correlation | Safe ApiError with requestId, appropriate validation/auth/not-found/outage statuses, no-store Manager responses |
| Database boundary | Distinct business/API catalogs and credentials, frozen versions, no Phase 5 business writes; disposable-only test mutations and cleanup |
| Packaging/OpenAPI | Shared Core 1.0.1 plus Phase 3/4/5 classes, no JavaFX/JPA/ORM, no logs/scripts/secrets, development-only docs by default |
| Production configuration | Empty bundled secrets, production encryption/certificate validation, non-sa accounts, exact CORS origins, initialization default false, safe health/readiness |

## Production environment review (names only, no values)

Provide `ALMAHWAR_DB_HOST`, `ALMAHWAR_DB_PORT`, `ALMAHWAR_DB_NAME`, `ALMAHWAR_DB_USER`, `ALMAHWAR_DB_PASSWORD` and independent `ALMAHWAR_API_DB_HOST`, `ALMAHWAR_API_DB_PORT`, `ALMAHWAR_API_DB_NAME`, `ALMAHWAR_API_DB_USER`, `ALMAHWAR_API_DB_PASSWORD`. Provide a deployment-specific `ALMAHWAR_API_JWT_SECRET` containing base64 of at least 32 random bytes. Configure `ALMAHWAR_API_PORT` and, only for browser clients, exact `ALMAHWAR_API_CORS_ALLOWED_ORIGINS`. Secret values belong in the deployment secret store or ignored external configuration, never in source, documentation, process command lines or logs.

Bundled application.properties leaves both DB usernames/passwords and JWT secret empty. API credentials have no business-credential fallback. `SessionDatabaseProperties.validateAgainst` rejects either `sa` login and identical catalog names; outside explicit dev it rejects disabled encryption or trusted/self-signed certificates on either DB. Use certificate-valid encrypted connections, with `ALMAHWAR_API_DB_HOST_NAME_IN_CERTIFICATE` only when the validated server certificate requires it. Keep external properties `almahwar.db.encrypt` and `almahwar.api.db.encrypt` true and both `trust-server-certificate` properties false. Never enable certificate trust overrides in production.

API database initialization is false by default; `spring.sql.init.mode=never`. Phase 5 does not execute migrations. A DBA must provision the existing frozen schema before runtime; any one-time API initialization belongs to the separate established provisioning process, with DDL permissions removed afterward. Business runtime permissions remain limited to the existing reads and released authentication/administration/audit operations; Phase 5 adds only reads. The integration harness's create-database permissions belong solely to a temporary non-sa development login which is removed after testing.

Keep `SPRING_PROFILES_ACTIVE` free of dev in production. Bundled OpenAPI and Swagger UI are disabled; dev explicitly enables them. Do not override documentation switches in production. CORS rejects wildcard origins. The API supplies no TLS deployment in this change: configure HTTPS at a trusted reverse proxy, restrict direct backend access, and explicitly configure forwarded-header trust for that proxy. Align the SQL business clock to Kuwait and synchronize operational clocks. Use deployment-side rate limits for login and costly user/password operations. No deployment was performed.

Liveness `/api/v1/health` returns only UP. Readiness `/api/v1/health/ready` checks business compatibility and API session compatibility and returns only READY/NOT_READY (200/503), without host/user/schema details. A short readiness cache is inherited. Request IDs are validated/generated by RequestIdFilter and included in responses/errors/log context. Logging records safe action/identity metadata without request bodies, Authorization headers, passwords, refresh credentials or JWT values.

## Known limits to carry into client design

Quotation GETs intentionally do not run Desktop automatic expiry mutations: show stored status plus pastValidity. Invoice payments are stored invoice snapshots, not a newly invented payment history or post-return customer balance. Invoice list return cutoffs and all-time detail returns differ explicitly. Inventory inside daily summary is current, separately dated. Separate read statements may observe concurrent changes; no snapshot transaction is promised. Broad literal searches/deep offset pages require production measurement; no schema/index changes were made. Inherited own-session history remains an unpaginated array; monitor retention/history growth and review any future compatible pagination extension separately. SQL 1205 document-number deadlock remains unchanged and outside this read-only phase.

These limits do not remove any requested Manager v1 capability. The backend provides management/monitoring, audit and permission-protected account administration; it deliberately supplies no sale/quotation/payment/return/purchase/expense/stock mutation API or mobile POS.

The final readiness dependency list includes sales, returns, quotations/items, parties, expenses, cash, stock movements and ledger tables, alongside the inherited catalog/identity/audit tables. A missing Manager table yields safe NOT_READY; its name remains server-side.

Read-only local environment audit: the existing AlMahwarDB reports schema 1.10.0. A permanent AlMahwarApiDB is not provisioned on this development SQL Server. Its unchanged schema 1.0.0 is exercised in independent disposable API catalogs, including packaged HTTP readiness. Provisioning the existing API security schema and production HTTPS/configuration is a deployment prerequisite outside this implementation-only phase, not a missing API capability. No permanent database was created or migrated.

Final verification completed: 637 API tests (all inherited 504 plus Phase 5 133), zero failures/errors/skips; Desktop 373 discovered / 168 executed / 205 opt-in skipped / zero failures/errors. Packaged HTTP and the complete method/path inventory passed. Final cleanup reports zero disposable databases and temporary logins; packaged dependency/class/default/secret audits are clean. Code and contract are ready for review, with deployment prerequisites above.
