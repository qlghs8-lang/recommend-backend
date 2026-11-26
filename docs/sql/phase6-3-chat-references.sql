-- Use the application's selected database. Run only if Hibernate schema update has not added this column.
-- Existing messages remain valid; they have no trusted reference slots until a new answer is generated.
ALTER TABLE chat_messages ADD COLUMN content_references VARCHAR(128) NULL;

SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_messages' AND COLUMN_NAME = 'content_references';
