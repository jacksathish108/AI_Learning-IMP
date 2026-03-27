package com.sathish.jobhunt.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sathish.jobhunt.config.AppConfig;
import com.sathish.jobhunt.model.Job;
import com.sathish.jobhunt.model.JobMatchResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * JD Analyzer Agent — fine-tuned for Sathishkumar's 8.8-year profile.
 *
 * Scoring model:
 *   - Core stack match (Java/Spring/Kafka)     → 0–40 pts
 *   - Experience level fit (6–14 yrs range)    → 0–20 pts
 *   - Architecture / AI / advanced skills      → 0–20 pts
 *   - Title / seniority match                  → 0–10 pts
 *   - Location / remote / CTC fit              → 0–10 pts
 *
 *   AUTO-DISQUALIFY (score = 0):
 *   - Requires Golang / Rust as PRIMARY language
 *   - Frontend / Mobile / DevOps / Data Engineering role
 *   - Requires < 5 or > 15 years experience
 *   - Fresher / junior / associate titles
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JdAnalyzerAgent {

    private final ChatClient chatClient;
    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;

    // ── Sathish's complete profile for the AI ─────────────────────────────────
    private static final String PROFILE = """
        === CANDIDATE PROFILE ===
        Name:            Sathishkumar K
        Total Experience: 8.8 years (Senior Backend Engineer)
        Location:        Bangalore, India
        Current CTC:     30 LPA
        Expected CTC:    38 LPA (Negotiable)
        Notice Period:   Immediate Joiner (LWD: 13 March 2025)
        
        === CORE STRENGTHS (score HIGH if JD needs these) ===
        PRIMARY LANGUAGE:   Java 8 / 11 / 17
        FRAMEWORKS:         Spring Boot, Spring MVC, Spring Security, Spring Data JPA,
                            Hibernate, Spring AI
        AI / RAG:           Spring AI, RAG Pipelines, Ollama (Llama3, nomic-embed-text),
                            Vector Embeddings, Oracle Vector DB, Semantic Search,
                            Prompt Engineering, LLM Integration
        MESSAGING:          Apache Kafka — Producer, Consumer, Streams, DLQ,
                            exactly-once semantics, consumer lag monitoring
                            5M+ events/day in production
        CACHING:            Redis (distributed), Caffeine (in-process)
                            Achieved 35% API latency reduction
        DATABASES:          Oracle DB (Vector Search), PostgreSQL, MySQL, MongoDB
        ARCHITECTURE:       Microservices, Event-Driven Architecture, CQRS, Saga Pattern,
                            Domain-Driven Design, API Gateway, REST API Design,
                            HLD / LLD, System Design
        CONCURRENCY:        CompletableFuture, ExecutorService, Thread Pools,
                            Backpressure Handling, JVM Tuning, Connection Pool Optimization
        RELIABILITY:        Resilience4j — circuit breakers, retry, health checks,
                            graceful degradation. Maintained 99.9% uptime at 3000-5000 TPS
        DEVOPS:             Docker, CI/CD Pipelines, Git
        TESTING:            JUnit 5, Mockito, Integration Testing
        
        === KEY PRODUCTION ACHIEVEMENTS ===
        - 12+ microservices at 3,000–5,000 TPS, sub-300ms p99 latency
        - Production RAG pipeline (Spring AI + Oracle Vector DB + Llama3)
        - 35% API latency reduction (Redis + Caffeine multi-layer caching)
        - 40% DB performance boost (query optimization + indexing)
        - 5M+ Kafka events/day with exactly-once semantics
        - 99.9% uptime with Resilience4j
        - Mentored 6+ engineers
        
        === EXPERIENCE TIMELINE ===
        Sapiens International    Jun 2022 – Present  (Senior Software Engineer)
        Happiest Minds           Jun 2021 – Jun 2022 (Senior Software Engineer)
        Smitiv Mobile            Aug 2019 – Jun 2021 (Associate Software Engineer)
        Orange Sorting Machines  Jun 2017 – Jun 2019 (Software Engineer)
        
        === EDUCATION ===
        MCA — KGISL IIM, Coimbatore (Bharathiar University) 2015–2017
        
        === WEAK / MISSING SKILLS (penalize if JD REQUIRES these heavily) ===
        - Golang, Rust, C++ (not in stack at all)
        - Kubernetes, Istio, Helm (light Docker only)
        - AWS, Azure, GCP cloud-native services (no cloud certifications)
        - React, Angular, Vue (pure backend engineer)
        - Data Engineering, ML/AI model training (integration only, not training)
        - Android, iOS, Mobile development
        """;

    // ── Prompt template ───────────────────────────────────────────────────────
    private String buildPrompt(Job job) {
        return """
            You are a senior technical recruiter scoring a job match with surgical precision.
            Your scores must be CONSISTENT and CALIBRATED — the same quality match always
            gets the same score. Do not be generous or harsh.
            
            %s
            
            === JOB TO EVALUATE ===
            Title:    %s
            Company:  %s
            Location: %s
            
            Full Job Description:
            ---
            %s
            ---
            
            === SCORING INSTRUCTIONS ===
            
            STEP 1 — AUTO-DISQUALIFY CHECK (return matchScore=0 immediately if ANY is true):
              □ Role is primarily Frontend / Mobile / DevOps / Data Engineering / QA
              □ Primary language required is Golang, Rust, or C++ (not Java)
              □ Requires < 5 years OR > 15 years experience
              □ Title contains: Junior, Associate, Fresher, Trainee, Intern
              □ Role is 100%% cloud-native (requires AWS/GCP/Azure certifications)
            
            STEP 2 — SCORE EACH DIMENSION (only if not disqualified):
            
              A. Core Stack Match (0–40 pts):
                 40 = Java + Spring Boot + Kafka all required → perfect fit
                 30 = Java + Spring Boot required, Kafka optional
                 20 = Java required, Spring framework mentioned
                 10 = JVM / backend required but framework-agnostic
                  0 = Non-Java primary language required
            
              B. Experience Level Fit (0–20 pts):
                 20 = Requires 7–10 years → ideal band for 8.8 years
                 15 = Requires 6–12 years → good fit
                 10 = Requires 5–8 years OR 8–14 years → acceptable
                  5 = Requires 4–6 years (slightly junior ask)
                  0 = Requires < 4 or > 15 years
            
              C. Architecture & Advanced Skills (0–20 pts):
                 +5 = Microservices / distributed systems explicitly required
                 +5 = Event-driven / Kafka / messaging explicitly required
                 +4 = Spring AI / AI integration / RAG / LLM mentioned
                 +3 = Redis / caching / performance optimization mentioned
                 +3 = CQRS / Saga / DDD / system design mentioned
                 (cap at 20)
            
              D. Title / Seniority Match (0–10 pts):
                 10 = Senior Engineer / Lead / Staff / Principal / Architect
                  5 = "Engineer" without seniority qualifier
                  0 = Junior / Associate / Manager / Director
            
              E. Location & CTC Fit (0–10 pts):
                 +5 = Bangalore / Bengaluru or Remote India
                 +3 = India (other city, relocatable)
                 +5 = Salary range includes or exceeds 35 LPA (or not mentioned)
                 +2 = Salary range 28–35 LPA (negotiable)
                  0 = Salary clearly below 28 LPA
            
            STEP 3 — PENALTY DEDUCTIONS:
              -10 = Requires Kubernetes/Istio as HARD requirement
              -10 = Requires AWS/GCP/Azure certifications as HARD requirement
              -10 = Requires Golang or Rust as HARD requirement
               -5 = Requires significant frontend work (React/Angular)
               -5 = More than 3 "nice to have" skills candidate completely lacks
            
            FINAL SCORE = A + B + C + D + E − Penalties (minimum 0, maximum 100)
            
            PRIORITY based on final score:
              HIGH   = 80–100 (apply immediately, tailored resume)
              MEDIUM = 65–79  (apply with standard tailoring)
              LOW    = 50–64  (apply only if interested)
              SKIP   = 0–49   (do not apply)
            
            === OUTPUT FORMAT ===
            Return ONLY valid JSON, no markdown, no explanation:
            {
              "matchScore": <integer 0-100>,
              "matchedSkills": ["skill1", "skill2", ...],
              "missingSkills": ["skill1", "skill2", ...],
              "priority": "<HIGH|MEDIUM|LOW|SKIP>",
              "reasoning": "<3-4 sentences explaining the score using the dimensions above>",
              "suggestedTitle": "<exact title from the JD that best fits candidate>",
              "estimatedCtcRange": "<expected CTC range in LPA for this role>",
              "keywordsToHighlight": ["keyword1", "keyword2", ...],
              "recruiterTips": "<one specific, actionable tip to improve this application>",
              "disqualified": <true|false>,
              "disqualifyReason": "<reason if disqualified, empty string otherwise>"
            }
            """.formatted(
                PROFILE,
                job.getTitle(),
                job.getCompany(),
                job.getLocation() != null ? job.getLocation() : "Not specified",
                job.getJobDescription().substring(0, Math.min(3000, job.getJobDescription().length()))
            );
    }

    // ── Main analyze method ───────────────────────────────────────────────────

    @CircuitBreaker(name = "ai-agent", fallbackMethod = "analyzeWithFallback")
    @Retry(name = "ai-agent")
    public JobMatchResult analyze(Job job) {
        log.info("Analyzing: '{}' at '{}' (JD length: {} chars)",
            job.getTitle(), job.getCompany(),
            job.getJobDescription() != null ? job.getJobDescription().length() : 0);

        // Fast pre-filter — skip obviously wrong roles before calling AI
        JobMatchResult preFilter = preFilterCheck(job);
        if (preFilter != null) {
            log.info("Pre-filter skip: {} — {}", job.getTitle(), preFilter.getDisqualifyReason());
            return preFilter;
        }

        try {
            String raw = chatClient.prompt()
                .user(buildPrompt(job))
                .call()
                .content();

            // Strip markdown code fences if model wraps response
            String json = raw
                .replaceAll("(?s)```json\\s*", "")
                .replaceAll("```", "")
                .trim();

            // Find the JSON object bounds in case model adds preamble text
            int start = json.indexOf('{');
            int end   = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }

            JobMatchResult result = objectMapper.readValue(json, JobMatchResult.class);

            log.info("Score: {}% | Priority: {} | Title: '{}' at '{}'",
                result.getMatchScore(), result.getPriority(),
                job.getTitle(), job.getCompany());

            return result;

        } catch (Exception e) {
            log.error("AI analysis failed for job '{}': {}", job.getTitle(), e.getMessage());
            throw new RuntimeException("AI analysis failed", e);
        }
    }

    // ── Pre-filter: instant skip without calling AI ───────────────────────────

    private JobMatchResult preFilterCheck(Job job) {
        String titleLower = job.getTitle() != null ? job.getTitle().toLowerCase() : "";
        String jdLower    = job.getJobDescription() != null
            ? job.getJobDescription().toLowerCase() : "";

        // Disqualified role types
        String[] badTitleKeywords = {
            "frontend", "front-end", "react developer", "angular developer",
            "vue developer", "ios developer", "android developer",
            "mobile developer", "devops engineer", "data engineer",
            "machine learning engineer", "ml engineer", "data scientist",
            "qa engineer", "test engineer", "junior", "fresher", "trainee",
            "intern ", "associate engineer"
        };
        for (String kw : badTitleKeywords) {
            if (titleLower.contains(kw)) {
                return disqualified("Role type mismatch: title contains '" + kw + "'");
            }
        }

        // Must be Java / JVM role
        boolean hasJava = jdLower.contains("java") || jdLower.contains("spring") || jdLower.contains("jvm");
        boolean primaryGolang = (jdLower.contains("golang") || jdLower.contains("go developer"))
            && !hasJava;
        boolean primaryRust = jdLower.contains("rust developer") && !hasJava;

        if (primaryGolang) return disqualified("Primary language is Golang, not Java");
        if (primaryRust)   return disqualified("Primary language is Rust, not Java");

        return null; // passes pre-filter — proceed to AI
    }

    private JobMatchResult disqualified(String reason) {
        JobMatchResult r = new JobMatchResult();
        r.setMatchScore(0);
        r.setPriority("SKIP");
        r.setDisqualified(true);
        r.setDisqualifyReason(reason);
        r.setReasoning(reason);
        r.setMatchedSkills(List.of());
        r.setMissingSkills(List.of());
        r.setKeywordsToHighlight(List.of());
        r.setRecruiterTips("");
        return r;
    }

    // ── Fallback when Ollama is down ─────────────────────────────────────────

    public JobMatchResult analyzeWithFallback(Job job, Exception ex) {
        log.warn("AI circuit open for '{}' — using fallback score. Error: {}",
            job.getTitle(), ex.getMessage());
        JobMatchResult r = new JobMatchResult();
        r.setMatchScore(55);
        r.setPriority("LOW");
        r.setDisqualified(false);
        r.setDisqualifyReason("");
        r.setReasoning("AI analysis unavailable (Ollama offline) — manual review required");
        r.setMatchedSkills(List.of("Java", "Spring Boot"));
        r.setMissingSkills(List.of());
        r.setKeywordsToHighlight(List.of("Java", "Kafka", "Microservices", "Spring Boot"));
        r.setRecruiterTips("Review manually — AI scoring temporarily unavailable");
        return r;
    }
}
