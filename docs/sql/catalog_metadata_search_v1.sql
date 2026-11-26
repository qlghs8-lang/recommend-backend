-- Append-only discovery activation events. Raw/review candidates remain inactive.
CREATE TABLE `catalog_metadata_search_events` (
 event_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 event_sha CHAR(64) NOT NULL UNIQUE,
 previous_sha CHAR(64) NULL UNIQUE,
 enabled TINYINT NOT NULL,
 release_sha CHAR(64) NOT NULL,
 search_sha CHAR(64) NOT NULL,
 payload LONGTEXT NOT NULL,
 event_body LONGTEXT NOT NULL,
 created_at DATETIME(6) NOT NULL,
 FOREIGN KEY(previous_sha) REFERENCES `catalog_metadata_search_events`(event_sha),
 CHECK(enabled IN (0,1)), CHECK(OCTET_LENGTH(payload)<=200000), CHECK(OCTET_LENGTH(event_body)<=4096)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
