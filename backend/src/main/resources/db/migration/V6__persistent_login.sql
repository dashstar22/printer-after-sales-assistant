-- Spring Security persistent remember-me tokens; credentials never enter browser storage.
CREATE TABLE persistent_logins (
    username VARCHAR(64) NOT NULL,
    series VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    token VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    last_used TIMESTAMP(6) NOT NULL,
    INDEX ix_persistent_logins_username (username),
    CONSTRAINT fk_persistent_logins_user FOREIGN KEY (username) REFERENCES users(username) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
