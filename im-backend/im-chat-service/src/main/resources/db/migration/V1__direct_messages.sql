CREATE TABLE im_direct_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_low_id BIGINT NOT NULL,
    user_high_id BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    client_msg_id VARCHAR(36) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_direct_client_msg (sender_id, client_msg_id),
    KEY idx_direct_pair_history (user_low_id, user_high_id, id),
    CONSTRAINT chk_direct_pair CHECK (user_low_id < user_high_id),
    CONSTRAINT chk_direct_sender CHECK (sender_id IN (user_low_id, user_high_id)),
    CONSTRAINT chk_direct_recipient CHECK (recipient_id IN (user_low_id, user_high_id) AND sender_id <> recipient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
