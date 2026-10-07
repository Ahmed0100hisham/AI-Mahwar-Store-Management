-- API-owned baseline 1.0.0. Executed only on an empty, explicitly configured AlMahwarApi* database.
-- The Java initializer owns the transaction and application lock. No USE, CREATE DATABASE or business SQL.
CREATE TABLE dbo.api_schema_version (
    id INT NOT NULL CONSTRAINT PK_api_schema_version PRIMARY KEY CHECK (id = 1),
    version VARCHAR(20) NOT NULL,
    script_sha256 CHAR(64) NOT NULL,
    installed_at DATETIME2(7) NOT NULL DEFAULT SYSUTCDATETIME()
);
CREATE TABLE dbo.api_sessions (
    row_id BIGINT IDENTITY NOT NULL CONSTRAINT CX_api_sessions PRIMARY KEY CLUSTERED,
    sid UNIQUEIDENTIFIER NOT NULL CONSTRAINT UQ_api_sessions_sid UNIQUE NONCLUSTERED,
    user_id INT NOT NULL,
    password_version VARCHAR(64) NOT NULL,
    credential_fingerprint CHAR(64) NOT NULL,
    restricted BIT NOT NULL,
    device_label NVARCHAR(100) NULL,
    created_at DATETIME2(7) NOT NULL,
    last_activity_at DATETIME2(7) NOT NULL,
    idle_expires_at DATETIME2(7) NOT NULL,
    absolute_expires_at DATETIME2(7) NOT NULL,
    revoked_at DATETIME2(7) NULL,
    revocation_reason VARCHAR(32) NULL,
    CONSTRAINT CK_api_sessions_expiry CHECK (idle_expires_at <= absolute_expires_at)
);
CREATE INDEX IX_api_sessions_user ON dbo.api_sessions(user_id) INCLUDE (sid, revoked_at);
CREATE INDEX IX_api_sessions_retention ON dbo.api_sessions(absolute_expires_at, revoked_at);
CREATE TABLE dbo.api_refresh_tokens (
    token_id BIGINT IDENTITY NOT NULL CONSTRAINT PK_api_refresh_tokens PRIMARY KEY,
    sid UNIQUEIDENTIFIER NOT NULL,
    token_hash BINARY(32) NOT NULL CONSTRAINT UQ_api_refresh_hash UNIQUE,
    issued_at DATETIME2(7) NOT NULL DEFAULT SYSUTCDATETIME(),
    consumed_at DATETIME2(7) NULL,
    CONSTRAINT FK_api_refresh_session FOREIGN KEY (sid) REFERENCES dbo.api_sessions(sid) ON DELETE CASCADE
);
CREATE INDEX IX_api_refresh_session ON dbo.api_refresh_tokens(sid);
-- Version + checksum inserted by the initializer in this same transaction after all DDL succeeds.
