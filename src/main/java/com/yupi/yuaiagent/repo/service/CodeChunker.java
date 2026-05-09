package com.yupi.yuaiagent.repo.service;

import cn.hutool.core.util.StrUtil;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.Position;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.yupi.yuaiagent.repo.enums.CodeChunkType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CodeChunker {

    private static final int MAX_CHUNK_LINES = 160;

    private static final Pattern CLASS_PATTERN = Pattern.compile(
            "^\\s*(?:(?:public|private|protected|abstract|final|sealed|static|export)\\s+)*(?:class|interface|enum)\\s+([A-Za-z_][\\w$]*)",
            Pattern.MULTILINE
    );
    private static final Pattern JAVA_METHOD_PATTERN = Pattern.compile(
            "^\\s*(?:(?:public|private|protected|static|final|synchronized|abstract|default|native|strictfp)\\s+)*(?:<[^>]+>\\s*)?[\\w\\[\\]<>.,?]+\\s+([A-Za-z_][\\w$]*)\\s*\\([^\\n;{}]*\\)\\s*(?:throws\\s+[^{]+)?\\{",
            Pattern.MULTILINE
    );
    private static final Pattern JS_FUNCTION_PATTERN = Pattern.compile(
            "^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+([A-Za-z_$][\\w$]*)\\s*\\(" +
                    "|^\\s*(?:export\\s+)?(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?\\([^)]*\\)\\s*=>",
            Pattern.MULTILINE
    );
    private static final Pattern PYTHON_DEF_PATTERN = Pattern.compile(
            "^\\s*(class|def)\\s+([A-Za-z_][\\w]*)\\s*[:(]",
            Pattern.MULTILINE
    );
    private static final Pattern MARKDOWN_HEADING_PATTERN = Pattern.compile("^#{1,6}\\s+(.+)$", Pattern.MULTILINE);

    public List<CodeChunkDraft> chunk(ScannedRepoFile scannedFile) {
        List<String> lines = readLines(scannedFile);
        if (lines.isEmpty()) {
            return List.of(buildDraft(scannedFile, null, CodeChunkType.FILE, 1, 1, ""));
        }
        return switch (scannedFile.language()) {
            case "java" -> splitJava(scannedFile, lines);
            case "typescript", "tsx", "javascript", "jsx" -> splitStructured(scannedFile, lines, JS_FUNCTION_PATTERN);
            case "python" -> splitPython(scannedFile, lines);
            case "markdown" -> splitMarkdown(scannedFile, lines);
            case "yaml", "sql", "properties", "xml", "json" -> splitConfig(scannedFile, lines);
            default -> splitWholeFile(scannedFile, lines, CodeChunkType.FILE, null);
        };
    }

    private List<String> readLines(ScannedRepoFile scannedFile) {
        try {
            return Files.readAllLines(scannedFile.absolutePath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read repo file: " + scannedFile.relativePath(), e);
        }
    }

    private List<CodeChunkDraft> splitStructured(ScannedRepoFile scannedFile, List<String> lines, Pattern functionPattern) {
        List<ChunkBoundary> boundaries = new ArrayList<>();
        boundaries.addAll(findBoundaries(lines, CLASS_PATTERN, CodeChunkType.CLASS));
        boundaries.addAll(findBoundaries(lines, functionPattern, CodeChunkType.FUNCTION));
        if (boundaries.isEmpty()) {
            return splitWholeFile(scannedFile, lines, CodeChunkType.FILE, null);
        }
        return buildChunksFromBoundaries(scannedFile, lines, boundaries);
    }

    private List<CodeChunkDraft> splitJava(ScannedRepoFile scannedFile, List<String> lines) {
        try {
            CompilationUnit compilationUnit = StaticJavaParser.parse(String.join("\n", lines));
            List<AstChunk> astChunks = new ArrayList<>();
            for (TypeDeclaration<?> typeDeclaration : compilationUnit.findAll(TypeDeclaration.class)) {
                if (typeDeclaration.isTopLevelType() || typeDeclaration.isNestedType()) {
                    addAstChunk(astChunks, typeDeclaration.getNameAsString(), CodeChunkType.CLASS, typeDeclaration);
                }
            }
            for (CallableDeclaration<?> callableDeclaration : compilationUnit.findAll(CallableDeclaration.class)) {
                addAstChunk(astChunks, callableDeclaration.getNameAsString(), CodeChunkType.FUNCTION, callableDeclaration);
            }
            if (astChunks.isEmpty()) {
                return splitStructured(scannedFile, lines, JAVA_METHOD_PATTERN);
            }
            return buildChunksFromAst(scannedFile, lines, astChunks);
        } catch (ParseProblemException | IllegalStateException e) {
            return splitStructured(scannedFile, lines, JAVA_METHOD_PATTERN);
        }
    }

    private List<CodeChunkDraft> splitPython(ScannedRepoFile scannedFile, List<String> lines) {
        List<ChunkBoundary> boundaries = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = PYTHON_DEF_PATTERN.matcher(lines.get(i));
            if (matcher.find()) {
                CodeChunkType chunkType = "class".equals(matcher.group(1)) ? CodeChunkType.CLASS : CodeChunkType.FUNCTION;
                boundaries.add(new ChunkBoundary(i + 1, matcher.group(2), chunkType));
            }
        }
        if (boundaries.isEmpty()) {
            return splitWholeFile(scannedFile, lines, CodeChunkType.FILE, null);
        }
        return buildChunksFromBoundaries(scannedFile, lines, boundaries);
    }

    private List<CodeChunkDraft> splitMarkdown(ScannedRepoFile scannedFile, List<String> lines) {
        List<ChunkBoundary> boundaries = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = MARKDOWN_HEADING_PATTERN.matcher(lines.get(i));
            if (matcher.find()) {
                boundaries.add(new ChunkBoundary(i + 1, matcher.group(1).trim(), CodeChunkType.DOC));
            }
        }
        if (boundaries.isEmpty()) {
            return splitWholeFile(scannedFile, lines, CodeChunkType.DOC, null);
        }
        return buildChunksFromBoundaries(scannedFile, lines, boundaries);
    }

    private List<CodeChunkDraft> splitConfig(ScannedRepoFile scannedFile, List<String> lines) {
        return splitWholeFile(scannedFile, lines, CodeChunkType.CONFIG, null);
    }

    private List<ChunkBoundary> findBoundaries(List<String> lines, Pattern pattern, CodeChunkType chunkType) {
        List<ChunkBoundary> boundaries = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = pattern.matcher(lines.get(i));
            if (matcher.find()) {
                String symbolName = firstNonBlankGroup(matcher);
                boundaries.add(new ChunkBoundary(i + 1, symbolName, chunkType));
            }
        }
        return boundaries;
    }

    private List<CodeChunkDraft> buildChunksFromBoundaries(ScannedRepoFile scannedFile,
                                                           List<String> lines,
                                                           List<ChunkBoundary> boundaries) {
        boundaries.sort((left, right) -> Integer.compare(left.startLine(), right.startLine()));
        List<CodeChunkDraft> chunks = new ArrayList<>();
        for (int i = 0; i < boundaries.size(); i++) {
            ChunkBoundary current = boundaries.get(i);
            int endLine = i + 1 < boundaries.size() ? boundaries.get(i + 1).startLine() - 1 : lines.size();
            if (endLine < current.startLine()) {
                continue;
            }
            chunks.addAll(splitByMaxLines(
                    scannedFile,
                    lines,
                    current.startLine(),
                    endLine,
                    current.chunkType(),
                    current.symbolName()
            ));
        }
        return chunks.isEmpty() ? splitWholeFile(scannedFile, lines, CodeChunkType.FILE, null) : chunks;
    }

    private List<CodeChunkDraft> splitWholeFile(ScannedRepoFile scannedFile,
                                                List<String> lines,
                                                CodeChunkType chunkType,
                                                String symbolName) {
        return splitByMaxLines(scannedFile, lines, 1, lines.size(), chunkType, symbolName);
    }

    private List<CodeChunkDraft> buildChunksFromAst(ScannedRepoFile scannedFile,
                                                    List<String> lines,
                                                    List<AstChunk> astChunks) {
        List<CodeChunkDraft> chunks = new ArrayList<>();
        astChunks.sort((left, right) -> {
            int startCompare = Integer.compare(left.startLine(), right.startLine());
            if (startCompare != 0) {
                return startCompare;
            }
            return Integer.compare(right.endLine(), left.endLine());
        });
        for (AstChunk astChunk : astChunks) {
            chunks.addAll(splitByMaxLines(
                    scannedFile,
                    lines,
                    astChunk.startLine(),
                    astChunk.endLine(),
                    astChunk.chunkType(),
                    astChunk.symbolName()
            ));
        }
        return chunks.isEmpty() ? splitStructured(scannedFile, lines, JAVA_METHOD_PATTERN) : chunks;
    }

    private List<CodeChunkDraft> splitByMaxLines(ScannedRepoFile scannedFile,
                                                 List<String> lines,
                                                 int startLine,
                                                 int endLine,
                                                 CodeChunkType chunkType,
                                                 String symbolName) {
        List<CodeChunkDraft> chunks = new ArrayList<>();
        int currentStart = startLine;
        while (currentStart <= endLine) {
            int currentEnd = Math.min(currentStart + MAX_CHUNK_LINES - 1, endLine);
            String content = joinLines(lines, currentStart, currentEnd);
            chunks.add(buildDraft(scannedFile, symbolName, chunkType, currentStart, currentEnd, content));
            currentStart = currentEnd + 1;
        }
        return chunks;
    }

    private CodeChunkDraft buildDraft(ScannedRepoFile scannedFile,
                                      String symbolName,
                                      CodeChunkType chunkType,
                                      int startLine,
                                      int endLine,
                                      String content) {
        return new CodeChunkDraft(
                scannedFile.relativePath(),
                scannedFile.language(),
                StrUtil.emptyToNull(StrUtil.trim(symbolName)),
                chunkType,
                startLine,
                endLine,
                content
        );
    }

    private String joinLines(List<String> lines, int startLine, int endLine) {
        if (lines.isEmpty()) {
            return "";
        }
        return String.join("\n", lines.subList(Math.max(0, startLine - 1), Math.min(lines.size(), endLine))).trim();
    }

    private String firstNonBlankGroup(Matcher matcher) {
        for (int i = 1; i <= matcher.groupCount(); i++) {
            if (StrUtil.isNotBlank(matcher.group(i))) {
                return matcher.group(i);
            }
        }
        return null;
    }

    private void addAstChunk(List<AstChunk> chunks,
                             String symbolName,
                             CodeChunkType chunkType,
                             com.github.javaparser.ast.Node node) {
        Position begin = node.getBegin().orElse(null);
        Position end = node.getEnd().orElse(null);
        if (begin == null || end == null) {
            return;
        }
        if (begin.line <= 0 || end.line < begin.line) {
            return;
        }
        chunks.add(new AstChunk(begin.line, end.line, symbolName, chunkType));
    }

    private record ChunkBoundary(int startLine, String symbolName, CodeChunkType chunkType) {
    }

    private record AstChunk(int startLine, int endLine, String symbolName, CodeChunkType chunkType) {
    }
}
