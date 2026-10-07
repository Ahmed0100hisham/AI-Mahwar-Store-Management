-- Run deliberately as DBA against master. Runtime API logins must not have CREATE DATABASE permission.
-- Business AlMahwarDB is never opened or changed. The application initializes tables only with explicit opt-in.
USE [master];
GO
IF DB_ID(N'AlMahwarApiDB') IS NULL
    CREATE DATABASE [AlMahwarApiDB];
GO
-- Provision a separate external-secret login/user in AlMahwarApiDB.
-- For baseline initialization only: grant CREATE TABLE and ALTER on dbo; then remove those privileges.
-- Runtime: SELECT/INSERT/UPDATE on api_sessions and api_refresh_tokens, SELECT on api_schema_version.
-- API-session users also require permission to acquire transaction-owned application locks (public by default).
