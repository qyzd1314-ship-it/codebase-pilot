package com.yupi.codebasepilot.repo.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class RepoFileScannerTest {

    private final RepoFileScanner repoFileScanner = new RepoFileScanner();

    @TempDir
    Path tempDir;

    @Test
    void shouldScanSupportedFilesAndIgnoreConfiguredDirs() throws IOException {
        Files.writeString(tempDir.resolve("App.java"), "class App {}", StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/index.ts"), "export function run() {}", StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve("node_modules/pkg"));
        Files.writeString(tempDir.resolve("node_modules/pkg/ignored.js"), "console.log('ignore')", StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve(".git"));
        Files.writeString(tempDir.resolve(".git/config"), "ignored", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("README.md"), "# demo", StandardCharsets.UTF_8);

        List<ScannedRepoFile> scannedFiles = repoFileScanner.scan(tempDir);

        Assertions.assertEquals(3, scannedFiles.size());
        Assertions.assertTrue(scannedFiles.stream().anyMatch(file -> "App.java".equals(file.relativePath())));
        Assertions.assertTrue(scannedFiles.stream().anyMatch(file -> "src/index.ts".equals(file.relativePath())));
        Assertions.assertTrue(scannedFiles.stream().anyMatch(file -> "README.md".equals(file.relativePath())));
        Assertions.assertFalse(scannedFiles.stream().anyMatch(file -> file.relativePath().contains("node_modules")));
        Assertions.assertFalse(scannedFiles.stream().anyMatch(file -> file.relativePath().contains(".git")));
    }
}
