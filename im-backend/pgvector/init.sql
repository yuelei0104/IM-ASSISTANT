CREATE EXTENSION IF NOT EXISTS vector;

-- Only identifiers and embeddings: message text remains in MySQL.
-- One message may own several rows, one per chunk, so a long message is
-- searchable paragraph by paragraph instead of as one blurred vector.
CREATE TABLE IF NOT EXISTS im_group_message_embedding (
    group_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    model VARCHAR(128) NOT NULL,
    chunk_no SMALLINT NOT NULL DEFAULT 0,
    content_hash CHAR(64) NOT NULL,
    embedding vector(1024) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, message_id, model, chunk_no)
);

-- Serves the permission filter (message_id = ANY (...)) restricted to one group.
CREATE INDEX IF NOT EXISTS idx_group_embedding_scope
    ON im_group_message_embedding (group_id, model, message_id);

-- Approximate ordering for groups whose visible history outgrows exact scoring.
CREATE INDEX IF NOT EXISTS idx_group_embedding_vector
    ON im_group_message_embedding USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
