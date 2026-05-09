package com.yupi.yuaiagent.repo.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.SecureUtil;
import com.yupi.yuaiagent.repo.dto.RepoResponse;
import com.yupi.yuaiagent.repo.entity.CodeChunk;
import com.yupi.yuaiagent.repo.entity.Repo;
import com.yupi.yuaiagent.repo.enums.RepoIndexedStatus;
import com.yupi.yuaiagent.repo.repository.CodeChunkRepository;
import com.yupi.yuaiagent.repo.repository.RepoRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class RepoIndexService {

    private static final Logger log = LoggerFactory.getLogger(RepoIndexService.class);

    private final RepoRepository repoRepository;
    private final CodeChunkRepository codeChunkRepository;
    private final RepoFileScanner repoFileScanner;
    private final CodeChunker codeChunker;
    private final RepoService repoService;
    private final EmbeddingService embeddingService;
    private final CodeChunkEmbeddingStoreService codeChunkEmbeddingStoreService;

    public RepoIndexService(RepoRepository repoRepository,
                            CodeChunkRepository codeChunkRepository,
                            RepoFileScanner repoFileScanner,
                            CodeChunker codeChunker,
                            RepoService repoService,
                            EmbeddingService embeddingService,
                            CodeChunkEmbeddingStoreService codeChunkEmbeddingStoreService) {
        this.repoRepository = repoRepository;
        this.codeChunkRepository = codeChunkRepository;
        this.repoFileScanner = repoFileScanner;
        this.codeChunker = codeChunker;
        this.repoService = repoService;
        this.embeddingService = embeddingService;
        this.codeChunkEmbeddingStoreService = codeChunkEmbeddingStoreService;
    }

    @Transactional
    public RepoResponse indexRepo(String repoId) {
        Repo repo = repoRepository.findById(repoId)
                .orElseThrow(() -> new EntityNotFoundException("Repo not found: " + repoId));
        repo.setIndexedStatus(RepoIndexedStatus.INDEXING);
        repoRepository.save(repo);

        try {
            Path repoRootPath = Path.of(repo.getLocalPath());
            List<ScannedRepoFile> scannedFiles = repoFileScanner.scan(repoRootPath);
            codeChunkRepository.deleteByRepoId(repoId);

            List<CodeChunk> chunks = scannedFiles.stream()
                    .flatMap(file -> codeChunker.chunk(file).stream())
                    .map(draft -> toEntity(repoId, draft))
                    .toList();
            codeChunkRepository.saveAll(chunks);
            try {
                codeChunkEmbeddingStoreService.replaceEmbeddings(repoId, chunks);
            } catch (Exception e) {
                log.warn("Skip pgvector embedding persistence for repo {} because: {}", repoId, e.getMessage());
            }

            repo.setFileCount(scannedFiles.size());
            repo.setChunkCount(chunks.size());
            repo.setLastIndexedAt(LocalDateTime.now());
            repo.setIndexedStatus(RepoIndexedStatus.INDEXED);
            repoRepository.save(repo);
            return repoService.getRepo(repoId);
        } catch (Exception e) {
            repo.setIndexedStatus(RepoIndexedStatus.FAILED);
            repoRepository.save(repo);
            throw new IllegalStateException("Failed to index repo: " + e.getMessage(), e);
        }
    }

    private CodeChunk toEntity(String repoId, CodeChunkDraft draft) {
        CodeChunk entity = new CodeChunk();
        entity.setId("chunk_" + IdUtil.fastSimpleUUID());
        entity.setRepoId(repoId);
        entity.setFilePath(draft.filePath());
        entity.setLanguage(draft.language());
        entity.setSymbolName(draft.symbolName());
        entity.setChunkType(draft.chunkType());
        entity.setStartLine(draft.startLine());
        entity.setEndLine(draft.endLine());
        entity.setContent(draft.content());
        entity.setContentHash(SecureUtil.sha256(draft.content()));
        entity.setTokenCount(countTokens(draft.content()));
        entity.setEmbeddingJson(embeddingService.generateChunkEmbeddingJson(entity));
        return entity;
    }

    private int countTokens(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isEmpty()) {
            return 0;
        }
        return normalized.split("\\s+").length;
    }
}
