CREATE TABLE im_notification_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_key VARCHAR(96) NOT NULL,
    payload VARCHAR(2048) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at TIMESTAMP(3) NULL DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_notification_outbox_pending (published_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
