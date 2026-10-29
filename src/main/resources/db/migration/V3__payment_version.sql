-- Optimistic lock for concurrent /process, /refund and re-initiate calls (S-77, S-79).
-- Existing rows start at version 0; nothing else changes.
ALTER TABLE payments ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
