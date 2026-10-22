CREATE TABLE im_chat_group (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(30) NOT NULL,
    owner_id BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE im_group_member (
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    joined_after_id BIGINT NOT NULL DEFAULT 0,
    last_read_id BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (group_id, user_id),
    KEY idx_group_member_user (user_id, group_id),
    CONSTRAINT fk_group_member_group FOREIGN KEY (group_id) REFERENCES im_chat_group(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE im_group_message (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    group_id BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    client_msg_id VARCHAR(36) NOT NULL,
    body VARCHAR(4000) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_group_client_msg (sender_id, client_msg_id),
    KEY idx_group_history (group_id, id),
    CONSTRAINT fk_group_message_group FOREIGN KEY (group_id) REFERENCES im_chat_group(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
