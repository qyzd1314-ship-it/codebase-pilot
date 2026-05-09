package com.yupi.yuaiagent.repo.service;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.repo.dto.RepoCreateRequest;
import com.yupi.yuaiagent.repo.dto.RepoResponse;
import com.yupi.yuaiagent.repo.entity.Repo;
import com.yupi.yuaiagent.repo.enums.RepoIndexedStatus;
import com.yupi.yuaiagent.repo.repository.RepoRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class RepoService {

    private static final String GITHUB_URL_PREFIX = "https://github.com/";
    private static final long GIT_CLONE_TIMEOUT_SECONDS = 120;

    private final RepoRepository repoRepository;
    private final RepoWorkspaceService repoWorkspaceService;

    public RepoService(RepoRepository repoRepository, RepoWorkspaceService repoWorkspaceService) {
        this.repoRepository = repoRepository;
        this.repoWorkspaceService = repoWorkspaceService;
    }

    public RepoResponse createRepo(RepoCreateRequest request) {
        validateCreateRequest(request);
        String normalizedUrl = request.getUrl().trim();
        String branch = StrUtil.blankToDefault(StrUtil.trim(request.getBranch()), "main");
        String repoId = generateRepoId();
        String repoName = extractRepoName(normalizedUrl);
        File localDir = repoWorkspaceService.buildRepoDir(repoId);
        if (localDir.exists()) {
            throw new IllegalStateException("Repo local path already exists: " + localDir.getAbsolutePath());
        }

        Repo repo = new Repo();
        repo.setId(repoId);
        repo.setName(repoName);
        repo.setUrl(normalizedUrl);
        repo.setBranch(branch);
        repo.setLocalPath(localDir.getAbsolutePath());
        repo.setIndexedStatus(RepoIndexedStatus.PENDING);
        repo.setFileCount(0);
        repo.setChunkCount(0);
        repo.setLastIndexedAt(null);
        repo = repoRepository.save(repo);

        try {
            cloneRepo(repo);
            repo.setIndexedStatus(RepoIndexedStatus.CLONED);
            repo = repoRepository.save(repo);
            return toResponse(repo);
        } catch (Exception e) {
            cleanupLocalDir(localDir.toPath());
            repo.setIndexedStatus(RepoIndexedStatus.FAILED);
            repoRepository.save(repo);
            throw new IllegalStateException("Failed to clone repo: " + e.getMessage(), e);
        }
    }

    public List<RepoResponse> listRepos() {
        return repoRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public RepoResponse getRepo(String repoId) {
        Repo repo = repoRepository.findById(repoId)
                .orElseThrow(() -> new EntityNotFoundException("Repo not found: " + repoId));
        return toResponse(repo);
    }

    private void validateCreateRequest(RepoCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (StrUtil.isBlank(request.getUrl())) {
            throw new IllegalArgumentException("url is required.");
        }
        String normalizedUrl = request.getUrl().trim();
        if (!normalizedUrl.startsWith(GITHUB_URL_PREFIX)) {
            throw new IllegalArgumentException("Only GitHub HTTPS URLs are allowed.");
        }
    }

    private void cloneRepo(Repo repo) throws IOException, InterruptedException {
        File targetDir = new File(repo.getLocalPath());
        if (targetDir.exists()) {
            throw new IllegalStateException("Repo local path already exists: " + targetDir.getAbsolutePath());
        }
        File parentDir = repoWorkspaceService.getReposRootDir();
        List<String> command = List.of(
                "git",
                "clone",
                "--depth=1",
                "--branch",
                repo.getBranch(),
                repo.getUrl(),
                targetDir.getAbsolutePath()
        );
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(parentDir);
        processBuilder.redirectErrorStream(true);
        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to start git clone process. Please ensure git is installed and available.", e);
        }
        String output = readProcessOutput(process);
        boolean finished = process.waitFor(GIT_CLONE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("Git clone timed out after " + GIT_CLONE_TIMEOUT_SECONDS + " seconds.");
        }
        if (process.exitValue() != 0) {
            String message = StrUtil.blankToDefault(output, "unknown git error");
            throw new IllegalStateException(message);
        }
    }

    private String readProcessOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!output.isEmpty()) {
                    output.append(System.lineSeparator());
                }
                output.append(line);
            }
        }
        return output.toString().trim();
    }

    private void cleanupLocalDir(Path localPath) {
        if (!Files.exists(localPath)) {
            return;
        }
        try (var pathStream = Files.walk(localPath)) {
            pathStream.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException ignored) {
        }
    }

    private String generateRepoId() {
        return "repo_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String extractRepoName(String url) {
        String normalized = StrUtil.removeSuffix(url, "/");
        String name = StrUtil.subAfter(normalized, "/", true);
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        return StrUtil.blankToDefault(name, "unknown-repo");
    }

    private RepoResponse toResponse(Repo repo) {
        return RepoResponse.builder()
                .repoId(repo.getId())
                .name(repo.getName())
                .url(repo.getUrl())
                .branch(repo.getBranch())
                .localPath(repo.getLocalPath())
                .indexedStatus(repo.getIndexedStatus().name())
                .fileCount(repo.getFileCount())
                .chunkCount(repo.getChunkCount())
                .lastIndexedAt(repo.getLastIndexedAt())
                .createdAt(repo.getCreatedAt())
                .updatedAt(repo.getUpdatedAt())
                .build();
    }
}
