-- T011: data layer only. Ownership, confirmation validity and transactions belong to services.
CREATE TABLE repair_drafts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    symptom VARCHAR(2000) NOT NULL,
    extra VARCHAR(2000) NOT NULL,
    contact VARCHAR(100) NOT NULL,
    contact_method VARCHAR(200) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_repair_drafts_customer FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_repair_drafts_order FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX ix_repair_drafts_customer (customer_id),
    INDEX ix_repair_drafts_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tickets (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ticket_no VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    draft_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    snapshot_symptom VARCHAR(2000) NOT NULL,
    snapshot_extra VARCHAR(2000) NOT NULL,
    snapshot_contact VARCHAR(100) NOT NULL,
    snapshot_contact_method VARCHAR(200) NOT NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    accepted_by BIGINT NULL,
    CONSTRAINT uk_tickets_no UNIQUE (ticket_no),
    CONSTRAINT uk_tickets_draft UNIQUE (draft_id),
    CONSTRAINT fk_tickets_draft FOREIGN KEY (draft_id) REFERENCES repair_drafts(id),
    CONSTRAINT fk_tickets_customer FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_tickets_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_tickets_accepted_by FOREIGN KEY (accepted_by) REFERENCES users(id),
    CONSTRAINT ck_tickets_status CHECK (CAST(status AS BINARY) IN ('PENDING', 'IN_PROGRESS', 'CLOSED')),
    INDEX ix_tickets_customer (customer_id),
    INDEX ix_tickets_order (order_id),
    INDEX ix_tickets_accepted_by (accepted_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- All referenced tables now exist; ticket_id remains NULL until a later creation transaction.
CREATE TABLE repair_confirmations (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    draft_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    draft_version BIGINT NOT NULL,
    payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    operation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_id BIGINT NULL,
    CONSTRAINT uk_repair_confirmations_operation UNIQUE (operation_id),
    CONSTRAINT fk_repair_confirmations_draft FOREIGN KEY (draft_id) REFERENCES repair_drafts(id),
    CONSTRAINT fk_repair_confirmations_customer FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_repair_confirmations_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id),
    INDEX ix_repair_confirmations_draft (draft_id),
    INDEX ix_repair_confirmations_customer (customer_id),
    INDEX ix_repair_confirmations_ticket (ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ticket_events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    note VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_ticket_events_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id),
    CONSTRAINT fk_ticket_events_actor FOREIGN KEY (actor_id) REFERENCES users(id),
    INDEX ix_ticket_events_timeline (ticket_id, created_at, id),
    INDEX ix_ticket_events_actor (actor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
