package com.yupi.yuaiagent.llm;

import com.yupi.yuaiagent.llm.dto.LlmRequest;
import com.yupi.yuaiagent.llm.dto.LlmResponse;
import com.yupi.yuaiagent.llm.dto.LlmStructuredResponse;

public interface LlmService {

    LlmResponse chat(LlmRequest request);

    <T> LlmStructuredResponse<T> chatForObject(LlmRequest request, Class<T> responseType);
}
