-- T025: persistent business conversations, independent of the login HttpSession.
CREATE TABLE conversations (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX ix_conversations_customer (customer_id, id),
    INDEX ix_conversations_order (order_id),
    CONSTRAINT fk_conversations_customer FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_conversations_order FOREIGN KEY (order_id) REFERENCES orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE messages (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    turn_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content VARCHAR(4000) NOT NULL,
    response_json JSON NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_messages_turn_role UNIQUE (conversation_id, turn_id, role),
    CONSTRAINT ck_messages_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT ck_messages_response CHECK (
        (role='USER' AND response_json IS NULL) OR (role='ASSISTANT' AND response_json IS NOT NULL)),
    CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id) REFERENCES conversations(id),
    INDEX ix_messages_conversation (conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
