package com.yupi.yuaiagent.repo.service;

import com.yupi.yuaiagent.repo.dto.RepoResponse;
import com.yupi.yuaiagent.repo.entity.Repo;
import com.yupi.yuaiagent.repo.enums.RepoIndexedStatus;
import com.yupi.yuaiagent.repo.repository.CodeChunkRepository;
import com.yupi.yuaiagent.repo.repository.RepoRepository;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootTest
class RepoIndexServiceTest {

    @Autowired
    private RepoIndexService repoIndexService;

    @Autowired
    private RepoRepository repoRepository;

    @Autowired
    private CodeChunkRepository codeChunkRepository;

    @TempDir
    Path tempDir;

    @Test
    void shouldIndexRepoAndPersistChunks() throws IOException {
        Files.writeString(tempDir.resolve("App.java"), """
                public class App {
                    public void login() {
                        System.out.println("login");
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("README.md"), """
                # Demo
                repo intro
                """, StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve("node_modules"));
        Files.writeString(tempDir.resolve("node_modules/ignored.js"), "console.log('ignore')", StandardCharsets.UTF_8);

        Repo repo = new Repo();
        repo.setId("repo_index_test");
        repo.setName("demo");
        repo.setUrl("https://github.com/example/demo.git");
        repo.setBranch("main");
        repo.setLocalPath(tempDir.toString());
        repo.setIndexedStatus(RepoIndexedStatus.CLONED);
        repo.setFileCount(0);
        repo.setChunkCount(0);
        repoRepository.save(repo);

        RepoResponse response = repoIndexService.indexRepo(repo.getId());

        Assertions.assertEquals("INDEXED", response.getIndexedStatus());
        Assertions.assertEquals(2, response.getFileCount());
        Assertions.assertTrue(response.getChunkCount() >= 2);
        Assertions.assertNotNull(response.getLastIndexedAt());
        Assertions.assertFalse(codeChunkRepository.findByRepoIdOrderByFilePathAscStartLineAsc(repo.getId()).isEmpty());
        Assertions.assertTrue(codeChunkRepository.findByRepoIdOrderByFilePathAscStartLineAsc(repo.getId())
                .stream()
                .noneMatch(chunk -> chunk.getFilePath().contains("node_modules")));
    }
}
