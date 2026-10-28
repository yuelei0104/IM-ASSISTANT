CREATE EXTENSION IF NOT EXISTS vector;

-- Only identifiers and embeddings: message text remains in MySQL.
CREATE TABLE IF NOT EXISTS im_group_message_embedding (
    group_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    model VARCHAR(128) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    embedding vector(1024) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, message_id, model)
);
