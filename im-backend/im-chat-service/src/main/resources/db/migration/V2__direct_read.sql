CREATE TABLE im_direct_read (
    user_id BIGINT NOT NULL,
    peer_id BIGINT NOT NULL,
    last_read_message_id BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (user_id, peer_id),
    CONSTRAINT chk_direct_read_pair CHECK (user_id <> peer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_direct_unread ON im_direct_message (recipient_id, sender_id, id);
