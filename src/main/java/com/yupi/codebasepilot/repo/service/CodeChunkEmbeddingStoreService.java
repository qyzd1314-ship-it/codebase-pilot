package com.yupi.codebasepilot.repo.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.yupi.codebasepilot.repo.entity.CodeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class CodeChunkEmbeddingStoreService {

    private static final Logger log = LoggerFactory.getLogger(CodeChunkEmbeddingStoreService.class);
    private static final String TABLE_NAME = "code_chunk_embedding";

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingService embeddingService;
    private final boolean pgvectorEnabled;
    private final String embeddingModel;

    public CodeChunkEmbeddingStoreService(JdbcTemplate jdbcTemplate,
                                          EmbeddingService embeddingService,
                                          @Value("${app.repo.pgvector.enabled:true}") boolean pgvectorEnabled,
                                          @Value("${app.repo.embedding-model:local-hash-128}") String embeddingModel) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
        this.pgvectorEnabled = pgvectorEnabled;
        this.embeddingModel = embeddingModel;
    }

    public String getEmbeddingModel() {
        return embeddingModel;
    }

    public PgVectorCapability inspectCapability() {
        if (!pgvectorEnabled) {
            return new PgVectorCapability(false, false, false, "pgvector disabled by config");
        }
        try {
            String databaseProductName = jdbcTemplate.execute((ConnectionCallback<String>) connection ->
                    connection.getMetaData().getDatabaseProductName());
            if (!"PostgreSQL".equalsIgnoreCase(StrUtil.nullToEmpty(databaseProductName))) {
                return new PgVectorCapability(false, false, false,
                        "Current datasource is not PostgreSQL");
            }
            boolean extensionAvailable = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                    select exists (
                        select 1 from pg_available_extensions where name = 'vector'
                    )
                    """, Boolean.class));
            boolean extensionInstalled = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                    select exists (
                        select 1 from pg_extension where extname = 'vector'
                    )
                    """, Boolean.class));
            boolean tableReady = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                    select exists (
                        select 1
                        from information_schema.tables
                        where table_schema = 'public'
                          and table_name = 'code_chunk_embedding'
                    )
                    """, Boolean.class));
            String reason = extensionInstalled && tableReady
                    ? "pgvector ready"
                    : extensionAvailable
                    ? "vector extension package is available but not fully initialized"
                    : "vector extension package is not available";
            return new PgVectorCapability(extensionAvailable, extensionInstalled, tableReady, reason);
        } catch (DataAccessException e) {
            log.warn("Failed to inspect pgvector capability: {}", e.getMessage());
            return new PgVectorCapability(false, false, false, "Failed to inspect pgvector capability");
        }
    }

    public void replaceEmbeddings(String repoId, List<CodeChunk> chunks) {
        PgVectorCapability capability = inspectCapability();
        if (!capability.isReady()) {
            log.info("Skip pgvector embedding persistence for repo {}: {}", repoId, capability.reason());
            return;
        }
        jdbcTemplate.update("DELETE FROM " + TABLE_NAME + " WHERE repo_id = ?", repoId);
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.batchUpdate("""
                        INSERT INTO code_chunk_embedding
                            (id, repo_id, chunk_id, embedding_model, embedding, content_hash, created_at)
                        VALUES (?, ?, ?, ?, CAST(? AS vector), ?, ?)
                        """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        CodeChunk chunk = chunks.get(i);
                        ps.setString(1, "embed_" + IdUtil.fastSimpleUUID());
                        ps.setString(2, repoId);
                        ps.setString(3, chunk.getId());
                        ps.setString(4, embeddingModel);
                        ps.setString(5, toVectorLiteral(readChunkEmbedding(chunk)));
                        ps.setString(6, chunk.getContentHash());
                        ps.setTimestamp(7, Timestamp.valueOf(now));
                    }

                    @Override
                    public int getBatchSize() {
                        return chunks.size();
                    }
                });
    }

    public List<VectorMatch> search(String repoId, String query, int topK) {
        PgVectorCapability capability = inspectCapability();
        if (!capability.isReady()) {
            return List.of();
        }
        List<Double> queryEmbedding = embeddingService.embedQuery(query);
        if (queryEmbedding.isEmpty()) {
            return List.of();
        }
        String vectorLiteral = toVectorLiteral(queryEmbedding);
        return jdbcTemplate.query("""
                        SELECT chunk_id,
                               GREATEST(0, 1 - (embedding <=> CAST(? AS vector))) AS similarity
                        FROM code_chunk_embedding
                        WHERE repo_id = ?
                        ORDER BY embedding <=> CAST(? AS vector)
                        LIMIT ?
                        """,
                ps -> {
                    ps.setString(1, vectorLiteral);
                    ps.setString(2, repoId);
                    ps.setString(3, vectorLiteral);
                    ps.setInt(4, topK);
                },
                (rs, rowNum) -> new VectorMatch(
                        rs.getString("chunk_id"),
                        rs.getDouble("similarity")
                ));
    }

    private List<Double> readChunkEmbedding(CodeChunk chunk) {
        if (StrUtil.isNotBlank(chunk.getEmbeddingJson())) {
            return embeddingService.readEmbedding(chunk.getEmbeddingJson());
        }
        return embeddingService.embedFallbackChunk(chunk);
    }

    private String toVectorLiteral(List<Double> vector) {
        List<String> values = new ArrayList<>(vector.size());
        for (Double value : vector) {
            double normalized = value == null ? 0D : value;
            values.add(String.format(Locale.US, "%.8f", normalized));
        }
        return "[" + String.join(",", values) + "]";
    }

    public record VectorMatch(String chunkId, double similarity) {
    }

    public record PgVectorCapability(boolean extensionAvailable,
                                     boolean extensionInstalled,
                                     boolean tableReady,
                                     String reason) {
        public boolean isReady() {
            return extensionInstalled && tableReady;
        }
    }
}
