-- Additive automation identity and notification facts; no business records rewritten.
CREATE TABLE integration_credentials (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 label VARCHAR(80) NOT NULL,
 token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 scopes VARCHAR(100) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 revoked_at DATETIME(6) NULL
) ENGINE=InnoDB;
CREATE TABLE automation_runs (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 credential_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 tool_name VARCHAR(40) NOT NULL,
 result VARCHAR(32) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(credential_id) REFERENCES integration_credentials(id)
) ENGINE=InnoDB;
CREATE TABLE notification_guard (id INT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO notification_guard VALUES (1);
CREATE TABLE notification_deliveries (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 credential_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 event_key VARCHAR(180) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 target_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 business_date DATE NOT NULL,
 window_key VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 daily_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 claim_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(16) NOT NULL,
 claimed_at DATETIME(6) NOT NULL,
 lease_until DATETIME(6) NOT NULL,
 finished_at DATETIME(6) NULL,
 channel_receipt_id VARCHAR(120) NULL,
 result_request_id CHAR(36) NULL,
 reviewed_by BIGINT NULL,
 review_note VARCHAR(500) NULL,
 FOREIGN KEY(credential_id) REFERENCES integration_credentials(id),
 FOREIGN KEY(reviewed_by) REFERENCES users(id),
 UNIQUE(event_key,target_id,window_key),
 UNIQUE(target_id,daily_key),
 UNIQUE(credential_id,request_id),
 CHECK(status IN ('CLAIMED','SENT','FAILED','UNKNOWN')),
 INDEX ix_notification_review(status,lease_until)
) ENGINE=InnoDB;
