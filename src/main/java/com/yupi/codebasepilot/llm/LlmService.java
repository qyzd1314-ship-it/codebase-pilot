package com.yupi.codebasepilot.llm;

import com.yupi.codebasepilot.llm.dto.LlmRequest;
import com.yupi.codebasepilot.llm.dto.LlmResponse;
import com.yupi.codebasepilot.llm.dto.LlmStructuredResponse;

public interface LlmService {

    LlmResponse chat(LlmRequest request);

    <T> LlmStructuredResponse<T> chatForObject(LlmRequest request, Class<T> responseType);
}
