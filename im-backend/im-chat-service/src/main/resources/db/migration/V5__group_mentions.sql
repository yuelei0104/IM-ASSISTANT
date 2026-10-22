CREATE TABLE im_group_mention_notice (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    read_at TIMESTAMP(3) NULL,
    UNIQUE KEY uk_group_mention_recipient (message_id, recipient_id),
    KEY idx_mention_inbox (recipient_id, id),
    KEY idx_mention_unread (recipient_id, read_at),
    CONSTRAINT fk_mention_message FOREIGN KEY (message_id) REFERENCES im_group_message(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
