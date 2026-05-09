package com.yupi.yuaiagent.repo.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RepoFileScanner {

    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", "node_modules", "target", "dist", "build", ".idea", ".vscode", "coverage"
    );

    private static final Map<String, String> SUPPORTED_EXTENSIONS = Map.ofEntries(
            Map.entry(".java", "java"),
            Map.entry(".py", "python"),
            Map.entry(".ts", "typescript"),
            Map.entry(".tsx", "tsx"),
            Map.entry(".js", "javascript"),
            Map.entry(".jsx", "jsx"),
            Map.entry(".go", "go"),
            Map.entry(".md", "markdown"),
            Map.entry(".yml", "yaml"),
            Map.entry(".yaml", "yaml"),
            Map.entry(".sql", "sql"),
            Map.entry(".properties", "properties"),
            Map.entry(".xml", "xml"),
            Map.entry(".json", "json")
    );

    public List<ScannedRepoFile> scan(Path repoRootPath) {
        if (repoRootPath == null || !Files.isDirectory(repoRootPath)) {
            throw new IllegalArgumentException("Repo local path does not exist or is not a directory: " + repoRootPath);
        }
        List<ScannedRepoFile> files = new ArrayList<>();
        try {
            Files.walkFileTree(repoRootPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!repoRootPath.equals(dir) && IGNORED_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String language = detectLanguage(file.getFileName().toString());
                    if (language == null) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relativePath = repoRootPath.relativize(file).toString().replace("\\", "/");
                    files.add(new ScannedRepoFile(file, relativePath, language));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan repo files: " + e.getMessage(), e);
        }
        return files;
    }

    private String detectLanguage(String fileName) {
        String lowerName = fileName.toLowerCase();
        return SUPPORTED_EXTENSIONS.entrySet()
                .stream()
                .filter(entry -> lowerName.endsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}
