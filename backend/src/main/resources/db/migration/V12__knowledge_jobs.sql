-- Recoverable publication jobs. V11 is reserved for the parallel email migration.
CREATE TABLE knowledge_jobs (
 id CHAR(36) NOT NULL PRIMARY KEY,
 document_id BIGINT NOT NULL,
 revision INT NOT NULL,
 binding_hash CHAR(64) NOT NULL,
 state VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
 stage VARCHAR(40) NOT NULL DEFAULT 'INDEXING',
 cycle INT NOT NULL DEFAULT 1,
 attempt INT NOT NULL DEFAULT 0,
 lease_token CHAR(36) NULL,
 lease_owner VARCHAR(100) NULL,
 lease_until DATETIME(6) NULL,
 due_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 failure_code VARCHAR(64) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(document_id,revision,binding_hash),
 FOREIGN KEY(id) REFERENCES knowledge_publication_requests(id),
 FOREIGN KEY(document_id,revision) REFERENCES knowledge_revisions(document_id,revision),
 CHECK(state IN ('QUEUED','RUNNING','RETRY_WAIT','SUCCEEDED','FAILED')),
 CHECK(attempt BETWEEN 0 AND 3 AND cycle>0),
 INDEX ix_knowledge_jobs_recovery(state,lease_until,due_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_job_attempts (
 job_id CHAR(36) NOT NULL,
 cycle INT NOT NULL,
 attempt INT NOT NULL,
 lease_token CHAR(36) NOT NULL,
 state VARCHAR(24) NOT NULL,
 stage VARCHAR(40) NOT NULL,
 failure_code VARCHAR(64) NULL,
 started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 finished_at DATETIME(6) NULL,
 PRIMARY KEY(job_id,cycle,attempt),
 FOREIGN KEY(job_id) REFERENCES knowledge_jobs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_job_outbox (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 job_id CHAR(36) NOT NULL,
 revision INT NOT NULL,
 stage VARCHAR(40) NOT NULL,
 cycle INT NOT NULL,
 next_attempt INT NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 publish_attempts INT NOT NULL DEFAULT 0,
 failure_code VARCHAR(64) NULL,
 due_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 delivered_at DATETIME(6) NULL,
 UNIQUE(job_id,cycle,next_attempt),
 FOREIGN KEY(job_id) REFERENCES knowledge_jobs(id),
 INDEX ix_knowledge_outbox_due(state,due_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_job_dead_letters (
 job_id CHAR(36) NOT NULL,
 cycle INT NOT NULL,
 attempt INT NOT NULL,
 failure_code VARCHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(job_id,cycle),
 FOREIGN KEY(job_id) REFERENCES knowledge_jobs(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_index_generations (
 generation_id CHAR(64) NOT NULL PRIMARY KEY,
 document_id BIGINT NOT NULL,
 revision INT NOT NULL,
 binding_hash CHAR(64) NOT NULL,
 layout_hash CHAR(64) NOT NULL,
 config_hash CHAR(64) NOT NULL,
 manifest_hash CHAR(64) NOT NULL,
 embedding_configuration VARCHAR(100) NOT NULL,
 chunk_count INT NOT NULL,
 status VARCHAR(24) NOT NULL,
 vector_verified BOOLEAN NOT NULL DEFAULT FALSE,
 bm25_verified BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 published_at DATETIME(6) NULL,
 UNIQUE(document_id,revision,layout_hash,config_hash),
 FOREIGN KEY(document_id,revision) REFERENCES knowledge_revisions(document_id,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_index_batches (
 generation_id CHAR(64) NOT NULL,
 batch_number INT NOT NULL,
 status VARCHAR(24) NOT NULL,
 embedding_json MEDIUMTEXT NULL,
 failure_code VARCHAR(64) NULL,
 PRIMARY KEY(generation_id,batch_number),
 FOREIGN KEY(generation_id) REFERENCES knowledge_index_generations(generation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_active_generations (
 document_key VARCHAR(96) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
 generation_id CHAR(64) NULL,
 FOREIGN KEY(generation_id) REFERENCES knowledge_index_generations(generation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_epoch (id INT NOT NULL PRIMARY KEY, value BIGINT NOT NULL);
INSERT INTO knowledge_epoch(id,value) VALUES(1,0);
