-- T021 additive processing identity and one active version per document key.
ALTER TABLE knowledge_documents
  ADD COLUMN active_key VARCHAR(96) COLLATE utf8mb4_bin
    GENERATED ALWAYS AS (CASE WHEN active THEN document_key ELSE NULL END) STORED,
  ADD CONSTRAINT uk_knowledge_active_key UNIQUE (active_key);

CREATE TABLE knowledge_processing (
  document_id BIGINT NOT NULL PRIMARY KEY,
  layout_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  metadata_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  model VARCHAR(100) NOT NULL,
  dimensions INT NOT NULL,
  collection_name VARCHAR(100) NOT NULL,
  chunk_size INT NOT NULL,
  chunk_overlap INT NOT NULL,
  attempt BIGINT NOT NULL DEFAULT 1,
  failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  published_at DATETIME(6) NULL,
  CONSTRAINT fk_knowledge_processing_document FOREIGN KEY (document_id) REFERENCES knowledge_documents(id),
  CONSTRAINT ck_knowledge_processing_parameters CHECK
    (dimensions=1024 AND chunk_size BETWEEN 100 AND 2000 AND chunk_overlap>=0 AND chunk_overlap<chunk_size AND attempt>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
