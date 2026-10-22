CREATE TABLE im_direct_inbox (
    user_id BIGINT NOT NULL,
    peer_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, peer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One-time backfill for direct messages created before this migration.
INSERT IGNORE INTO im_direct_inbox (user_id, peer_id)
SELECT recipient_id, sender_id FROM im_direct_message GROUP BY recipient_id, sender_id;
