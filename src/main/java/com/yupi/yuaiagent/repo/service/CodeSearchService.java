package com.yupi.yuaiagent.repo.service;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.repo.dto.CodeSearchRequest;
import com.yupi.yuaiagent.repo.dto.CodeSearchResponse;
import com.yupi.yuaiagent.repo.dto.CodeSearchResultDto;
import com.yupi.yuaiagent.repo.entity.CodeChunk;
import com.yupi.yuaiagent.repo.entity.Repo;
import com.yupi.yuaiagent.repo.enums.CodeChunkType;
import com.yupi.yuaiagent.repo.enums.CodeSearchMode;
import com.yupi.yuaiagent.repo.repository.CodeChunkRepository;
import com.yupi.yuaiagent.repo.repository.RepoRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CodeSearchService {

    private static final int DEFAULT_TOP_K = 10;
    private static final int MAX_TOP_K = 50;
    private static final int CONTENT_PREVIEW_LIMIT = 500;
    private static final int ROUTE_TOP_K = 20;
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "in", "on", "to", "for", "from", "by", "with", "without",
            "at", "into", "about", "help", "find", "analyze", "analyse", "analysis", "possible", "maybe",
            "likely", "issue", "issues", "problem", "problems", "root", "cause", "current", "implementation",
            "provided", "code", "based", "whether"
    );
    private static final Set<String> WEAK_SIGNAL_TOKENS = Set.of(
            "bug", "error", "errors", "exception", "exceptions", "controller", "service",
            "class", "function", "method", "module", "chain", "request", "response"
    );
    private static final List<String> LOW_SIGNAL_PATH_PARTS = List.of(
            "/.mvn/",
            "/mvnw",
            "maven-wrapper.properties",
            "/gradle/",
            "gradle-wrapper.properties",
            "/wrapper/",
            "/build/",
            "/dist/",
            "/target/",
            "/node_modules/",
            "/.idea/",
            "/.vscode/"
    );
    private static final List<String> HIGH_SIGNAL_PATH_PARTS = List.of(
            "/controller/",
            "controller.java",
            "/service/",
            "service.java",
            "/filter/",
            "filter.java",
            "/interceptor/",
            "interceptor.java",
            "/exception/",
            "exception.java",
            "/security/",
            "security",
            "/auth/",
            "auth",
            "/test/",
            "test"
    );

    private final RepoRepository repoRepository;
    private final CodeChunkRepository codeChunkRepository;
    private final EmbeddingService embeddingService;
    private final CodeChunkEmbeddingStoreService codeChunkEmbeddingStoreService;

    public CodeSearchService(RepoRepository repoRepository,
                             CodeChunkRepository codeChunkRepository,
                             EmbeddingService embeddingService,
                             CodeChunkEmbeddingStoreService codeChunkEmbeddingStoreService) {
        this.repoRepository = repoRepository;
        this.codeChunkRepository = codeChunkRepository;
        this.embeddingService = embeddingService;
        this.codeChunkEmbeddingStoreService = codeChunkEmbeddingStoreService;
    }

    public CodeSearchResponse search(String repoId, CodeSearchRequest request) {
        validateRequest(repoId, request);
        Repo repo = repoRepository.findById(repoId)
                .orElseThrow(() -> new EntityNotFoundException("Repo not found: " + repoId));
        List<CodeChunk> chunks = codeChunkRepository.findByRepoIdOrderByFilePathAscStartLineAsc(repo.getId());
        int topK = normalizeTopK(request.getTopK());
        CodeSearchMode searchMode = CodeSearchMode.from(request.getSearchMode());
        Map<String, CodeChunk> chunkById = chunks.stream()
                .collect(LinkedHashMap::new, (map, chunk) -> map.put(chunk.getId(), chunk), Map::putAll);

        List<String> tokens = tokenizeQuery(request.getQuery());
        List<String> searchTokens = tokens;
        List<ScoredChunk> keywordResults = searchMode == CodeSearchMode.VECTOR_ONLY
                ? List.of()
                : limitRouteTopK(scoreChunks(chunks, searchTokens));
        if (searchMode != CodeSearchMode.VECTOR_ONLY && keywordResults.isEmpty()) {
            searchTokens = buildFallbackTokens(request.getQuery(), searchTokens);
            keywordResults = limitRouteTopK(scoreChunks(chunks, searchTokens));
        }
        if (searchMode != CodeSearchMode.VECTOR_ONLY
                && (searchTokens.isEmpty() || containsNoStrongSignalToken(searchTokens))) {
            return CodeSearchResponse.builder().results(List.of()).build();
        }
        List<ScoredChunk> vectorResults = searchMode == CodeSearchMode.KEYWORD_ONLY
                ? List.of()
                : limitRouteTopK(vectorSearch(repoId, chunks, chunkById, buildVectorQuery(request.getQuery(), searchTokens)));

        List<ScoredChunk> scoredChunks = switch (searchMode) {
            case KEYWORD_ONLY -> mergeResults(keywordResults, List.of());
            case VECTOR_ONLY -> mergeResults(List.of(), vectorResults);
            case HYBRID -> mergeResults(keywordResults, vectorResults);
        };
        if (scoredChunks.isEmpty()) {
            return CodeSearchResponse.builder().results(List.of()).build();
        }

        List<CodeSearchResultDto> results = scoredChunks.stream()
                .sorted(Comparator
                        .comparingDouble(ScoredChunk::score).reversed()
                        .thenComparing(scored -> scored.chunk().getFilePath())
                        .thenComparing(scored -> scored.chunk().getStartLine()))
                .limit(topK)
                .map(this::toDto)
                .toList();
        return CodeSearchResponse.builder()
                .results(results)
                .build();
    }

    private void validateRequest(String repoId, CodeSearchRequest request) {
        if (StrUtil.isBlank(repoId)) {
            throw new IllegalArgumentException("repoId is required.");
        }
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (StrUtil.isBlank(request.getQuery())) {
            throw new IllegalArgumentException("query is required.");
        }
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private List<ScoredChunk> scoreChunks(List<CodeChunk> chunks, List<String> tokens) {
        if (tokens.isEmpty()) {
            return List.of();
        }
        List<ScoredChunk> results = new ArrayList<>();
        for (CodeChunk chunk : chunks) {
            ScoredChunk scoredChunk = scoreChunk(chunk, tokens);
            if (scoredChunk.score() > 0D) {
                results.add(scoredChunk);
            }
        }
        return results;
    }

    private List<ScoredChunk> vectorSearch(String repoId,
                                           List<CodeChunk> chunks,
                                           Map<String, CodeChunk> chunkById,
                                           String query) {
        List<ScoredChunk> pgVectorResults = pgVectorSearch(repoId, chunkById, query);
        if (!pgVectorResults.isEmpty()) {
            return pgVectorResults;
        }
        return fallbackVectorSearch(chunks, query);
    }

    private List<ScoredChunk> pgVectorSearch(String repoId, Map<String, CodeChunk> chunkById, String query) {
        List<CodeChunkEmbeddingStoreService.VectorMatch> matches = codeChunkEmbeddingStoreService.search(repoId, query, ROUTE_TOP_K);
        if (matches.isEmpty()) {
            return List.of();
        }
        List<ScoredChunk> results = new ArrayList<>();
        for (CodeChunkEmbeddingStoreService.VectorMatch match : matches) {
            CodeChunk chunk = chunkById.get(match.chunkId());
            if (chunk == null || match.similarity() <= 0D) {
                continue;
            }
            results.add(new ScoredChunk(
                    chunk,
                    roundScore(match.similarity()),
                    "Matched pgvector cosine similarity",
                    MatchSource.VECTOR,
                    0D,
                    match.similarity()
            ));
        }
        return results;
    }

    private List<ScoredChunk> fallbackVectorSearch(List<CodeChunk> chunks, String query) {
        List<Double> queryEmbedding = embeddingService.embedQuery(query);
        if (queryEmbedding.isEmpty()) {
            return List.of();
        }
        List<ScoredChunk> results = new ArrayList<>();
        for (CodeChunk chunk : chunks) {
            List<Double> chunkEmbedding = readChunkEmbedding(chunk);
            double similarity = embeddingService.cosineSimilarity(queryEmbedding, chunkEmbedding);
            if (similarity > 0D) {
                results.add(new ScoredChunk(
                        chunk,
                        roundScore(similarity),
                        "Matched vector similarity",
                        MatchSource.VECTOR,
                        0D,
                        similarity
                ));
            }
        }
        return results.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(ROUTE_TOP_K)
                .toList();
    }

    private ScoredChunk scoreChunk(CodeChunk chunk, List<String> tokens) {
        String symbolName = StrUtil.nullToEmpty(chunk.getSymbolName()).toLowerCase(Locale.ROOT);
        String filePath = StrUtil.nullToEmpty(chunk.getFilePath()).toLowerCase(Locale.ROOT);
        String content = StrUtil.nullToEmpty(chunk.getContent()).toLowerCase(Locale.ROOT);
        double score = 0D;
        List<String> reasons = new ArrayList<>();
        boolean matchedStrongToken = false;
        boolean matchedPathOrSymbol = false;
        Set<String> matchedTokens = new HashSet<>();
        for (String token : tokens) {
            String normalizedToken = token.toLowerCase(Locale.ROOT);
            boolean weakSignalToken = isWeakSignalToken(normalizedToken);
            int symbolHits = countOccurrences(symbolName, normalizedToken);
            int pathHits = countOccurrences(filePath, normalizedToken);
            int contentHits = countOccurrences(content, normalizedToken);
            if (symbolHits > 0 || pathHits > 0 || contentHits > 0) {
                matchedTokens.add(normalizedToken);
                if (!weakSignalToken) {
                    matchedStrongToken = true;
                }
            }
            if (symbolHits > 0) {
                score += symbolHits * (weakSignalToken ? 2.5D : 5D);
                reasons.add(token + "(symbol)");
                matchedPathOrSymbol = true;
            }
            if (pathHits > 0) {
                score += pathHits * (weakSignalToken ? 1.5D : 3D);
                reasons.add(token + "(path)");
                matchedPathOrSymbol = true;
            }
            if (contentHits > 0) {
                score += Math.min(contentHits, 5) * (weakSignalToken ? 0.35D : 1.5D);
                reasons.add(token + "(content)");
            }
        }
        if (matchedTokens.isEmpty()) {
            return new ScoredChunk(chunk, 0D, "No keyword hits.", MatchSource.KEYWORD, 0D, 0D);
        }
        if (!matchedStrongToken && !matchedPathOrSymbol) {
            return new ScoredChunk(chunk, 0D, "Matched only weak content terms.", MatchSource.KEYWORD, 0D, 0D);
        }
        score = Math.max(score + calculatePathBoost(filePath), 0D);
        return new ScoredChunk(
                chunk,
                roundScore(score),
                buildReason(reasons),
                MatchSource.KEYWORD,
                score,
                0D
        );
    }

    private int countOccurrences(String source, String token) {
        if (StrUtil.isBlank(source) || StrUtil.isBlank(token)) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }

    private List<String> tokenizeQuery(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("[A-Za-z0-9_./\\\\-]+|[\\p{IsHan}]{2,}").matcher(query);
        while (matcher.find()) {
            String rawToken = matcher.group().trim();
            if (rawToken.isEmpty()) {
                continue;
            }
            addToken(tokens, rawToken);
            splitCamelCase(rawToken).forEach(token -> addToken(tokens, token));
        }
        return tokens.stream().toList();
    }

    private List<String> buildFallbackTokens(String query, List<String> existingTokens) {
        LinkedHashSet<String> fallbackTokens = new LinkedHashSet<>(existingTokens);
        Matcher matcher = Pattern.compile("[A-Z][A-Za-z0-9]+Exception|[A-Z][A-Za-z0-9]+Error|[A-Za-z0-9_/.-]+").matcher(query);
        while (matcher.find()) {
            String token = matcher.group().trim();
            addToken(fallbackTokens, token);
            if (token.contains("/")) {
                for (String part : token.split("/")) {
                    addToken(fallbackTokens, part);
                }
            }
            if (token.contains(".")) {
                for (String part : token.split("\\.")) {
                    addToken(fallbackTokens, part);
                }
            }
        }
        return fallbackTokens.stream().toList();
    }

    private String buildVectorQuery(String rawQuery, List<String> tokens) {
        if (!tokens.isEmpty()) {
            return String.join(" ", tokens);
        }
        return StrUtil.nullToEmpty(rawQuery).trim();
    }

    private List<String> splitCamelCase(String token) {
        List<String> parts = new ArrayList<>();
        Matcher matcher = Pattern.compile("[A-Z]?[a-z]+|[A-Z]+(?![a-z])|\\d+").matcher(token);
        while (matcher.find()) {
            parts.add(matcher.group());
        }
        return parts;
    }

    private void addToken(Set<String> tokens, String token) {
        String normalized = token == null ? "" : token.trim();
        if (normalized.length() < 2) {
            return;
        }
        if (isStopWord(normalized)) {
            return;
        }
        tokens.add(normalized);
        String lowerCaseToken = normalized.toLowerCase(Locale.ROOT);
        if (!normalized.equals(lowerCaseToken) && !isStopWord(lowerCaseToken)) {
            tokens.add(lowerCaseToken);
        }
    }

    private boolean isStopWord(String token) {
        return STOP_WORDS.contains(StrUtil.nullToEmpty(token).toLowerCase(Locale.ROOT));
    }

    private boolean isWeakSignalToken(String token) {
        return WEAK_SIGNAL_TOKENS.contains(StrUtil.nullToEmpty(token).toLowerCase(Locale.ROOT));
    }

    private boolean containsNoStrongSignalToken(List<String> tokens) {
        return tokens.stream().noneMatch(token -> !isWeakSignalToken(token));
    }

    private String buildReason(List<String> reasons) {
        if (reasons.isEmpty()) {
            return "No keyword hits.";
        }
        LinkedHashSet<String> uniqueReasons = new LinkedHashSet<>(reasons);
        return "Matched " + String.join(", ", uniqueReasons);
    }

    private CodeSearchResultDto toDto(ScoredChunk scoredChunk) {
        CodeChunk chunk = scoredChunk.chunk();
        return CodeSearchResultDto.builder()
                .chunkId(chunk.getId())
                .filePath(chunk.getFilePath())
                .symbolName(chunk.getSymbolName())
                .startLine(chunk.getStartLine())
                .endLine(chunk.getEndLine())
                .score(scoredChunk.score())
                .reason(scoredChunk.reason())
                .matchSource(scoredChunk.matchSource().value())
                .contentPreview(buildContentPreview(chunk.getContent()))
                .build();
    }

    private List<ScoredChunk> limitRouteTopK(List<ScoredChunk> scoredChunks) {
        return scoredChunks.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(ROUTE_TOP_K)
                .toList();
    }

    private List<ScoredChunk> mergeResults(List<ScoredChunk> keywordResults, List<ScoredChunk> vectorResults) {
        Map<String, ScoredChunk> merged = new LinkedHashMap<>();
        double maxKeywordScore = keywordResults.stream().mapToDouble(ScoredChunk::keywordScore).max().orElse(0D);
        double maxVectorScore = vectorResults.stream().mapToDouble(ScoredChunk::vectorScore).max().orElse(0D);

        for (ScoredChunk scoredChunk : keywordResults) {
            merged.put(scoredChunk.chunk().getId(), normalizeForMerge(scoredChunk, maxKeywordScore, maxVectorScore));
        }
        for (ScoredChunk scoredChunk : vectorResults) {
            String chunkId = scoredChunk.chunk().getId();
            ScoredChunk normalized = normalizeForMerge(scoredChunk, maxKeywordScore, maxVectorScore);
            if (!merged.containsKey(chunkId)) {
                merged.put(chunkId, normalized);
                continue;
            }
            ScoredChunk existing = merged.get(chunkId);
            double keywordScore = Math.max(existing.keywordScore(), normalized.keywordScore());
            double vectorScore = Math.max(existing.vectorScore(), normalized.vectorScore());
            MatchSource matchSource = existing.matchSource() == normalized.matchSource()
                    ? existing.matchSource()
                    : MatchSource.BOTH;
            merged.put(chunkId, new ScoredChunk(
                    existing.chunk(),
                    roundScore(applyFusionBoosts(existing.chunk(), keywordScore, vectorScore)),
                    buildMergedReason(existing, normalized, matchSource),
                    matchSource,
                    keywordScore,
                    vectorScore
            ));
        }
        return merged.values().stream()
                .map(scoredChunk -> scoredChunk.withMergedScore(roundScore(applyFusionBoosts(
                        scoredChunk.chunk(),
                        scoredChunk.keywordScore(),
                        scoredChunk.vectorScore()
                ))))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .toList();
    }

    private ScoredChunk normalizeForMerge(ScoredChunk scoredChunk, double maxKeywordScore, double maxVectorScore) {
        double normalizedKeywordScore = maxKeywordScore <= 0D ? 0D : scoredChunk.keywordScore() / maxKeywordScore;
        double normalizedVectorScore = maxVectorScore <= 0D ? 0D : scoredChunk.vectorScore() / maxVectorScore;
        double mergedScore = applyFusionBoosts(scoredChunk.chunk(), normalizedKeywordScore, normalizedVectorScore);
        return new ScoredChunk(
                scoredChunk.chunk(),
                roundScore(mergedScore),
                buildMergedReason(scoredChunk, null, scoredChunk.matchSource()),
                scoredChunk.matchSource(),
                normalizedKeywordScore,
                normalizedVectorScore
        );
    }

    private double applyFusionBoosts(CodeChunk chunk, double keywordScore, double vectorScore) {
        double fusedScore = keywordScore * 0.6D + vectorScore * 0.4D;
        fusedScore += calculatePathBoost(normalizeFilePath(chunk.getFilePath()));
        fusedScore += calculateChunkTypeBoost(chunk.getChunkType());
        return Math.max(fusedScore, 0D);
    }

    private double calculatePathBoost(String filePath) {
        if (StrUtil.isBlank(filePath)) {
            return 0D;
        }
        if (matchesAnyPathPart(filePath, LOW_SIGNAL_PATH_PARTS)) {
            return -0.25D;
        }
        if (matchesAnyPathPart(filePath, HIGH_SIGNAL_PATH_PARTS)) {
            return 0.12D;
        }
        return 0D;
    }

    private double calculateChunkTypeBoost(CodeChunkType chunkType) {
        if (chunkType == null) {
            return 0D;
        }
        return switch (chunkType) {
            case FUNCTION -> 0.08D;
            case CLASS -> 0.05D;
            case FILE -> 0.01D;
            case CONFIG -> -0.03D;
            case DOC -> -0.05D;
        };
    }

    private boolean matchesAnyPathPart(String filePath, List<String> patterns) {
        for (String pattern : patterns) {
            if (filePath.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeFilePath(String filePath) {
        return StrUtil.nullToEmpty(filePath).toLowerCase(Locale.ROOT);
    }

    private String buildMergedReason(ScoredChunk primary, ScoredChunk secondary, MatchSource matchSource) {
        return switch (matchSource) {
            case KEYWORD -> "Matched by keyword. " + primary.reason();
            case VECTOR -> "Matched by vector similarity. " + primary.reason();
            case BOTH -> "Matched by both keyword and vector. "
                    + primary.reason()
                    + (secondary == null ? "" : " " + secondary.reason());
        };
    }

    private List<Double> readChunkEmbedding(CodeChunk chunk) {
        if (StrUtil.isNotBlank(chunk.getEmbeddingJson())) {
            return embeddingService.readEmbedding(chunk.getEmbeddingJson());
        }
        return embeddingService.embedFallbackChunk(chunk);
    }

    private String buildContentPreview(String content) {
        String normalized = StrUtil.nullToEmpty(content).trim();
        if (normalized.length() <= CONTENT_PREVIEW_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, CONTENT_PREVIEW_LIMIT) + "...";
    }

    private double roundScore(double score) {
        return Math.round(score * 100.0) / 100.0;
    }

    private record ScoredChunk(CodeChunk chunk,
                               double score,
                               String reason,
                               MatchSource matchSource,
                               double keywordScore,
                               double vectorScore) {
        private ScoredChunk withMergedScore(double mergedScore) {
            return new ScoredChunk(chunk, mergedScore, reason, matchSource, keywordScore, vectorScore);
        }
    }

    private enum MatchSource {
        KEYWORD("keyword"),
        VECTOR("vector"),
        BOTH("both");

        private final String value;

        MatchSource(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
