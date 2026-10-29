-- Upgrade path for a pgvector volume created before chunked indexing.
-- Fresh deployments get the same shape from init.sql and do not need this file.
BEGIN;

ALTER TABLE im_group_message_embedding
    ADD COLUMN IF NOT EXISTS chunk_no SMALLINT NOT NULL DEFAULT 0;

DO $$
DECLARE
    pk_name text;
    pk_def text;
BEGIN
    SELECT conname, pg_get_constraintdef(oid) INTO pk_name, pk_def
      FROM pg_constraint
     WHERE conrelid = 'im_group_message_embedding'::regclass AND contype = 'p';
    IF pk_def IS NULL OR position('chunk_no' IN pk_def) = 0 THEN
        IF pk_name IS NOT NULL THEN
            EXECUTE format('ALTER TABLE im_group_message_embedding DROP CONSTRAINT %I', pk_name);
        END IF;
        ALTER TABLE im_group_message_embedding
            ADD CONSTRAINT im_group_message_embedding_pkey
            PRIMARY KEY (group_id, message_id, model, chunk_no);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_group_embedding_scope
    ON im_group_message_embedding (group_id, model, message_id);

CREATE INDEX IF NOT EXISTS idx_group_embedding_vector
    ON im_group_message_embedding USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

COMMIT;
