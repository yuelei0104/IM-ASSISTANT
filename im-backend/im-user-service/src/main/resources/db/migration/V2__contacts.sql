CREATE TABLE im_friendship (
    user_low_id BIGINT NOT NULL,
    user_high_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_low_id, user_high_id),
    KEY idx_friendship_high (user_high_id, user_low_id),
    CONSTRAINT fk_friendship_low FOREIGN KEY (user_low_id) REFERENCES im_user(id),
    CONSTRAINT fk_friendship_high FOREIGN KEY (user_high_id) REFERENCES im_user(id),
    CONSTRAINT chk_friendship_order CHECK (user_low_id < user_high_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE im_friend_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    from_user_id BIGINT NOT NULL,
    to_user_id BIGINT NOT NULL,
    note VARCHAR(120) NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP NULL,
    pending_low_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'PENDING' THEN LEAST(from_user_id, to_user_id) ELSE NULL END) STORED,
    pending_high_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'PENDING' THEN GREATEST(from_user_id, to_user_id) ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pending_pair (pending_low_id, pending_high_id),
    KEY idx_friend_request_from (from_user_id, status, id),
    KEY idx_friend_request_to (to_user_id, status, id),
    CONSTRAINT fk_friend_request_from FOREIGN KEY (from_user_id) REFERENCES im_user(id),
    CONSTRAINT fk_friend_request_to FOREIGN KEY (to_user_id) REFERENCES im_user(id),
    CONSTRAINT chk_friend_request_distinct CHECK (from_user_id <> to_user_id),
    CONSTRAINT chk_friend_request_status CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'WITHDRAWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
