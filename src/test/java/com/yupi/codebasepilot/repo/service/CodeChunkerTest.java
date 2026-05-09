package com.yupi.codebasepilot.repo.service;

import com.yupi.codebasepilot.repo.enums.CodeChunkType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class CodeChunkerTest {

    private final CodeChunker codeChunker = new CodeChunker();

    @TempDir
    Path tempDir;

    @Test
    void shouldSplitJavaFileIntoStructuredChunks() throws IOException {
        Path file = tempDir.resolve("Sample.java");
        Files.writeString(file, """
                public class Sample {
                    private String name;

                    public void doWork() {
                        System.out.println("ok");
                    }

                    interface Worker {
                        void execute();
                    }
                }
                """, StandardCharsets.UTF_8);

        List<CodeChunkDraft> chunks = codeChunker.chunk(new ScannedRepoFile(file, "Sample.java", "java"));

        Assertions.assertFalse(chunks.isEmpty());
        Assertions.assertTrue(chunks.stream().anyMatch(chunk -> chunk.chunkType() == CodeChunkType.CLASS && "Sample".equals(chunk.symbolName())));
        Assertions.assertTrue(chunks.stream().anyMatch(chunk -> chunk.chunkType() == CodeChunkType.CLASS && "Worker".equals(chunk.symbolName())));
        Assertions.assertTrue(chunks.stream().anyMatch(chunk -> chunk.chunkType() == CodeChunkType.FUNCTION && "doWork".equals(chunk.symbolName())));
        Assertions.assertTrue(chunks.stream().anyMatch(chunk -> chunk.chunkType() == CodeChunkType.FUNCTION && "execute".equals(chunk.symbolName())));
        CodeChunkDraft classChunk = chunks.stream()
                .filter(chunk -> chunk.chunkType() == CodeChunkType.CLASS && "Sample".equals(chunk.symbolName()))
                .findFirst()
                .orElseThrow();
        CodeChunkDraft functionChunk = chunks.stream()
                .filter(chunk -> chunk.chunkType() == CodeChunkType.FUNCTION && "doWork".equals(chunk.symbolName()))
                .findFirst()
                .orElseThrow();
        Assertions.assertEquals(1, classChunk.startLine());
        Assertions.assertEquals(11, classChunk.endLine());
        Assertions.assertEquals(4, functionChunk.startLine());
        Assertions.assertEquals(6, functionChunk.endLine());
        Assertions.assertTrue(chunks.stream().allMatch(chunk -> chunk.startLine() >= 1));
        Assertions.assertTrue(chunks.stream().allMatch(chunk -> chunk.endLine() >= chunk.startLine()));
    }

    @Test
    void shouldFallbackToRegexWhenJavaAstParsingFails() throws IOException {
        Path file = tempDir.resolve("Broken.java");
        Files.writeString(file, """
                public class Broken {
                    public void broken() {
                        System.out.println("oops")
                    }
                }
                """, StandardCharsets.UTF_8);

        List<CodeChunkDraft> chunks = codeChunker.chunk(new ScannedRepoFile(file, "Broken.java", "java"));

        Assertions.assertFalse(chunks.isEmpty());
        Assertions.assertTrue(chunks.stream().anyMatch(chunk -> chunk.chunkType() == CodeChunkType.CLASS));
    }

    @Test
    void shouldSplitMarkdownByHeading() throws IOException {
        Path file = tempDir.resolve("README.md");
        Files.writeString(file, """
                # Intro
                line 1
                ## Usage
                line 2
                """, StandardCharsets.UTF_8);

        List<CodeChunkDraft> chunks = codeChunker.chunk(new ScannedRepoFile(file, "README.md", "markdown"));

        Assertions.assertEquals(2, chunks.size());
        Assertions.assertTrue(chunks.stream().allMatch(chunk -> chunk.chunkType() == CodeChunkType.DOC));
    }
}
