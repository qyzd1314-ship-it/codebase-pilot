package com.yupi.yuaiagent.app;

import com.yupi.yuaiagent.advisor.MyLoggerAdvisor;
import com.yupi.yuaiagent.rag.QueryRewriter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

@Component
@ConditionalOnBean(name = "dashscopeChatModel")
public class LoveApp {

    private static final String SYSTEM_PROMPT = "你是一位资深恋爱顾问。开场要向用户表明身份，并引导用户描述问题背景、对方反应和自己的想法，以便给出针对性建议。";

    private final ChatClient chatClient;

    @Autowired(required = false)
    private VectorStore loveAppVectorStore;

    @Autowired(required = false)
    private Advisor loveAppRagCloudAdvisor;

    @Autowired(required = false)
    private VectorStore pgVectorVectorStore;

    @Autowired(required = false)
    private QueryRewriter queryRewriter;

    @Autowired(required = false)
    private ToolCallbackProvider toolCallbackProvider;

    @Autowired
    private ToolCallback[] allTools;

    public LoveApp(ChatModel dashscopeChatModel) {
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        this.chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new MyLoggerAdvisor()
                )
                .build();
    }

    public String doChat(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .chatResponse();
        return chatResponse.getResult().getOutput().getText();
    }

    public Flux<String> doChatByStream(String message, String chatId) {
        return chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .stream()
                .content();
    }

    record LoveReport(String title, List<String> suggestions) {
    }

    public LoveReport doChatWithReport(String message, String chatId) {
        return chatClient
                .prompt()
                .system(SYSTEM_PROMPT + " 每次对话后输出恋爱建议报告，包含标题和建议列表。")
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .entity(LoveReport.class);
    }

    public String doChatWithRag(String message, String chatId) {
        if (loveAppVectorStore == null) {
            throw new IllegalStateException("RAG feature is disabled. Enable yuai.features.rag-enabled to use it.");
        }
        String rewrittenMessage = queryRewriter == null ? message : queryRewriter.doQueryRewrite(message);
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(rewrittenMessage)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .advisors(new MyLoggerAdvisor())
                .advisors(new QuestionAnswerAdvisor(loveAppVectorStore))
                .call()
                .chatResponse();
        return chatResponse.getResult().getOutput().getText();
    }

    public String doChatWithTools(String message, String chatId) {
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(allTools)
                .call()
                .chatResponse();
        return chatResponse.getResult().getOutput().getText();
    }

    public String doChatWithMcp(String message, String chatId) {
        if (toolCallbackProvider == null) {
            throw new IllegalStateException("MCP feature is disabled or not configured.");
        }
        ChatResponse chatResponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, chatId))
                .advisors(new MyLoggerAdvisor())
                .toolCallbacks(toolCallbackProvider)
                .call()
                .chatResponse();
        return chatResponse.getResult().getOutput().getText();
    }
}
