-- Review/apply to the intended MySQL schema only. No existing rows are backfilled.
-- Inspect information_schema.columns first; skip ALTER if this nullable column already exists.
SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'chat_messages'
  AND column_name = 'recommendation_provenance';

ALTER TABLE chat_messages ADD COLUMN recommendation_provenance LONGTEXT NULL;
