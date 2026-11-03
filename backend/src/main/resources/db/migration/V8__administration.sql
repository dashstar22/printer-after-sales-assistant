-- Additive administration upgrade. No seeded credentials or rewritten historical rows.
ALTER TABLE users DROP CHECK ck_users_role,
 ADD CONSTRAINT ck_users_role CHECK (role IN ('CUSTOMER','STAFF','ADMIN')),
 ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN display_name VARCHAR(100) NULL,
 ADD CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE','DISABLED'));
CREATE TABLE administration_guard (id INT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO administration_guard VALUES (1);
ALTER TABLE device_models ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
-- Canonical codes and reviewed aliases share one binary unique namespace.
CREATE TABLE model_aliases (
 normalized_alias VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY,
 model_id BIGINT NOT NULL, original_alias VARCHAR(64) NOT NULL,
 FOREIGN KEY (model_id) REFERENCES device_models(id)
) ENGINE=InnoDB;
-- Legacy canonical values are normalized with Java NFKC on first administrative mutation.
ALTER TABLE orders
 ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 ADD COLUMN void_reason VARCHAR(500) NULL,
 ADD COLUMN verified_by BIGINT NULL,
 ADD COLUMN verified_at TIMESTAMP(6) NULL,
 ADD COLUMN verification_note VARCHAR(500) NULL,
 ADD COLUMN import_id CHAR(36) NULL,
 ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
 ADD CONSTRAINT fk_orders_verifier FOREIGN KEY (verified_by) REFERENCES users(id),
 ADD CONSTRAINT ck_orders_status CHECK (status IN ('ACTIVE','VOID'));
CREATE TABLE administration_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, actor_id BIGINT NOT NULL,
 action VARCHAR(64) NOT NULL, object_id VARCHAR(64) NOT NULL,
 result VARCHAR(64) NOT NULL, request_id CHAR(36) NOT NULL,
 request_hash VARCHAR(100) NOT NULL, response_json JSON NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_admin_request (actor_id,action,request_id),
 FOREIGN KEY (actor_id) REFERENCES users(id)
) ENGINE=InnoDB;
CREATE TABLE order_imports (
 id CHAR(36) PRIMARY KEY, actor_id BIGINT NOT NULL,
 input_hash CHAR(64) NOT NULL, snapshot_json JSON NOT NULL, errors_json JSON NOT NULL,
 expires_at TIMESTAMP(6) NOT NULL, status VARCHAR(16) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, result_json JSON NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (actor_id) REFERENCES users(id),
 CONSTRAINT ck_import_status CHECK (status IN ('PREVIEW','COMMITTED'))
) ENGINE=InnoDB;
