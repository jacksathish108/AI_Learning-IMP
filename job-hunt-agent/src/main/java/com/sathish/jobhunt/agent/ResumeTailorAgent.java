package com.sathish.jobhunt.agent;

import com.sathish.jobhunt.config.AppConfig;
import com.sathish.jobhunt.model.Job;
import com.sathish.jobhunt.model.JobMatchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Agent 2 — Resume Tailor Agent
 *
 * Uses Spring AI + Ollama (Llama3) to rewrite resume bullets
 * to match JD keywords WITHOUT changing any facts.
 * Also generates a custom cover letter.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeTailorAgent {

    private final ChatClient chatClient;
    private final AppConfig appConfig;

    private static final String BASE_RESUME = """
        SATHISHKUMAR K
        Senior Backend Engineer | Java | Spring Boot | Apache Kafka | Spring AI | RAG Pipelines | Distributed Systems
        Bangalore, India | +91 90950 27108 | sathishkumar9095027108@gmail.com
        linkedin.com/in/sathishkumar108 | github.com/jacksathish108
        
        PROFESSIONAL SUMMARY
        Senior Backend Engineer with 8.8+ years building high-throughput, low-latency distributed systems.
        Core expertise in Java, Spring Boot, Apache Kafka, Redis, and Spring AI. Recently delivered a production
        RAG pipeline using Spring AI, Oracle Vector DB, and Ollama (Llama3 + nomic-embed-text). Achieved 35% API
        latency reduction, 40% DB performance improvement, and 99.9% uptime at 3,000–5,000 TPS.
        
        KEY ACHIEVEMENTS
        - 35% API latency reduction via multi-layer Redis + Caffeine caching
        - 40% DB performance boost through query optimization and indexing
        - 5M+ Kafka events/day with exactly-once semantics and DLQ handling
        - Production RAG pipeline: Spring AI + Oracle Vector DB + Ollama (Llama3 + nomic-embed-text)
        
        TECHNICAL SKILLS
        Languages: Java 8/11/17, Spring Boot, Spring MVC, Spring Security, Spring Data JPA, Hibernate
        AI/RAG: Spring AI, RAG, Ollama, Vector Embeddings, Semantic Search, Prompt Engineering
        Architecture: Microservices, Event-Driven, CQRS, Saga, DDD, API Gateway, REST
        Messaging/Cache: Apache Kafka (Producer, Consumer, Streams, DLQ), Redis, Caffeine
        Databases: Oracle DB (Vector Search), MySQL, PostgreSQL, MongoDB
        Concurrency: CompletableFuture, ExecutorService, Thread Pools, JVM Tuning
        DevOps: Docker, CI/CD, Git
        Testing: JUnit 5, Mockito, Integration Testing
        
        EXPERIENCE
        
        Senior Software Engineer — Sapiens International (Jun 2022 – Present, Bangalore)
        - Architected and owned 12+ production microservices handling 3,000–5,000 TPS with sub-300ms p99 latency
        - Delivered end-to-end RAG pipeline using Spring AI, Oracle Vector DB, and Ollama (Llama3 + nomic-embed-text)
        - Engineered Kafka event-driven pipelines processing 5M+ events/day with exactly-once semantics and DLQ
        - Reduced API latency by 35% via multi-layer caching: Redis (distributed) + Caffeine (in-process)
        - Improved DB performance by 40% through query optimisation, indexing, and connection pool tuning
        - Maintained 99.9% uptime with Resilience4j circuit breakers and graceful degradation
        - Applied CQRS and Saga patterns for distributed transaction consistency
        - Mentored 6+ engineers via code reviews, architecture workshops, and onboarding docs
        
        Senior Software Engineer — Happiest Minds Technologies (Jun 2021 – Jun 2022, Bangalore)
        - Designed and scaled backend services supporting 50,000+ daily active users
        - Built Kafka consumer pipelines with custom backpressure handling
        - Improved async task execution with CompletableFuture and custom ExecutorService thread pools
        - Containerised services with Docker and established CI/CD pipelines
        
        Associate Software Engineer — Smitiv Mobile Technologies (Aug 2019 – Jun 2021, Bangalore)
        - Improved REST API response time by 25% via Redis caching and N+1 query elimination
        - Designed RESTful APIs with versioning, error handling, and Swagger/OpenAPI documentation
        - Optimised complex SQL queries with indexed views
        
        Software Engineer — Orange Sorting Machines (Jun 2017 – Jun 2019)
        - Developed Spring Boot applications for real-time industrial automation
        - Built live operational dashboards (JavaFX) with TCP/IP socket communication
        
        EDUCATION
        Master of Computer Applications (MCA) — KGISL IIM, Coimbatore, 2015–2017
        
        Open to: Senior Backend Engineer | Lead Engineer | AI/ML Backend
        Notice Period: Immediate Joiner (LWD: 13 March 2025)
        Current CTC: 30 LPA | Expected: 38 LPA (Negotiable)
        """;

    /**
     * Rewrite resume bullets to mirror JD keywords.
     * All facts remain true — only phrasing is adjusted.
     */
    public String tailorResume(Job job, JobMatchResult matchResult) {
        log.info("Tailoring resume for: {} at {}", job.getTitle(), job.getCompany());

        String keywords = matchResult.getKeywordsToHighlight() != null
            ? String.join(", ", matchResult.getKeywordsToHighlight())
            : "";

        String missingSkills = matchResult.getMissingSkills() != null
            ? matchResult.getMissingSkills().stream().collect(Collectors.joining(", "))
            : "";

        String prompt = """
            You are a professional resume writer specializing in senior tech roles.
            
            TASK: Rewrite the candidate's resume to maximize keyword match with the job description.
            
            STRICT RULES:
            1. NEVER fabricate, invent, or exaggerate any experience or skill
            2. Only reword existing bullet points — do not add new experiences
            3. Mirror the JD's exact terminology where it matches real experience
            4. Move the most relevant bullets to the TOP of each experience section
            5. Weave these keywords naturally (only if genuinely applicable): %s
            6. Keep the resume under 2 pages (600 words max)
            7. Return the complete rewritten resume as plain text
            
            JOB TITLE: %s
            COMPANY: %s
            
            JOB DESCRIPTION (key parts):
            %s
            
            MATCHED SKILLS (emphasize these): %s
            
            BASE RESUME:
            %s
            
            Return ONLY the rewritten resume text. No commentary.
            """.formatted(
                keywords,
                job.getTitle(),
                job.getCompany(),
                job.getJobDescription().substring(0, Math.min(2000, job.getJobDescription().length())),
                keywords,
                BASE_RESUME
            );

        return chatClient.prompt()
            .user(prompt)
            .call()
            .content();
    }

    /**
     * Generate a targeted cover letter for the specific role.
     */
    public String generateCoverLetter(Job job, JobMatchResult matchResult) {
        log.info("Generating cover letter for: {} at {}", job.getTitle(), job.getCompany());

        String prompt = """
            Write a compelling, concise cover letter for this job application.
            
            CANDIDATE: Sathishkumar K
            - 8.8+ years Senior Backend Engineer
            - Expert in Java, Spring Boot, Apache Kafka, Spring AI, RAG Pipelines
            - Production RAG pipeline delivered (Spring AI + Ollama + Oracle Vector DB)
            - 35%% latency reduction, 40%% DB boost, 5M+ Kafka events/day
            - Immediate joiner | Expected CTC: 38 LPA
            
            ROLE: %s at %s
            LOCATION: %s
            
            MATCHED STRENGTHS: %s
            
            COVER LETTER REQUIREMENTS:
            - 3 paragraphs, max 200 words
            - Opening: Hook with most relevant achievement for THIS role
            - Middle: 2-3 specific technical wins that map to JD requirements
            - Closing: Clear CTA — available immediately, salary expectation
            - Professional but warm tone (not robotic)
            - NO generic phrases like "I am writing to apply" or "I am a hard worker"
            
            Return only the cover letter text.
            """.formatted(
                job.getTitle(),
                job.getCompany(),
                job.getLocation(),
                matchResult.getKeywordsToHighlight() != null
                    ? String.join(", ", matchResult.getKeywordsToHighlight())
                    : ""
            );

        return chatClient.prompt()
            .user(prompt)
            .call()
            .content();
    }

    public String getBaseResume() {
        return BASE_RESUME;
    }
}
