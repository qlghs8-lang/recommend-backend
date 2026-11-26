-- UNAPPLIED REFERENCE ONLY. Separate review versions; raw draft CHECK constraints remain unchanged.
CREATE TABLE `catalog_metadata_review_versions` (
 `version_sha` CHAR(64) NOT NULL,
 `parent_sha` CHAR(64) NULL,
 `payload` JSON NOT NULL,
 `created_at` DATETIME(6) NOT NULL,
 PRIMARY KEY (`version_sha`),
 FOREIGN KEY (`parent_sha`) REFERENCES `catalog_metadata_review_versions` (`version_sha`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE `catalog_metadata_review_records` (
 `record_sha` CHAR(64) NOT NULL,
 `version_sha` CHAR(64) NOT NULL,
 `row_sha` CHAR(64) NOT NULL,
 `payload` JSON NOT NULL,
 `created_at` DATETIME(6) NOT NULL,
 PRIMARY KEY (`record_sha`),
 FOREIGN KEY (`version_sha`) REFERENCES `catalog_metadata_review_versions` (`version_sha`),
 UNIQUE KEY uq_review_version_row (`version_sha`,`row_sha`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE `catalog_metadata_release_candidates` (
 `release_sha` CHAR(64) NOT NULL,
 `version_sha` CHAR(64) NOT NULL,
 `active` TINYINT NOT NULL,
 `payload` JSON NOT NULL,
 `created_at` DATETIME(6) NOT NULL,
 PRIMARY KEY (`release_sha`),
 FOREIGN KEY (`version_sha`) REFERENCES `catalog_metadata_review_versions` (`version_sha`),
 UNIQUE KEY uq_release_version (`version_sha`),
 CHECK (active=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
