-- Reservations survive business rollback/restart. Input bounds count UTF-8 bytes, not estimated tokens.
CREATE TABLE external_budget_counters (
 budget_key VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 attempts BIGINT NOT NULL DEFAULT 0,
 input_bytes BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
CREATE TABLE external_call_audit (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 scope_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 kind VARCHAR(16) NOT NULL,
 status VARCHAR(16) NOT NULL,
 reserved_input_bytes BIGINT NOT NULL,
 input_tokens BIGINT NULL, output_tokens BIGINT NULL,
 cost_amount DECIMAL(18,8) NULL,
 started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 duration_ms BIGINT NULL,
 INDEX ix_external_started (started_at), INDEX ix_external_scope (scope_id),
 CONSTRAINT ck_external_kind CHECK (kind IN ('CHAT','REWRITE','EMBEDDING','RERANK')),
 CONSTRAINT ck_external_status CHECK (status IN ('RESERVED','SUCCEEDED','UNKNOWN'))
) ENGINE=InnoDB;
