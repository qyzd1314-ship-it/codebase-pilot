package com.yupi.yuaiagent.task.agent.impl;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.repo.dto.CodeSearchRequest;
import com.yupi.yuaiagent.repo.dto.CodeSearchResponse;
import com.yupi.yuaiagent.repo.dto.CodeSearchResultDto;
import com.yupi.yuaiagent.repo.service.CodeSearchService;
import com.yupi.yuaiagent.task.agent.Agent;
import com.yupi.yuaiagent.task.agent.AgentContext;
import com.yupi.yuaiagent.task.agent.AgentResult;
import com.yupi.yuaiagent.task.agent.NextAction;
import com.yupi.yuaiagent.task.dto.EvidenceRefDto;
import com.yupi.yuaiagent.task.dto.RepoCandidateModuleDto;
import com.yupi.yuaiagent.task.dto.RepoProfileDto;
import com.yupi.yuaiagent.task.dto.UnderstandingPlanDto;
import com.yupi.yuaiagent.task.enums.CodeUnderstandingIntent;
import com.yupi.yuaiagent.task.service.RepoProfiler;
import com.yupi.yuaiagent.task.service.UnderstandingIntentPlanner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CodeSearchAgent implements Agent {

    private final CodeSearchService codeSearchService;
    private final RepoProfiler repoProfiler;
    private final UnderstandingIntentPlanner understandingIntentPlanner;

    public CodeSearchAgent(CodeSearchService codeSearchService,
                           RepoProfiler repoProfiler,
                           UnderstandingIntentPlanner understandingIntentPlanner) {
        this.codeSearchService = codeSearchService;
        this.repoProfiler = repoProfiler;
        this.understandingIntentPlanner = understandingIntentPlanner;
    }

    @Override
    public String name() {
        return "CodeSearchAgent";
    }

    @Override
    public AgentResult run(AgentContext context) {
        if (StrUtil.isBlank(context.getRepoId())) {
            return AgentResult.builder()
                    .success(false)
                    .summary("Code search cannot run because repoId is missing.")
                    .structuredOutput(Map.of())
                    .evidenceRefs(List.of())
                    .confidence(0D)
                    .nextAction(NextAction.REPLAN)
                    .failureReason("repoId is required for CodeSearchAgent.")
                    .build();
        }
        RepoProfileDto repoProfile = isCodeUnderstanding(context) ? repoProfiler.buildProfile(context.getRepoId()) : null;
        UnderstandingPlanDto understandingPlan = isCodeUnderstanding(context)
                ? understandingIntentPlanner.plan(context.getUserGoal(), repoProfile)
                : null;
        CodeUnderstandingIntent understandingIntent = resolveUnderstandingIntent(context, understandingPlan);
        List<String> searchQueries = buildSearchQueries(context, understandingIntent, repoProfile, understandingPlan);

        CodeSearchRequest request = new CodeSearchRequest();
        request.setQuery(String.join(" ", searchQueries));
        request.setTopK(resolveTopK(context.getBusinessType()));
        CodeSearchResponse response = codeSearchService.search(context.getRepoId(), request);
        List<EvidenceRefDto> evidenceRefs = response.getResults().stream()
                .map(result -> toEvidenceRef(context.getRepoId(), result))
                .toList();

        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("understandingIntent", understandingIntent.name());
        structuredOutput.put("understandingPlan", understandingPlan);
        structuredOutput.put("repoProfile", summarizeRepoProfile(repoProfile));
        structuredOutput.put("searchQueries", searchQueries);
        structuredOutput.put("query", request.getQuery());
        structuredOutput.put("results", response.getResults());

        if (evidenceRefs.isEmpty()) {
            String emptySummary = isCodeUnderstanding(context)
                    ? "No relevant code evidence was found for the current code understanding goal."
                    : "No relevant code evidence was found for the current bug diagnosis goal.";
            return AgentResult.builder()
                    .success(false)
                    .summary(emptySummary)
                    .structuredOutput(structuredOutput)
                    .evidenceRefs(List.of())
                    .confidence(0.15)
                    .nextAction(NextAction.RETRY)
                    .failureReason("No code chunks matched the search query.")
                    .build();
        }

        structuredOutput.put("resultCount", evidenceRefs.size());
        String matchedSummary = isCodeUnderstanding(context)
                ? "Matched %d code evidence snippets for %s.".formatted(evidenceRefs.size(), understandingIntent.name())
                : "Matched %d code evidence snippets related to the diagnosis goal.".formatted(evidenceRefs.size());
        return AgentResult.builder()
                .success(true)
                .summary(matchedSummary)
                .structuredOutput(structuredOutput)
                .evidenceRefs(evidenceRefs)
                .confidence(Math.min(0.95, 0.45 + evidenceRefs.size() * 0.05))
                .nextAction(NextAction.CONTINUE)
                .failureReason(null)
                .build();
    }

    private List<String> buildSearchQueries(AgentContext context,
                                            CodeUnderstandingIntent understandingIntent,
                                            RepoProfileDto repoProfile,
                                            UnderstandingPlanDto understandingPlan) {
        String userGoal = StrUtil.blankToDefault(context.getUserGoal(), "");
        if (!isCodeUnderstanding(context)) {
            return List.of(userGoal);
        }

        List<String> terms = new ArrayList<>();
        terms.add(userGoal);
        switch (understandingIntent) {
            case API_CALL_CHAIN -> {
                terms.addAll(List.of("controller", "service", "mapper", "repository", "endpoint", "request mapping"));
                terms.addAll(extractGoalSpecificTerms(userGoal));
            }
            case MODULE_DETAIL -> terms.addAll(List.of("controller", "service", "mapper", "repository", "entity"));
            case FLOW_ANALYSIS -> {
                terms.addAll(List.of("controller", "service", "mapper", "repository", "config"));
                terms.addAll(buildFlowAnalysisTerms(userGoal, repoProfile, understandingPlan));
            }
            case OVERALL_STRUCTURE -> terms.addAll(List.of(
                    "README", "pom.xml", "package.json", "application.yml", "application.properties",
                    "controller", "service", "mapper", "repository", "config", "entity", "model"
            ));
        }

        if (understandingPlan != null && understandingPlan.getTargetKeywords() != null) {
            terms.addAll(understandingPlan.getTargetKeywords());
        }
        if (understandingPlan != null && understandingPlan.getExpectedLayers() != null) {
            terms.addAll(understandingPlan.getExpectedLayers());
        }
        if (repoProfile != null && understandingIntent == CodeUnderstandingIntent.OVERALL_STRUCTURE && repoProfile.getCandidateModules() != null) {
            repoProfile.getCandidateModules().stream()
                    .limit(5)
                    .map(RepoCandidateModuleDto::getName)
                    .forEach(terms::add);
        }
        if (repoProfile != null && repoProfile.getFrameworkHints() != null && containsAuthGoal(userGoal)) {
            repoProfile.getFrameworkHints().stream()
                    .filter(hint -> hint.toLowerCase().contains("spring") || hint.toLowerCase().contains("security"))
                    .forEach(terms::add);
        }
        if (understandingPlan != null
                && StrUtil.isNotBlank(understandingPlan.getTargetModule())
                && repoProfile != null
                && repoProfile.getCandidateModules() != null) {
            repoProfile.getCandidateModules().stream()
                    .filter(module -> understandingPlan.getTargetModule().equals(module.getName()))
                    .findFirst()
                    .ifPresent(module -> {
                        if (module.getFiles() != null) {
                            terms.addAll(module.getFiles());
                        }
                        if (module.getLayerFiles() != null) {
                            module.getLayerFiles().values().forEach(terms::addAll);
                        }
                    });
        }

        return terms.stream()
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
    }

    private List<String> buildFlowAnalysisTerms(String userGoal,
                                                RepoProfileDto repoProfile,
                                                UnderstandingPlanDto understandingPlan) {
        List<String> terms = new ArrayList<>();
        if (containsAuthGoal(userGoal)) {
            terms.addAll(List.of(
                    "login", "auth", "authentication", "security", "jwt", "token", "session", "password", "captcha",
                    "UserDetailsService", "UserDetails", "AuthenticationManager", "SecurityContextHolder",
                    "AuthenticationSuccessHandler", "AuthenticationFailureHandler",
                    "loadUserByUsername", "Filter", "FilterSecurityInterceptor",
                    "DecisionManager", "MetadataSource", "CustomFilterInvocationSecurityMetadataSource"
            ));
        }
        if (understandingPlan != null && StrUtil.isNotBlank(understandingPlan.getTargetModule())) {
            terms.add(understandingPlan.getTargetModule());
        }
        if (repoProfile != null && repoProfile.getCandidateModules() != null) {
            repoProfile.getCandidateModules().stream()
                    .filter(this::looksLikeAuthRelatedModule)
                    .limit(4)
                    .forEach(module -> {
                        terms.add(module.getName());
                        if (module.getKeywords() != null) {
                            terms.addAll(module.getKeywords());
                        }
                        if (module.getFiles() != null) {
                            terms.addAll(module.getFiles());
                        }
                    });
        }
        return terms;
    }

    private boolean looksLikeAuthRelatedModule(RepoCandidateModuleDto module) {
        if (module == null) {
            return false;
        }
        String name = StrUtil.blankToDefault(module.getName(), "").toLowerCase();
        if (containsAuthToken(name)) {
            return true;
        }
        if (module.getKeywords() != null && module.getKeywords().stream().map(String::toLowerCase).anyMatch(this::containsAuthToken)) {
            return true;
        }
        return module.getFiles() != null && module.getFiles().stream().map(String::toLowerCase).anyMatch(this::containsAuthToken);
    }

    private boolean containsAuthGoal(String userGoal) {
        String normalized = StrUtil.blankToDefault(userGoal, "").toLowerCase();
        return containsAuthToken(normalized)
                || normalized.contains("登录")
                || normalized.contains("认证")
                || normalized.contains("鉴权")
                || normalized.contains("令牌");
    }

    private boolean containsAuthToken(String value) {
        return value.contains("login")
                || value.contains("auth")
                || value.contains("security")
                || value.contains("token")
                || value.contains("jwt")
                || value.contains("session")
                || value.contains("password")
                || value.contains("userdetails")
                || value.contains("decisionmanager")
                || value.contains("metadatasource");
    }

    private Integer resolveTopK(String businessType) {
        return "CODE_UNDERSTANDING".equalsIgnoreCase(businessType) ? 12 : 8;
    }

    private boolean isCodeUnderstanding(AgentContext context) {
        return "CODE_UNDERSTANDING".equalsIgnoreCase(context.getBusinessType());
    }

    private CodeUnderstandingIntent resolveUnderstandingIntent(AgentContext context, UnderstandingPlanDto understandingPlan) {
        if (understandingPlan != null && StrUtil.isNotBlank(understandingPlan.getIntent())) {
            try {
                return CodeUnderstandingIntent.valueOf(understandingPlan.getIntent());
            } catch (IllegalArgumentException ignored) {
                // Fall through.
            }
        }
        Object memoryIntent = context.getMemory() == null ? null : context.getMemory().get("codeUnderstandingIntent");
        if (memoryIntent != null) {
            try {
                return CodeUnderstandingIntent.valueOf(String.valueOf(memoryIntent));
            } catch (IllegalArgumentException ignored) {
                // Fallback below.
            }
        }
        return CodeUnderstandingIntent.OVERALL_STRUCTURE;
    }

    private List<String> extractGoalSpecificTerms(String userGoal) {
        if (StrUtil.isBlank(userGoal)) {
            return List.of();
        }
        return Arrays.stream(userGoal.split("[^A-Za-z0-9_./-]+"))
                .map(String::trim)
                .filter(term -> term.length() >= 3)
                .filter(term -> Character.isUpperCase(term.charAt(0))
                        || term.contains("/")
                        || term.toLowerCase().contains("controller")
                        || term.toLowerCase().contains("service")
                        || term.toLowerCase().contains("mapper"))
                .distinct()
                .toList();
    }

    private EvidenceRefDto toEvidenceRef(String repoId, CodeSearchResultDto result) {
        return EvidenceRefDto.builder()
                .repoId(repoId)
                .chunkId(result.getChunkId())
                .filePath(result.getFilePath())
                .startLine(result.getStartLine())
                .endLine(result.getEndLine())
                .score(result.getScore())
                .reason(result.getReason())
                .codePreview(result.getContentPreview())
                .build();
    }

    private Map<String, Object> summarizeRepoProfile(RepoProfileDto repoProfile) {
        if (repoProfile == null) {
            return Map.of();
        }
        return Map.of(
                "projectType", repoProfile.getProjectType(),
                "frameworkHints", repoProfile.getFrameworkHints(),
                "layers", repoProfile.getLayers(),
                "candidateModules", repoProfile.getCandidateModules() == null ? List.of()
                        : repoProfile.getCandidateModules().stream()
                        .limit(6)
                        .map(module -> Map.of(
                                "name", module.getName(),
                                "keywords", module.getKeywords(),
                                "files", module.getFiles(),
                                "weight", module.getWeight()
                        ))
                        .toList()
        );
    }
}
