-- Additive v2 identities; do not rewrite legacy content, layout hashes or reference snapshots.
ALTER TABLE knowledge_documents DROP CHECK ck_knowledge_source,
  ADD CONSTRAINT ck_knowledge_source CHECK (source_type IN ('official_manual','self_authored_simulation','enterprise_policy','enterprise_faq')),
  ADD COLUMN revision INT NOT NULL DEFAULT 1,
  ADD COLUMN layout_version VARCHAR(40) NOT NULL DEFAULT 'knowledge-layout-v1',
  ADD COLUMN storage_key VARCHAR(100) NULL,
  ADD COLUMN version_lock BIGINT NOT NULL DEFAULT 0;

CREATE TABLE knowledge_revisions (
  document_id BIGINT NOT NULL,
  revision INT NOT NULL,
  storage_key VARCHAR(100) NOT NULL,
  file_name VARCHAR(200) NOT NULL,
  metadata_json JSON NOT NULL,
  content_hash CHAR(64) NOT NULL,
  layout_hash CHAR(64) NOT NULL,
  config_hash CHAR(64) NOT NULL,
  binding_hash CHAR(64) NOT NULL,
  layout_version VARCHAR(40) NOT NULL,
  extracted_text MEDIUMTEXT NOT NULL,
  chunk_count INT NOT NULL,
  stage VARCHAR(40) NOT NULL DEFAULT 'REVIEW_REQUIRED',
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY(document_id,revision),
  FOREIGN KEY(document_id) REFERENCES knowledge_documents(id),
  FOREIGN KEY(created_by) REFERENCES users(id),
  CHECK(revision>0 AND chunk_count BETWEEN 1 AND 10000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_permissions (
  document_id BIGINT NOT NULL,
  revision INT NOT NULL,
  purpose VARCHAR(16) NOT NULL,
  allowed BOOLEAN NOT NULL DEFAULT FALSE,
  configuration_id VARCHAR(100) NULL,
  PRIMARY KEY(document_id,revision,purpose),
  FOREIGN KEY(document_id,revision) REFERENCES knowledge_revisions(document_id,revision),
  CHECK(purpose IN ('embedding','rerank','chat')),
  CHECK(allowed=FALSE OR configuration_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE knowledge_chunks DROP INDEX uk_knowledge_chunk_index,
  ADD COLUMN revision INT NOT NULL DEFAULT 1,
  ADD COLUMN stable_chunk_id CHAR(64) NULL,
  ADD COLUMN page_end INT NULL,
  ADD COLUMN offset_start INT NULL,
  ADD COLUMN offset_end INT NULL,
  ADD COLUMN layout_version VARCHAR(40) NOT NULL DEFAULT 'knowledge-layout-v1',
  ADD CONSTRAINT uk_knowledge_chunk_index UNIQUE(document_id,revision,chunk_index),
  ADD CONSTRAINT uk_knowledge_chunk_stable UNIQUE(stable_chunk_id);

CREATE TABLE knowledge_preview_confirmations (
  token CHAR(36) NOT NULL PRIMARY KEY,
  document_id BIGINT NOT NULL,
  revision INT NOT NULL,
  actor_id BIGINT NOT NULL,
  binding_hash CHAR(64) NOT NULL,
  covered_until INT NOT NULL DEFAULT 0,
  expires_at DATETIME(6) NOT NULL,
  FOREIGN KEY(document_id,revision) REFERENCES knowledge_revisions(document_id,revision),
  FOREIGN KEY(actor_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Durable publication intentions only. F18-F23 add jobs/outbox/worker and activation.
CREATE TABLE knowledge_publication_requests (
  id CHAR(36) NOT NULL PRIMARY KEY,
  document_id BIGINT NOT NULL,
  revision INT NOT NULL,
  binding_hash CHAR(64) NOT NULL,
  actor_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'WAITING_WORKER',
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE(document_id,revision,binding_hash),
  FOREIGN KEY(document_id,revision) REFERENCES knowledge_revisions(document_id,revision),
  FOREIGN KEY(actor_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
