-- Additive, nullable public metadata. Execute only if Hibernate ddl-auto=update has not already added these columns.
-- Check information_schema first. No guessed values or zero/default durations; existing records remain NULL.
-- runtime_minutes = MOVIE full-feature minutes; episode_runtime_minutes = TV representative episode minutes.
ALTER TABLE contents ADD COLUMN runtime_minutes INT NULL;
ALTER TABLE contents ADD COLUMN episode_runtime_minutes INT NULL;

SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contents'
  AND COLUMN_NAME IN ('runtime_minutes', 'episode_runtime_minutes');

-- Public coverage only; no user/reaction/chat data is queried.
SELECT type, COUNT(*) AS total,
       SUM(runtime_minutes BETWEEN 1 AND 1440) AS known_movie_runtimes,
       SUM(episode_runtime_minutes BETWEEN 1 AND 1440) AS known_episode_runtimes
FROM contents GROUP BY type;
