package com.sathish.jobhunt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sathish.jobhunt.agent.JdAnalyzerAgent;
import com.sathish.jobhunt.config.AppConfig;
import com.sathish.jobhunt.model.Job;
import com.sathish.jobhunt.model.JobMatchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JdAnalyzerAgent.
 *
 * Spring AI 1.0.0-M1 changed the ChatClient fluent API internal types.
 * We mock at the ChatModel level (lower level) to avoid coupling to
 * ChatClient's internal spec classes which can change between milestones.
 */
class JdAnalyzerAgentTest {

    private JdAnalyzerAgent agent;
    private ChatModel mockChatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        mockChatModel = mock(ChatModel.class);
        ChatClient chatClient = ChatClient.builder(mockChatModel).build();
        AppConfig config = new AppConfig();
        agent = new JdAnalyzerAgent(chatClient, config, objectMapper);
    }

    // ─── Helper: stub ChatModel to return a fixed string ──────────────────────

    private void stubResponse(String responseText) {
        ChatResponse chatResponse = mock(ChatResponse.class);
        Generation generation = mock(Generation.class);
        AssistantMessage assistantMessage = new AssistantMessage(responseText);

        when(mockChatModel.call(any(Prompt.class))).thenReturn(chatResponse);
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(assistantMessage);
    }

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    void shouldParseHighMatchScoreCorrectly() {
        stubResponse("""
            {
              "matchScore": 88,
              "matchedSkills": ["Java", "Spring Boot", "Kafka", "Microservices"],
              "missingSkills": ["Go"],
              "priority": "HIGH",
              "reasoning": "Strong match on core backend stack",
              "suggestedTitle": "Senior Backend Engineer",
              "estimatedCtcRange": "35-42 LPA",
              "keywordsToHighlight": ["Kafka", "Spring AI", "Redis"],
              "recruiterTips": "Highlight the RAG pipeline achievement"
            }
            """);

        Job job = Job.builder()
            .id("test-123")
            .title("Senior Backend Engineer")
            .company("Flipkart")
            .location("Bangalore")
            .jobDescription("Java, Spring Boot, Kafka, 5+ years experience required")
            .build();

        JobMatchResult result = agent.analyze(job);

        assertThat(result.getMatchScore()).isEqualTo(88);
        assertThat(result.getPriority()).isEqualTo("HIGH");
        assertThat(result.getMatchedSkills()).contains("Java", "Kafka");
        assertThat(result.getMissingSkills()).contains("Go");
        assertThat(result.getKeywordsToHighlight()).contains("Kafka", "Spring AI");
    }

    @Test
    void shouldHandleMarkdownWrappedJsonResponse() {
        // Ollama sometimes wraps JSON in ```json ... ``` blocks — agent must strip them
        stubResponse("```json\n{\"matchScore\":75,\"matchedSkills\":[\"Java\"]," +
            "\"missingSkills\":[],\"priority\":\"MEDIUM\",\"reasoning\":\"Good match\"," +
            "\"suggestedTitle\":\"SE\",\"estimatedCtcRange\":\"30-38 LPA\"," +
            "\"keywordsToHighlight\":[\"Java\"],\"recruiterTips\":\"Apply soon\"}\n```");

        Job job = Job.builder()
            .id("test-789").title("SE").company("Amazon")
            .location("Bangalore").jobDescription("Java Spring Boot Microservices")
            .build();

        JobMatchResult result = agent.analyze(job);

        assertThat(result.getMatchScore()).isEqualTo(75);
        assertThat(result.getPriority()).isEqualTo("MEDIUM");
    }

    @Test
    void shouldReturnFallbackWhenAiUnavailable() {
        // Fallback is called directly by Resilience4j when circuit is open
        Job job = Job.builder()
            .id("test-456").title("Lead Engineer").company("Swiggy")
            .location("Bangalore").jobDescription("Java Kafka Spring Boot")
            .build();

        JobMatchResult fallback = agent.analyzeWithFallback(job, new RuntimeException("Ollama timeout"));

        assertThat(fallback.getMatchScore()).isEqualTo(50);
        assertThat(fallback.getPriority()).isEqualTo("MEDIUM");
        assertThat(fallback.getReasoning()).contains("manual review");
        assertThat(fallback.getMatchedSkills()).isNotEmpty();
    }

    @Test
    void shouldSkipJobWithScoreBelowThreshold() {
        stubResponse("""
            {
              "matchScore": 35,
              "matchedSkills": ["Java"],
              "missingSkills": ["Golang", "Kubernetes", "Istio"],
              "priority": "LOW",
              "reasoning": "Mostly Golang stack, not a Java role",
              "suggestedTitle": "Backend Engineer",
              "estimatedCtcRange": "20-28 LPA",
              "keywordsToHighlight": ["Java"],
              "recruiterTips": "Skip this one"
            }
            """);

        Job job = Job.builder()
            .id("test-low").title("Backend Engineer (Golang)").company("Meesho")
            .location("Bangalore").jobDescription("Golang, Kubernetes, service mesh required")
            .build();

        JobMatchResult result = agent.analyze(job);

        assertThat(result.getMatchScore()).isLessThan(65);
        assertThat(result.getPriority()).isEqualTo("LOW");
    }
}
