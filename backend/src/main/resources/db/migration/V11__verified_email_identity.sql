-- Additive only: never infer an email or rewrite existing credentials/business foreign keys.
ALTER TABLE users
 ADD COLUMN email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN display_email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN email_verified_at TIMESTAMP(6) NULL,
 ADD COLUMN recovery_until TIMESTAMP(6) NULL,
 ADD UNIQUE KEY uk_users_email (email),
 ADD CONSTRAINT ck_verified_email CHECK ((email IS NULL AND email_verified_at IS NULL) OR (email IS NOT NULL AND email_verified_at IS NOT NULL));

CREATE TABLE email_invitations (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 display_email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 inviter_id BIGINT NOT NULL, inviter_version BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 expires_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY (inviter_id) REFERENCES users(id),
 CONSTRAINT ck_invitation_status CHECK (status IN ('PENDING','ACCEPTED','REVOKED')),
 INDEX ix_invite_email (email,status)
) ENGINE=InnoDB;

-- Pending applications have no password and no business identity. Only SHA-256 token digests.
CREATE TABLE email_actions (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 purpose VARCHAR(16) NOT NULL,
 email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 display_email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id BIGINT NULL, credential_version BIGINT NULL, invitation_id BIGINT NULL,
 token_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 expires_at TIMESTAMP(6) NOT NULL, consumed_at TIMESTAMP(6) NULL, revoked_at TIMESTAMP(6) NULL,
 UNIQUE KEY uk_email_token (token_digest),
 INDEX ix_email_action (email,purpose), INDEX ix_email_action_user (user_id),
 FOREIGN KEY (user_id) REFERENCES users(id),
 FOREIGN KEY (invitation_id) REFERENCES email_invitations(id),
 CONSTRAINT ck_email_purpose CHECK (purpose IN ('REGISTER','INVITE','RESET','BIND'))
) ENGINE=InnoDB;
CREATE TABLE email_sends (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 action_id BIGINT NOT NULL,
 email_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 purpose VARCHAR(16) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACCEPTED',
 reason VARCHAR(32) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (action_id) REFERENCES email_actions(id),
 INDEX ix_email_quota (email_digest,created_at), INDEX ix_global_quota (created_at),
 CONSTRAINT ck_email_send_status CHECK (status IN ('ACCEPTED','SMTP_ACCEPTED','FAILED','UNKNOWN'))
) ENGINE=InnoDB;

-- Offline recovery audit never stores a mailbox, token, temporary credential or identity document.
CREATE TABLE identity_maintenance_audit (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NULL, operation VARCHAR(32) NOT NULL,
 operator_reference VARCHAR(100) NOT NULL, verification_reference VARCHAR(100) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB;
