-- Serialize writes to each direct conversation so an afterId cursor follows commit order.
CREATE TABLE im_direct_pair_lock (
    user_low_id BIGINT NOT NULL,
    user_high_id BIGINT NOT NULL,
    PRIMARY KEY (user_low_id, user_high_id),
    CONSTRAINT chk_direct_pair_lock CHECK (user_low_id < user_high_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
