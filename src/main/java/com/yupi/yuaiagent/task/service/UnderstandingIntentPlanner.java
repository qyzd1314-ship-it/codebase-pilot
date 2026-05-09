package com.yupi.yuaiagent.task.service;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaiagent.task.dto.RepoCandidateModuleDto;
import com.yupi.yuaiagent.task.dto.RepoProfileDto;
import com.yupi.yuaiagent.task.dto.UnderstandingPlanDto;
import com.yupi.yuaiagent.task.enums.CodeUnderstandingIntent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class UnderstandingIntentPlanner {

    private static final Set<String> AUTH_TERMS = Set.of(
            "login", "auth", "authenticate", "authentication", "security", "jwt", "token",
            "session", "password", "captcha", "登录", "认证", "鉴权", "令牌", "密码", "会话"
    );

    public UnderstandingPlanDto plan(String userGoal, RepoProfileDto repoProfile) {
        String normalizedGoal = normalize(userGoal);
        CodeUnderstandingIntent intent = detectIntent(normalizedGoal);
        RepoCandidateModuleDto targetModule = matchTargetModule(normalizedGoal, repoProfile, intent);
        List<String> targetKeywords = buildTargetKeywords(normalizedGoal, targetModule, intent);
        List<String> expectedLayers = switch (intent) {
            case OVERALL_STRUCTURE -> List.of("controller", "service", "mapper", "repository", "config", "entity");
            case FLOW_ANALYSIS -> List.of("controller", "service", "mapper", "repository", "config");
            case MODULE_DETAIL -> List.of("controller", "service", "mapper", "repository", "entity");
            case API_CALL_CHAIN -> List.of("controller", "service", "mapper", "repository");
        };
        return UnderstandingPlanDto.builder()
                .intent(intent.name())
                .targetModule(targetModule == null ? null : targetModule.getName())
                .targetKeywords(targetKeywords)
                .expectedLayers(expectedLayers)
                .outputSchema(resolveOutputSchema(intent))
                .reason(buildReason(intent, targetModule))
                .build();
    }

    private CodeUnderstandingIntent detectIntent(String normalizedGoal) {
        if (containsAny(normalizedGoal, List.of(
                "调用链", "controller 到", "controller to", "service 到", "api call chain",
                "request mapping", "endpoint chain", "controller -> service", "service -> mapper"
        ))) {
            return CodeUnderstandingIntent.API_CALL_CHAIN;
        }
        if (containsAny(normalizedGoal, List.of(
                "整体结构", "项目结构", "整体代码结构", "主要模块", "项目架构", "overall project structure", "architecture"
        ))) {
            return CodeUnderstandingIntent.OVERALL_STRUCTURE;
        }
        if (containsAny(normalizedGoal, List.of(
                "流程", "如何", "执行", "加载", "认证", "登录", "flow", "how", "authenticate", "login"
        ))) {
            return CodeUnderstandingIntent.FLOW_ANALYSIS;
        }
        return CodeUnderstandingIntent.MODULE_DETAIL;
    }

    private RepoCandidateModuleDto matchTargetModule(String normalizedGoal,
                                                     RepoProfileDto repoProfile,
                                                     CodeUnderstandingIntent intent) {
        if (repoProfile == null || repoProfile.getCandidateModules() == null || repoProfile.getCandidateModules().isEmpty()) {
            return null;
        }
        return repoProfile.getCandidateModules().stream()
                .map(module -> new Match(module, scoreModule(normalizedGoal, module, intent)))
                .filter(match -> match.score > 0)
                .sorted((left, right) -> Integer.compare(right.score, left.score))
                .map(Match::module)
                .findFirst()
                .orElse(null);
    }

    private int scoreModule(String normalizedGoal, RepoCandidateModuleDto module, CodeUnderstandingIntent intent) {
        int score = 0;
        if (module.getKeywords() != null) {
            for (String keyword : module.getKeywords()) {
                String normalizedKeyword = normalize(keyword);
                if (normalizedGoal.contains(normalizedKeyword)) {
                    score += 4;
                }
                if (intent == CodeUnderstandingIntent.FLOW_ANALYSIS && AUTH_TERMS.contains(normalizedKeyword)) {
                    score += 2;
                }
            }
        }
        if (module.getName() != null && normalizedGoal.contains(normalize(module.getName()))) {
            score += 5;
        }
        if (intent == CodeUnderstandingIntent.API_CALL_CHAIN && module.getLayerFiles() != null) {
            if (module.getLayerFiles().containsKey("controller")) score += 2;
            if (module.getLayerFiles().containsKey("service")) score += 2;
            if (module.getLayerFiles().containsKey("mapper") || module.getLayerFiles().containsKey("repository")) score += 2;
        }
        if (intent == CodeUnderstandingIntent.MODULE_DETAIL && module.getFiles() != null && module.getFiles().size() >= 2) {
            score += 2;
        }
        return score;
    }

    private List<String> buildTargetKeywords(String normalizedGoal,
                                             RepoCandidateModuleDto targetModule,
                                             CodeUnderstandingIntent intent) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        if (targetModule != null) {
            keywords.add(targetModule.getName());
            if (targetModule.getKeywords() != null) {
                keywords.addAll(targetModule.getKeywords());
            }
        }
        if (keywords.isEmpty()) {
            extractGoalTerms(normalizedGoal).stream().limit(8).forEach(keywords::add);
        }
        if (intent == CodeUnderstandingIntent.FLOW_ANALYSIS && containsAuthGoal(normalizedGoal)) {
            keywords.addAll(List.of(
                    "login", "auth", "security", "jwt", "token", "session", "password",
                    "UserDetailsService", "loadUserByUsername", "AuthenticationManager",
                    "AuthenticationSuccessHandler", "AuthenticationFailureHandler",
                    "DecisionManager", "MetadataSource", "FilterSecurityInterceptor"
            ));
        }
        if (intent == CodeUnderstandingIntent.API_CALL_CHAIN) {
            keywords.addAll(List.of("controller", "service", "mapper", "repository"));
        }
        return List.copyOf(keywords);
    }

    private boolean containsAuthGoal(String normalizedGoal) {
        return AUTH_TERMS.stream().anyMatch(normalizedGoal::contains);
    }

    private String resolveOutputSchema(CodeUnderstandingIntent intent) {
        return switch (intent) {
            case OVERALL_STRUCTURE -> "MODULE_SUMMARY";
            case FLOW_ANALYSIS -> "FLOW_SUMMARY";
            case MODULE_DETAIL -> "MODULE_DETAIL";
            case API_CALL_CHAIN -> "CALL_CHAIN";
        };
    }

    private String buildReason(CodeUnderstandingIntent intent, RepoCandidateModuleDto targetModule) {
        if (targetModule == null) {
            return "Intent was inferred from the user goal, but no strong repository module match was found.";
        }
        return "Intent %s was selected and matched to repository module '%s'."
                .formatted(intent.name(), targetModule.getName());
    }

    private List<String> extractGoalTerms(String normalizedGoal) {
        List<String> terms = new ArrayList<>();
        for (String part : normalizedGoal.split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (part.length() >= 2) {
                terms.add(part);
            }
        }
        return terms.stream().distinct().toList();
    }

    private boolean containsAny(String normalizedGoal, List<String> keywords) {
        return keywords.stream().map(this::normalize).anyMatch(normalizedGoal::contains);
    }

    private String normalize(String text) {
        return StrUtil.blankToDefault(text, "").toLowerCase(Locale.ROOT).trim();
    }

    private record Match(RepoCandidateModuleDto module, int score) {
    }
}
