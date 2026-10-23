CREATE TABLE im_group_summary (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    requester_id BIGINT NOT NULL,
    first_message_id BIGINT NOT NULL,
    last_message_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_group_summary_list (group_id, id),
    CONSTRAINT fk_group_summary_group FOREIGN KEY (group_id) REFERENCES im_chat_group(id),
    CONSTRAINT chk_group_summary_range CHECK (first_message_id > 0 AND last_message_id >= first_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
