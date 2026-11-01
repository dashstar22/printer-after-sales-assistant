-- Internal workflow state. Messages and existing drafts remain independently readable.
CREATE TABLE conversation_dialogue_states (
    conversation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    phase VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    symptom TEXT NULL,
    extra TEXT NULL,
    contact TEXT NULL,
    contact_method TEXT NULL,
    pending_field VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
    authorized_turn CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revision BIGINT NOT NULL,
    last_turn CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    CONSTRAINT fk_dialogue_conversation FOREIGN KEY (conversation_id) REFERENCES conversations(id),
    CONSTRAINT fk_dialogue_customer FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT fk_dialogue_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT ck_dialogue_phase CHECK (phase IN ('COLLECTING','PAUSED','PREPARED','CANCELLED','EXPIRED')),
    CONSTRAINT ck_dialogue_revision CHECK (revision > 0),
    CONSTRAINT ck_dialogue_pending CHECK (pending_field IS NULL OR pending_field IN ('symptom','extra','contact','contactMethod'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
