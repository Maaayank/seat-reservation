-- Baseline migration. Domain tables arrive in later migrations (V2+).
-- Keeps Flyway's history table present from the first deploy.
SELECT 1;
