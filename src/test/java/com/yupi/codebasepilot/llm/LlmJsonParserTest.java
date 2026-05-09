package com.yupi.codebasepilot.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LlmJsonParserTest {

    @Test
    void shouldExtractAndParseJsonObject() {
        LlmJsonParser parser = new LlmJsonParser(new ObjectMapper());
        String content = "Here is the result:\n{\"approved\":true,\"summary\":\"ok\"}\nThanks";
        ReviewPayload payload = parser.parseObject(content, ReviewPayload.class);
        Assertions.assertTrue(payload.approved);
        Assertions.assertEquals("ok", payload.summary);
    }

    private static class ReviewPayload {
        public boolean approved;
        public String summary;
    }
}
