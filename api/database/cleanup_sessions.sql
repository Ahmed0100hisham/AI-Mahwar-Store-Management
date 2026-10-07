-- Run as a separate maintenance principal in AlMahwarApiDB; repeat bounded batches until zero rows deleted.
-- Keep ALL hashes for a live session so replay of any consumed credential remains detectable.
-- Never run against the business database. No runtime DELETE permission is required.
IF DB_NAME() NOT LIKE N'AlMahwarApi%' OR OBJECT_ID(N'dbo.api_schema_version',N'U') IS NULL
    THROW 51000,'Not an API session database',1;
DELETE TOP (500) FROM dbo.api_sessions
WHERE (revoked_at < DATEADD(DAY,-30,SYSUTCDATETIME()))
   OR (absolute_expires_at < DATEADD(DAY,-30,SYSUTCDATETIME()))
   OR (idle_expires_at < DATEADD(DAY,-30,SYSUTCDATETIME()));
-- FK cascade removes the complete terminal family's hash history, never individual hashes of a live family.
