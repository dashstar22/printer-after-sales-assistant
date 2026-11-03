-- Additive only: legacy tickets keep unknown context/timestamps as NULL.
ALTER TABLE tickets
    ADD conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD conversation_last_message_id BIGINT NULL,
    ADD created_at DATETIME(6) NULL,
    ADD updated_at DATETIME(6) NULL,
    ADD CONSTRAINT fk_ticket_conversation FOREIGN KEY (conversation_id) REFERENCES conversations(id),
    ADD CONSTRAINT fk_ticket_boundary FOREIGN KEY (conversation_last_message_id) REFERENCES messages(id),
    ADD CONSTRAINT ck_ticket_context_pair CHECK ((conversation_id IS NULL AND conversation_last_message_id IS NULL)
        OR (conversation_id IS NOT NULL AND conversation_last_message_id IS NOT NULL));

CREATE TABLE ticket_context_messages (
    ticket_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    turn_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role VARCHAR(16) NOT NULL,
    content VARCHAR(4000) NOT NULL,
    response_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY(ticket_id,message_id),
    FOREIGN KEY(ticket_id) REFERENCES tickets(id),
    FOREIGN KEY(message_id) REFERENCES messages(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_requests (
    ticket_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    action VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    response_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY(ticket_id,actor_id,action,request_id),
    FOREIGN KEY(ticket_id) REFERENCES tickets(id),
    FOREIGN KEY(actor_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_supplements (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content VARCHAR(2000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE(ticket_id,customer_id,request_id),
    FOREIGN KEY(ticket_id) REFERENCES tickets(id),
    FOREIGN KEY(customer_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ticket_attachments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    part_index INT NOT NULL,
    storage_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    mime VARCHAR(32) NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    bytes BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE(ticket_id,customer_id,request_id,part_index),
    UNIQUE(storage_key),
    FOREIGN KEY(ticket_id) REFERENCES tickets(id),
    FOREIGN KEY(customer_id) REFERENCES users(id),
    CHECK(part_index BETWEEN 0 AND 2 AND width>0 AND height>0 AND bytes>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
