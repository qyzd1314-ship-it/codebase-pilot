package com.yupi.yuaiagent.task.service;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.repo.entity.CodeChunk;
import com.yupi.yuaiagent.repo.repository.CodeChunkRepository;
import com.yupi.yuaiagent.task.dto.RepoCandidateModuleDto;
import com.yupi.yuaiagent.task.dto.RepoProfileDto;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class RepoProfiler {

    private static final Pattern CAMEL_SPLIT = Pattern.compile("(?<!^)(?=[A-Z])");
    private static final Set<String> GENERIC_TERMS = Set.of(
            "common", "config", "util", "utils", "base", "core", "shared", "model", "entity",
            "dto", "vo", "bo", "impl", "application", "repository", "mapper", "service", "controller"
    );

    private final CodeChunkRepository codeChunkRepository;

    public RepoProfiler(CodeChunkRepository codeChunkRepository) {
        this.codeChunkRepository = codeChunkRepository;
    }

    public RepoProfileDto buildProfile(String repoId) {
        List<CodeChunk> chunks = codeChunkRepository.findByRepoIdOrderByFilePathAscStartLineAsc(repoId);
        LinkedHashSet<String> frameworkHints = new LinkedHashSet<>();
        LinkedHashSet<String> layers = new LinkedHashSet<>();
        Map<String, ModuleAccumulator> modules = new LinkedHashMap<>();

        for (CodeChunk chunk : chunks) {
            detectFrameworkHints(chunk, frameworkHints);
            detectLayer(chunk).ifPresent(layers::add);
            extractModuleCandidate(chunk).ifPresent(candidate -> {
                ModuleAccumulator accumulator = modules.computeIfAbsent(candidate, key -> new ModuleAccumulator());
                accumulator.displayName = accumulator.displayName == null ? candidate : accumulator.displayName;
                accumulator.files.add(fileName(chunk.getFilePath()));
                detectLayer(chunk).ifPresent(layer -> accumulator.layerFiles.computeIfAbsent(layer, ignored -> new ArrayList<>()).add(fileName(chunk.getFilePath())));
                accumulator.keywords.add(candidate);
                accumulator.keywords.addAll(splitKeywords(candidate));
            });
        }

        List<RepoCandidateModuleDto> candidateModules = modules.entrySet().stream()
                .map(entry -> toCandidateModule(entry.getKey(), entry.getValue()))
                .filter(module -> module.getWeight() > 0)
                .sorted(Comparator.comparing(RepoCandidateModuleDto::getWeight).reversed()
                        .thenComparing(RepoCandidateModuleDto::getName))
                .limit(12)
                .toList();

        return RepoProfileDto.builder()
                .repoId(repoId)
                .projectType(detectProjectType(chunks, frameworkHints))
                .frameworkHints(List.copyOf(frameworkHints))
                .layers(List.copyOf(layers))
                .candidateModules(candidateModules)
                .build();
    }

    private void detectFrameworkHints(CodeChunk chunk, Set<String> frameworkHints) {
        String filePath = normalize(chunk.getFilePath());
        String content = normalize(chunk.getContent());
        if (filePath.endsWith("pom.xml") || content.contains("springapplication") || content.contains("@springbootapplication")) {
            frameworkHints.add("Spring Boot");
        }
        if (content.contains("mybatis") || filePath.contains("mapper")) {
            frameworkHints.add("MyBatis");
        }
        if (filePath.endsWith("package.json") || filePath.contains("src/views") || filePath.endsWith(".vue")) {
            frameworkHints.add("Vue");
        }
        if (filePath.endsWith(".js") || filePath.endsWith(".ts")) {
            frameworkHints.add("JavaScript/TypeScript");
        }
    }

    private String detectProjectType(List<CodeChunk> chunks, Set<String> frameworkHints) {
        boolean hasJava = chunks.stream().anyMatch(chunk -> "java".equalsIgnoreCase(chunk.getLanguage()));
        boolean hasFrontend = chunks.stream().anyMatch(chunk -> {
            String path = normalize(chunk.getFilePath());
            return path.endsWith(".vue") || path.endsWith(".ts") || path.endsWith(".tsx");
        });
        if (hasJava && hasFrontend) {
            return "FULL_STACK_WEB";
        }
        if (frameworkHints.contains("Spring Boot")) {
            return "SPRING_BOOT_SERVICE";
        }
        if (hasFrontend) {
            return "FRONTEND_APPLICATION";
        }
        return "CODEBASE_PROJECT";
    }

    private java.util.Optional<String> detectLayer(CodeChunk chunk) {
        String filePath = normalize(chunk.getFilePath());
        String symbol = normalize(chunk.getSymbolName());
        if (filePath.contains("controller") || symbol.endsWith("controller")) {
            return java.util.Optional.of("controller");
        }
        if (filePath.contains("service") || symbol.endsWith("service")) {
            return java.util.Optional.of("service");
        }
        if (filePath.contains("mapper") || symbol.endsWith("mapper")) {
            return java.util.Optional.of("mapper");
        }
        if (filePath.contains("repository") || symbol.endsWith("repository")) {
            return java.util.Optional.of("repository");
        }
        if (filePath.contains("config") || symbol.endsWith("config")) {
            return java.util.Optional.of("config");
        }
        if (filePath.contains("entity") || filePath.contains("model")) {
            return java.util.Optional.of("entity");
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<String> extractModuleCandidate(CodeChunk chunk) {
        String symbol = StrUtil.blankToDefault(chunk.getSymbolName(), fileName(chunk.getFilePath()));
        String baseName = stripKnownSuffixes(symbol);
        List<String> parts = splitCamelWords(baseName);
        if (parts.isEmpty()) {
            return java.util.Optional.empty();
        }
        List<String> filtered = parts.stream()
                .map(this::normalize)
                .filter(StrUtil::isNotBlank)
                .filter(part -> !GENERIC_TERMS.contains(part))
                .toList();
        if (filtered.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(String.join(" ", filtered));
    }

    private RepoCandidateModuleDto toCandidateModule(String name, ModuleAccumulator accumulator) {
        int weight = accumulator.files.size();
        if (accumulator.layerFiles.containsKey("controller")) {
            weight += 2;
        }
        if (accumulator.layerFiles.containsKey("service")) {
            weight += 2;
        }
        if (accumulator.layerFiles.containsKey("mapper") || accumulator.layerFiles.containsKey("repository")) {
            weight += 2;
        }
        return RepoCandidateModuleDto.builder()
                .name(name)
                .displayName(name)
                .keywords(List.copyOf(accumulator.keywords))
                .files(accumulator.files.stream().distinct().toList())
                .layerFiles(accumulator.layerFiles.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().stream().distinct().toList(),
                                (left, right) -> right, LinkedHashMap::new)))
                .weight(weight)
                .build();
    }

    private List<String> splitKeywords(String candidate) {
        List<String> terms = new ArrayList<>();
        terms.add(candidate);
        terms.add(candidate.replace(" ", ""));
        String[] segments = candidate.split("\\s+");
        terms.addAll(Arrays.asList(segments));
        return terms.stream().map(this::normalize).filter(StrUtil::isNotBlank).distinct().toList();
    }

    private String stripKnownSuffixes(String symbol) {
        String result = symbol;
        for (String suffix : List.of("Controller", "Service", "Mapper", "Repository", "Config", "Entity", "DTO", "VO", "BO", "Impl")) {
            if (result.endsWith(suffix) && result.length() > suffix.length()) {
                result = result.substring(0, result.length() - suffix.length());
                break;
            }
        }
        return result;
    }

    private List<String> splitCamelWords(String value) {
        return Arrays.stream(CAMEL_SPLIT.split(fileNameWithoutExtension(value)))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .toList();
    }

    private String fileName(String filePath) {
        int slashIndex = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return slashIndex >= 0 ? filePath.substring(slashIndex + 1) : filePath;
    }

    private String fileNameWithoutExtension(String filePath) {
        String fileName = fileName(filePath);
        int dotIndex = fileName.lastIndexOf('.');
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
    }

    private static final class ModuleAccumulator {
        private String displayName;
        private final Set<String> keywords = new LinkedHashSet<>();
        private final List<String> files = new ArrayList<>();
        private final Map<String, List<String>> layerFiles = new LinkedHashMap<>();
    }
}
