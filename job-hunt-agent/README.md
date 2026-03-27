# 🤖 Job Hunt AI Agent — Sathishkumar K
> Spring Boot + Spring AI + Kafka + Playwright — Fully automated job hunting pipeline

---

## Architecture

```
[Scraper Agent] ──job.discovered──▶ [JD Analyzer Agent (Llama3)]
                                              │
                                       job.analyzed
                                              │
                                    [Resume Tailor Agent (Llama3)]
                                              │
                                     (human review gate)
                                              │
                                    [Auto Apply Agent (Playwright)]
                                              │
                                    [Tracker + Follow-up Scheduler]
```

---

## Quick Start (5 steps)

### Step 1 — Start infrastructure
```bash
docker-compose up -d
```

### Step 2 — Pull Ollama models (one-time)
```bash
docker exec -it jobhunt-ollama ollama pull llama3
docker exec -it jobhunt-ollama ollama pull nomic-embed-text
```

### Step 3 — Set environment variables
```bash
export LINKEDIN_EMAIL="your-email@gmail.com"
export LINKEDIN_PASSWORD="your-password"
export NAUKRI_EMAIL="your-email@gmail.com"
export NAUKRI_PASSWORD="your-password"
export GMAIL_USER="your-email@gmail.com"
export GMAIL_APP_PASSWORD="your-app-password"   # Gmail App Password (not login password)
```

### Step 4 — Build and run
```bash
mvn clean install -DskipTests
mvn spring-boot:run
```

### Step 5 — Install Playwright browsers (one-time)
```bash
mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install chromium"
```

---

## Configuration

Edit `src/main/resources/application.yml`:

```yaml
app:
  job-search:
    min-match-score: 65        # Only apply if AI score >= 65%
    max-applications-per-day: 20
    human-review-required: true  # Set false for full automation
  candidate:
    expected-ctc: "38 LPA (Negotiable)"
    notice-period: "Immediate Joiner"
```

---

## API Reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/jobs` | All discovered jobs |
| GET | `/api/jobs?status=PENDING_REVIEW` | Jobs awaiting approval |
| GET | `/api/stats` | Dashboard summary |
| POST | `/api/trigger/scrape` | Manually trigger scrape |
| POST | `/api/jobs/{id}/approve` | Approve & apply to a job |
| POST | `/api/jobs/{id}/skip` | Skip a job |
| POST | `/api/tailor` | Tailor resume for any pasted JD |
| GET | `/api/jobs/{id}/resume` | Download tailored resume PDF |
| GET | `/api/jobs/{id}/cover-letter` | Download cover letter PDF |

### Ad-hoc Resume Tailor (most useful endpoint right now!)
```bash
curl -X POST http://localhost:8080/api/tailor \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Senior Backend Engineer",
    "company": "Flipkart",
    "location": "Bangalore",
    "jd": "We are looking for a Senior Backend Engineer with 5+ years Java, Spring Boot, Kafka..."
  }'
```

Returns: `matchScore`, `tailoredResume`, `coverLetter`, PDF path

---

## Monitoring

| Service | URL |
|---------|-----|
| App health | http://localhost:8080/api/health |
| Actuator | http://localhost:8080/actuator |
| Kafka UI | http://localhost:8090 |
| pgAdmin | http://localhost:5050 |

---

## Project Structure

```
src/main/java/com/sathish/jobhunt/
├── agent/
│   ├── JdAnalyzerAgent.java       # AI-powered JD analysis (Llama3)
│   ├── ResumeTailorAgent.java     # AI resume + cover letter writer
│   ├── JobScraperAgent.java       # Jsoup scraper (Naukri, Indeed, Instahyre)
│   └── AutoApplyAgent.java        # Playwright auto-apply (LinkedIn, Naukri)
├── service/
│   ├── JobHuntOrchestrator.java   # Kafka event wiring — main pipeline
│   ├── PdfGeneratorService.java   # iText PDF generation
│   └── EmailService.java          # JavaMail notifications + email apply
├── scheduler/
│   └── JobHuntScheduler.java      # @Scheduled — daily scrape + follow-ups
├── controller/
│   └── JobHuntController.java     # REST API + dashboard
├── model/
│   ├── Job.java                   # JPA entity
│   ├── JobMatchResult.java        # Spring AI response DTO
│   └── KafkaEvents.java           # Kafka event DTOs
├── repository/
│   └── JobRepository.java         # Spring Data JPA
├── config/
│   ├── AppConfig.java             # application.yml binding
│   ├── AiConfig.java              # Spring AI ChatClient bean
│   └── KafkaConfig.java           # Topic definitions
└── kafka/
    └── DlqHandler.java            # Dead Letter Queue handler
```

---

## Switching to OpenAI (optional)

Replace Ollama with OpenAI in `pom.xml`:
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
</dependency>
```

And in `application.yml`:
```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        model: gpt-4o
```

Update `AiConfig.java` to inject `OpenAiChatModel` instead of `OllamaChatModel`.

---

## Human Review Flow (recommended for first week)

1. Agent discovers job → analyzes → tailors resume
2. Emails you: **"[Job Agent] Review Required: Senior BE at Flipkart (Match: 87%)"**
3. You review the attached tailored PDF
4. Approve with: `POST /api/jobs/{id}/approve`
5. Agent applies automatically

Set `human-review-required: false` once you trust the agent.

---

## Tips for Sathish

- **Instahyre** is the best source for 35L+ Bangalore roles — keep it enabled
- The AI scores your profile vs JD — tune `min-match-score` based on hit rate
- Your RAG pipeline + Spring AI experience is a strong differentiator — the tailor agent leads with it
- LinkedIn Easy Apply works best with headless=false initially, to verify it's working
- Keep Ollama running locally — it's free and faster than API calls for bulk analysis
