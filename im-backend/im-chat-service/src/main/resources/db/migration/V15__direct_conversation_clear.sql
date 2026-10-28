CREATE TABLE im_direct_clear (
    user_id BIGINT NOT NULL,
    peer_id BIGINT NOT NULL,
    last_cleared_id BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, peer_id),
    CONSTRAINT chk_direct_clear_pair CHECK (user_id > 0 AND peer_id > 0 AND user_id <> peer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
