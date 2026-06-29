package cn.ethan.ai.test.spring.ai;

import cn.ethan.ai.test.support.ManualTestGate;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionResultVO;
import cn.ethan.ai.domain.agent.service.rag.RagIngestionService;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

@Slf4j
@SpringBootTest
public class OpenAiIT {

    @BeforeAll
    public static void beforeClass() {
        ManualTestGate.requireRealAi("OpenAiTest");
    }

    @Value("classpath:data/dog.png")
    private Resource imageResource;

    @Value("classpath:data/file.txt")
    private Resource textResource;

    @Value("classpath:data/spring-ai-mcp-client.md")
    private Resource springAiMcpClientMarkdown;

    @Value("classpath:data/rag-evidence-retrieval.md")
    private Resource ragEvidenceMarkdown;

    @Autowired
    private OpenAiChatModel openAiChatModel;

    @jakarta.annotation.Resource(name = "vectorStore")
    private PgVectorStore pgVectorStore;

    @jakarta.annotation.Resource
    private RagIngestionService ragIngestionService;

    @Test
    public void test_call() {
        ChatResponse response = openAiChatModel.call(new Prompt(
                "1+1",
                OpenAiChatOptions.builder()
                        .model("qwen3.7-max")
                        .build()));

        log.info("测试结果(call):{}", JSON.toJSONString(response));
    }

    @Test
    public void test_call_images() {
        UserMessage userMessage = UserMessage.builder()
                .text("请描述这张图片的主要内容，并说明图中物品的可能用途。")
                .media(org.springframework.ai.content.Media.builder()
                        .mimeType(MimeType.valueOf(MimeTypeUtils.IMAGE_PNG_VALUE))
                        .data(imageResource)
                        .build())
                .build();

        ChatResponse response = openAiChatModel.call(new Prompt(
                userMessage,
                OpenAiChatOptions.builder()
                        .model("qwen3.7-max")
                        .build()));

        log.info("测试结果(images):{}", JSON.toJSONString(response));
    }

    @Test
    public void test_stream() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);

        Flux<ChatResponse> stream = openAiChatModel.stream(new Prompt(
                "1+1",
                OpenAiChatOptions.builder()
                        .model("qwen3.7-max")
                        .build()));

        stream.subscribe(
                chatResponse -> {
                    AssistantMessage output = chatResponse.getResult().getOutput();
                    log.info("测试结果(stream): {}", JSON.toJSONString(output));
                },
                Throwable::printStackTrace,
                () -> {
                    countDownLatch.countDown();
                    log.info("测试结果(stream): done!");
                }
        );

        countDownLatch.await();
    }

    @Test
    public void uploadMarkdownParentChild() throws IOException {
        ManualTestGate.requireDbMutation("OpenAiTest.uploadMarkdownParentChild");

        RagIngestionResultVO mcpGuideResult = ragIngestionService.ingestMarkdown(
                "rag-agent-station",
                "Spring AI MCP Client 使用指南",
                "spring-ai-mcp-client.md",
                readUtf8Resource(springAiMcpClientMarkdown)
        );

        RagIngestionResultVO ragGuideResult = ragIngestionService.ingestMarkdown(
                "rag-agent-station",
                "Evidence Retrieval 说明",
                "rag-evidence-retrieval.md",
                readUtf8Resource(ragEvidenceMarkdown)
        );

        log.info("Markdown RAG 导入完成，结果1:{}，结果2:{}",
                JSON.toJSONString(mcpGuideResult),
                JSON.toJSONString(ragGuideResult));
    }

    @Test
    public void chat() {
        String message = "Spring AI MCP Client 常见的接入方式有哪些？";

        String SYSTEM_PROMPT = """
                Use the information from the DOCUMENTS section to provide accurate answers but act as if you knew this information innately.
                If unsure, simply state that you don't know.
                Another thing you need to note is that your reply must be in Chinese!
                DOCUMENTS:
                    {documents}
                """;

        SearchRequest request = SearchRequest.builder()
                .query(message)
                .topK(5)
                .filterExpression("rag_id == 'rag-agent-station'")
                .build();

        List<Document> documents = pgVectorStore.similaritySearch(request);

        String documentsCollectors = null == documents ? "" : documents.stream().map(Document::getText).collect(Collectors.joining());

        Message ragMessage = new SystemPromptTemplate(SYSTEM_PROMPT).createMessage(Map.of("documents", documentsCollectors));

        ArrayList<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(message));
        messages.add(ragMessage);

        ChatResponse chatResponse = openAiChatModel.call(new Prompt(
                messages,
                OpenAiChatOptions.builder()
                        .model("qwen3.7-max")
                        .build()));

        log.info("测试结果:{}", JSON.toJSONString(chatResponse));
    }

    private String readUtf8Resource(Resource resource) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            return FileCopyUtils.copyToString(reader);
        }
    }

}
