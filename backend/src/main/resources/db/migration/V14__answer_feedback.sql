CREATE TABLE answer_feedback (
    message_id BIGINT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    rating VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_feedback_message FOREIGN KEY (message_id) REFERENCES messages(id),
    CONSTRAINT fk_feedback_customer FOREIGN KEY (customer_id) REFERENCES users(id),
    CONSTRAINT ck_feedback_rating CHECK (rating IN ('HELPFUL','UNRESOLVED')),
    INDEX ix_feedback_gap (rating, message_id)
);
