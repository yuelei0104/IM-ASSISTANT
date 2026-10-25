ALTER TABLE im_direct_message ADD COLUMN recalled_at TIMESTAMP(3) NULL;
ALTER TABLE im_group_message ADD COLUMN recalled_at TIMESTAMP(3) NULL;

CREATE TABLE im_direct_recall (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    user_low_id BIGINT NOT NULL,
    user_high_id BIGINT NOT NULL,
    recalled_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_direct_recall_message (message_id),
    KEY idx_direct_recall_pair (user_low_id, user_high_id, id),
    CONSTRAINT fk_direct_recall_message FOREIGN KEY (message_id) REFERENCES im_direct_message(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE im_group_recall (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    recalled_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_group_recall_message (message_id),
    KEY idx_group_recall_group (group_id, id),
    CONSTRAINT fk_group_recall_message FOREIGN KEY (message_id) REFERENCES im_group_message(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
