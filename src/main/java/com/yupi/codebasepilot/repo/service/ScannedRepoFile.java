package com.yupi.codebasepilot.repo.service;

import java.nio.file.Path;

public record ScannedRepoFile(
        Path absolutePath,
        String relativePath,
        String language
) {
}
