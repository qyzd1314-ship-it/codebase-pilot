package com.yupi.codebasepilot.repo.service;

import com.yupi.codebasepilot.repo.enums.CodeChunkType;

public record CodeChunkDraft(
        String filePath,
        String language,
        String symbolName,
        CodeChunkType chunkType,
        int startLine,
        int endLine,
        String content
) {
}
