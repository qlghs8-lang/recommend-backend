-- UNAPPLIED DRAFT SCHEMA. CLI does not execute DDL. DDL is separate from the data transaction.
SET SESSION lock_wait_timeout=5;
CREATE TABLE `catalog_metadata_batches` (
 `batch_sha` CHAR(64) NOT NULL,
 `projection_sha` CHAR(64) NOT NULL,
 `manifest` JSON NOT NULL,
 `created_at` DATETIME(6) NOT NULL,
 PRIMARY KEY (`batch_sha`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE `catalog_metadata_sources` (
 `row_sha` CHAR(64) NOT NULL,
 `batch_sha` CHAR(64) NOT NULL,
 `content_id` BIGINT NOT NULL,
 `source_kind` VARCHAR(32) NOT NULL,
 `payload` JSON NOT NULL,
 `created_at` DATETIME(6) NOT NULL,
 PRIMARY KEY (`row_sha`),
 CONSTRAINT fk_md_1_0 FOREIGN KEY (`batch_sha`) REFERENCES `catalog_metadata_batches` (`batch_sha`) ON DELETE CASCADE,
 CONSTRAINT fk_md_1_1 FOREIGN KEY (`content_id`) REFERENCES `contents` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE `catalog_content_keywords` (
 `row_sha` CHAR(64) NOT NULL,
 `batch_sha` CHAR(64) NOT NULL,
 `source_sha` CHAR(64) NOT NULL,
 `content_id` BIGINT NOT NULL,
 `keyword_id` BIGINT NOT NULL,
 `label` VARCHAR(128) NOT NULL,
 `approved` TINYINT NOT NULL,
 `active` TINYINT NOT NULL,
 `payload` JSON NOT NULL,
 `created_at` DATETIME(6) NOT NULL,
 PRIMARY KEY (`row_sha`),
 CONSTRAINT fk_md_2_0 FOREIGN KEY (`batch_sha`) REFERENCES `catalog_metadata_batches` (`batch_sha`) ON DELETE CASCADE,
 CONSTRAINT fk_md_2_1 FOREIGN KEY (`content_id`) REFERENCES `contents` (`id`) ON DELETE CASCADE,
 CONSTRAINT fk_md_2_2 FOREIGN KEY (`source_sha`) REFERENCES `catalog_metadata_sources` (`row_sha`) ON DELETE CASCADE,
 UNIQUE KEY uq_md_keyword_source (`source_sha`,`keyword_id`),
 CHECK (approved=0 AND active=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE `catalog_content_aliases` (
 `row_sha` CHAR(64) NOT NULL,
 `batch_sha` CHAR(64) NOT NULL,
 `source_sha` CHAR(64) NOT NULL,
 `content_id` BIGINT NOT NULL,
 `alias_kind` VARCHAR(32) NOT NULL,
 `normalized_title` VARCHAR(500) NOT NULL,
 `approved` TINYINT NOT NULL,
 `active` TINYINT NOT NULL,
 `payload` JSON NOT NULL,
 `created_at` DATETIME(6) NOT NULL,
 PRIMARY KEY (`row_sha`),
 CONSTRAINT fk_md_3_0 FOREIGN KEY (`batch_sha`) REFERENCES `catalog_metadata_batches` (`batch_sha`) ON DELETE CASCADE,
 CONSTRAINT fk_md_3_1 FOREIGN KEY (`content_id`) REFERENCES `contents` (`id`) ON DELETE CASCADE,
 CONSTRAINT fk_md_3_2 FOREIGN KEY (`source_sha`) REFERENCES `catalog_metadata_sources` (`row_sha`) ON DELETE CASCADE,
 CHECK (approved=0 AND active=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE `catalog_metadata_field_reviews` (
 `row_sha` CHAR(64) NOT NULL,
 `batch_sha` CHAR(64) NOT NULL,
 `source_sha` CHAR(64) NOT NULL,
 `content_id` BIGINT NOT NULL,
 `field_name` VARCHAR(32) NOT NULL,
 `decision` VARCHAR(48) NOT NULL,
 `approved` TINYINT NOT NULL,
 `active` TINYINT NOT NULL,
 `payload` JSON NOT NULL,
 `created_at` DATETIME(6) NOT NULL,
 PRIMARY KEY (`row_sha`),
 CONSTRAINT fk_md_4_0 FOREIGN KEY (`batch_sha`) REFERENCES `catalog_metadata_batches` (`batch_sha`) ON DELETE CASCADE,
 CONSTRAINT fk_md_4_1 FOREIGN KEY (`content_id`) REFERENCES `contents` (`id`) ON DELETE CASCADE,
 CONSTRAINT fk_md_4_2 FOREIGN KEY (`source_sha`) REFERENCES `catalog_metadata_sources` (`row_sha`) ON DELETE CASCADE,
 UNIQUE KEY uq_md_field_source (`source_sha`,`field_name`),
 CHECK (approved=0 AND active=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE `catalog_metadata_runtime_candidates` (
 `row_sha` CHAR(64) NOT NULL,
 `batch_sha` CHAR(64) NOT NULL,
 `source_sha` CHAR(64) NOT NULL,
 `content_id` BIGINT NOT NULL,
 `target_field` VARCHAR(32) NOT NULL,
 `minutes` INT NOT NULL,
 `approved` TINYINT NOT NULL,
 `active` TINYINT NOT NULL,
 `payload` JSON NOT NULL,
 `created_at` DATETIME(6) NOT NULL,
 PRIMARY KEY (`row_sha`),
 CONSTRAINT fk_md_5_0 FOREIGN KEY (`batch_sha`) REFERENCES `catalog_metadata_batches` (`batch_sha`) ON DELETE CASCADE,
 CONSTRAINT fk_md_5_1 FOREIGN KEY (`content_id`) REFERENCES `contents` (`id`) ON DELETE CASCADE,
 CONSTRAINT fk_md_5_2 FOREIGN KEY (`source_sha`) REFERENCES `catalog_metadata_sources` (`row_sha`) ON DELETE CASCADE,
 UNIQUE KEY uq_md_time_source (`source_sha`,`target_field`),
 CHECK (minutes BETWEEN 1 AND 1440 AND target_field IN ('runtime_minutes','episode_runtime_minutes')),
 CHECK (approved=0 AND active=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
