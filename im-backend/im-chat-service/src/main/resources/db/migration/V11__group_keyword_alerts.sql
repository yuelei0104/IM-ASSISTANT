CREATE TABLE im_group_keyword (
    user_id BIGINT NOT NULL,
    word VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, word)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE im_group_mention_notice
    ADD COLUMN kind VARCHAR(8) NOT NULL DEFAULT 'MENTION';
