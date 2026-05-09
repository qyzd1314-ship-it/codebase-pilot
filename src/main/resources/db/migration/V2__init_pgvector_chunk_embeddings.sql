DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_available_extensions
        WHERE name = 'vector'
    ) THEN
        CREATE EXTENSION IF NOT EXISTS vector;
    ELSE
        RAISE NOTICE 'pgvector extension package is not available, skip vector extension initialization.';
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_extension
        WHERE extname = 'vector'
    ) THEN
        EXECUTE '
            CREATE TABLE IF NOT EXISTS code_chunk_embedding (
                id VARCHAR(64) PRIMARY KEY,
                repo_id VARCHAR(64) NOT NULL,
                chunk_id VARCHAR(64) NOT NULL,
                embedding_model VARCHAR(128) NOT NULL,
                embedding vector(128) NOT NULL,
                content_hash VARCHAR(128) NOT NULL,
                created_at TIMESTAMP NOT NULL
            )';
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_code_chunk_embedding_repo_id ON code_chunk_embedding (repo_id)';
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_code_chunk_embedding_chunk_id ON code_chunk_embedding (chunk_id)';
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_code_chunk_embedding_repo_hash ON code_chunk_embedding (repo_id, content_hash)';
        EXECUTE 'CREATE UNIQUE INDEX IF NOT EXISTS uk_code_chunk_embedding_chunk_model ON code_chunk_embedding (chunk_id, embedding_model)';
    ELSE
        RAISE NOTICE 'pgvector extension is not installed, skip code_chunk_embedding table creation.';
    END IF;
END $$;
