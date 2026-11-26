-- Select the application's database in your SQL client before running this file.
-- Widen only the chat message body; preserve existing rows and NOT NULL.
-- Needed if automatic Hibernate schema update did not widen the existing column.
ALTER TABLE chat_messages MODIFY COLUMN text LONGTEXT NOT NULL;

-- Verify the resulting schema without reading message contents.
SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'chat_messages'
  AND COLUMN_NAME = 'text';
