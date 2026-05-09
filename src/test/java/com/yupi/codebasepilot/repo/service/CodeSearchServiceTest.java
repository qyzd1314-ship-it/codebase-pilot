package com.yupi.codebasepilot.repo.service;

import com.yupi.codebasepilot.repo.dto.CodeSearchRequest;
import com.yupi.codebasepilot.repo.dto.CodeSearchResponse;
import com.yupi.codebasepilot.repo.entity.CodeChunk;
import com.yupi.codebasepilot.repo.entity.Repo;
import com.yupi.codebasepilot.repo.enums.CodeChunkType;
import com.yupi.codebasepilot.repo.enums.RepoIndexedStatus;
import com.yupi.codebasepilot.repo.repository.CodeChunkRepository;
import com.yupi.codebasepilot.repo.repository.RepoRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class CodeSearchServiceTest {

    @Autowired
    private CodeSearchService codeSearchService;

    @Autowired
    private RepoRepository repoRepository;

    @Autowired
    private CodeChunkRepository codeChunkRepository;

    @BeforeEach
    void setUp() {
        codeChunkRepository.deleteByRepoId("repo_search_test");
        repoRepository.findById("repo_search_test").ifPresent(repoRepository::delete);

        Repo repo = new Repo();
        repo.setId("repo_search_test");
        repo.setName("search-demo");
        repo.setUrl("https://github.com/example/search-demo.git");
        repo.setBranch("main");
        repo.setLocalPath("D:/tmp/search-demo");
        repo.setIndexedStatus(RepoIndexedStatus.INDEXED);
        repo.setFileCount(3);
        repo.setChunkCount(3);
        repoRepository.save(repo);

        codeChunkRepository.save(buildChunk(
                "chunk_symbol",
                "src/main/java/com/example/LoginController.java",
                "java",
                "login",
                CodeChunkType.FUNCTION,
                10,
                40,
                "public void login() { throw new TokenValidationException(\"token invalid\"); }"
        ));
        codeChunkRepository.save(buildChunk(
                "chunk_path",
                "src/main/java/com/example/token/TokenFilter.java",
                "java",
                null,
                CodeChunkType.CLASS,
                1,
                20,
                "public class TokenFilter { void check(String token) {} }"
        ));
        codeChunkRepository.save(buildChunk(
                "chunk_content",
                "src/main/resources/application.yml",
                "yaml",
                null,
                CodeChunkType.CONFIG,
                1,
                10,
                "exception: login failed because token expired and controller returned 500"
        ));
        codeChunkRepository.save(buildChunk(
                "chunk_wrapper",
                ".mvn/wrapper/maven-wrapper.properties",
                "properties",
                null,
                CodeChunkType.CONFIG,
                1,
                10,
                "wrapper exception login token controller build settings"
        ));
    }

    @Test
    void shouldSortByWeightedKeywordScore() {
        CodeSearchRequest request = new CodeSearchRequest();
        request.setQuery("login token controller");
        request.setTopK(10);

        CodeSearchResponse response = codeSearchService.search("repo_search_test", request);

        Assertions.assertFalse(response.getResults().isEmpty());
        Assertions.assertEquals("chunk_symbol", response.getResults().getFirst().getChunkId());
        Assertions.assertTrue(response.getResults().getFirst().getScore() >= response.getResults().get(1).getScore());
        Assertions.assertNotNull(response.getResults().getFirst().getReason());
        Assertions.assertNotNull(response.getResults().getFirst().getMatchSource());
        Assertions.assertTrue(response.getResults().getFirst().getContentPreview().length() <= 503);
        Assertions.assertTrue(
                "keyword".equals(response.getResults().getFirst().getMatchSource())
                        || "both".equals(response.getResults().getFirst().getMatchSource())
        );
    }

    @Test
    void shouldReturnEmptyResultsWhenNoKeywordsMatched() {
        CodeSearchRequest request = new CodeSearchRequest();
        request.setQuery("nonexistentkeyword");
        request.setTopK(10);

        CodeSearchResponse response = codeSearchService.search("repo_search_test", request);

        Assertions.assertNotNull(response.getResults());
        Assertions.assertTrue(response.getResults().isEmpty());
    }

    @Test
    void shouldDeprioritizeWrapperFilesComparedWithBusinessCode() {
        CodeSearchRequest request = new CodeSearchRequest();
        request.setQuery("login token controller");
        request.setTopK(10);

        CodeSearchResponse response = codeSearchService.search("repo_search_test", request);

        int businessIndex = indexOfChunk(response, "chunk_symbol");
        int wrapperIndex = indexOfChunk(response, "chunk_wrapper");

        Assertions.assertTrue(businessIndex >= 0);
        Assertions.assertTrue(wrapperIndex >= 0);
        Assertions.assertTrue(businessIndex < wrapperIndex);
    }

    @Test
    void shouldIgnoreStopwordOnlyQuery() {
        CodeSearchRequest request = new CodeSearchRequest();
        request.setQuery("find root cause of the possible issue in the provided code");
        request.setTopK(10);

        CodeSearchResponse response = codeSearchService.search("repo_search_test", request);

        Assertions.assertNotNull(response.getResults());
        Assertions.assertTrue(response.getResults().isEmpty());
    }

    @Test
    void shouldDropWeakContentOnlyMatches() {
        CodeSearchRequest request = new CodeSearchRequest();
        request.setQuery("exception controller service");
        request.setTopK(10);

        CodeSearchResponse response = codeSearchService.search("repo_search_test", request);

        Assertions.assertNotNull(response.getResults());
        Assertions.assertTrue(response.getResults().isEmpty());
    }

    @Test
    void shouldSupportVectorOnlySearchModeWithFallbackEmbeddings() {
        CodeSearchRequest request = new CodeSearchRequest();
        request.setQuery("token validation exception");
        request.setTopK(10);
        request.setSearchMode("VECTOR_ONLY");

        CodeSearchResponse response = codeSearchService.search("repo_search_test", request);

        Assertions.assertNotNull(response.getResults());
        Assertions.assertFalse(response.getResults().isEmpty());
        Assertions.assertEquals("vector", response.getResults().getFirst().getMatchSource());
    }

    @Test
    void shouldSupportKeywordOnlyMode() {
        CodeSearchRequest request = new CodeSearchRequest();
        request.setQuery("login token controller");
        request.setTopK(10);
        request.setSearchMode("KEYWORD_ONLY");

        CodeSearchResponse response = codeSearchService.search("repo_search_test", request);

        Assertions.assertNotNull(response.getResults());
        Assertions.assertFalse(response.getResults().isEmpty());
        Assertions.assertNotEquals("vector", response.getResults().getFirst().getMatchSource());
    }

    private CodeChunk buildChunk(String id,
                                 String filePath,
                                 String language,
                                 String symbolName,
                                 CodeChunkType chunkType,
                                 int startLine,
                                 int endLine,
                                 String content) {
        CodeChunk codeChunk = new CodeChunk();
        codeChunk.setId(id);
        codeChunk.setRepoId("repo_search_test");
        codeChunk.setFilePath(filePath);
        codeChunk.setLanguage(language);
        codeChunk.setSymbolName(symbolName);
        codeChunk.setChunkType(chunkType);
        codeChunk.setStartLine(startLine);
        codeChunk.setEndLine(endLine);
        codeChunk.setContent(content);
        codeChunk.setContentHash(id + "_hash");
        codeChunk.setTokenCount(content.split("\\s+").length);
        return codeChunk;
    }

    private int indexOfChunk(CodeSearchResponse response, String chunkId) {
        for (int i = 0; i < response.getResults().size(); i++) {
            if (chunkId.equals(response.getResults().get(i).getChunkId())) {
                return i;
            }
        }
        return -1;
    }
}
