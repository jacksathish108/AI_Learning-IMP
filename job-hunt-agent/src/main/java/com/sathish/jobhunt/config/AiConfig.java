package com.sathish.jobhunt.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    /**
     * Configure the Spring AI ChatClient with system context.
     * Uses Ollama Llama3 by default (local, free).
     * To switch to OpenAI: replace OllamaChatModel with OpenAiChatModel
     * and update application.yml accordingly.
     */
    @Bean
    public ChatClient chatClient(OllamaChatModel ollamaChatModel) {
        return ChatClient.builder(ollamaChatModel)
            .defaultSystem("""
                You are an expert technical recruiter and career coach specializing in
                senior software engineering roles in India. You help match candidates to
                jobs and tailor resumes for maximum ATS compatibility. Always be precise,
                factual, and return valid JSON when asked to.
                """)
            .build();
    }
}
