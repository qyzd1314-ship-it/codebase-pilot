package com.yupi.codebasepilot.eval.dto;

public enum EvalStrategy {

    KEYWORD_ONLY,
    VECTOR_ONLY,
    HYBRID,
    LLM_ONLY,
    RAG_ONLY,
    AGENT_RAG_REVIEWER,
    AGENT_RAG_REVIEWER_PATCH
}
