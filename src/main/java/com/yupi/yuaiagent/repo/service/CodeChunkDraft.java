package com.yupi.yuaiagent.repo.service;

import com.yupi.yuaiagent.repo.enums.CodeChunkType;

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
