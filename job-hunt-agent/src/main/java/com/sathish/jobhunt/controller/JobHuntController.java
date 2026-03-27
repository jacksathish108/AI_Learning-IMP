package com.sathish.jobhunt.controller;

import com.sathish.jobhunt.agent.JdAnalyzerAgent;
import com.sathish.jobhunt.agent.JobScraperAgent;
import com.sathish.jobhunt.agent.ResumeTailorAgent;
import com.sathish.jobhunt.model.Job;
import com.sathish.jobhunt.model.JobMatchResult;
import com.sathish.jobhunt.repository.JobRepository;
import com.sathish.jobhunt.service.JobHuntOrchestrator;
import com.sathish.jobhunt.service.PdfGeneratorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * REST API for the Job Hunt Agent Dashboard.
 *
 * Base URL: http://localhost:8080/api
 *
 * Key endpoints:
 *   GET  /api/jobs                   → All jobs with status
 *   GET  /api/jobs/pending-review    → Jobs awaiting human approval
 *   POST /api/jobs/{id}/approve      → Approve and apply
 *   POST /api/jobs/{id}/skip         → Mark as skipped
 *   POST /api/trigger/scrape         → Manual scrape trigger
 *   POST /api/trigger/analyze/{id}   → Re-analyze a specific job
 *   POST /api/tailor                 → Tailor resume for a pasted JD (ad-hoc)
 *   GET  /api/stats                  → Dashboard summary stats
 *   GET  /api/jobs/{id}/resume       → Download tailored resume PDF
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class JobHuntController {

    private final JobRepository jobRepository;
    private final JobHuntOrchestrator orchestrator;
    private final JobScraperAgent scraperAgent;
    private final JdAnalyzerAgent jdAnalyzerAgent;
    private final ResumeTailorAgent resumeTailorAgent;
    private final PdfGeneratorService pdfGeneratorService;

    // ─── Job Listing ──────────────────────────────────────────────────────────

    @GetMapping("/jobs")
    public List<Job> getAllJobs(
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int minScore
    ) {
        if (status != null) {
            try {
                Job.ApplicationStatus s = Job.ApplicationStatus.valueOf(status.toUpperCase());
                return jobRepository.findByStatusOrderByMatchScoreDesc(s);
            } catch (IllegalArgumentException ignored) {}
        }
        if (minScore > 0) {
            return jobRepository.findByMatchScoreGreaterThanEqualAndStatusOrderByMatchScoreDesc(
                minScore, Job.ApplicationStatus.ANALYZED);
        }
        return jobRepository.findAll();
    }

    @GetMapping("/jobs/pending-review")
    public List<Job> getPendingReview() {
        return jobRepository.findByStatusOrderByMatchScoreDesc(Job.ApplicationStatus.PENDING_REVIEW);
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<Job> getJob(@PathVariable String id) {
        return jobRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    // ─── Approve / Skip ───────────────────────────────────────────────────────

    @PostMapping("/jobs/{id}/approve")
    public ResponseEntity<Map<String, String>> approveJob(@PathVariable String id) {
        try {
            orchestrator.approvePendingJob(id);
            return ResponseEntity.ok(Map.of(
                "status", "APPROVED",
                "message", "Job approved and queued for application",
                "jobId", id
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/jobs/{id}/skip")
    public ResponseEntity<Map<String, String>> skipJob(@PathVariable String id) {
        return jobRepository.findById(id)
            .map(job -> {
                job.setStatus(Job.ApplicationStatus.SCORE_TOO_LOW);
                job.setNotes("Manually skipped");
                jobRepository.save(job);
                return ResponseEntity.ok(Map.of("status", "SKIPPED", "jobId", id));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/jobs/{id}/recruiter")
    public ResponseEntity<Job> updateRecruiterInfo(
        @PathVariable String id,
        @RequestBody Map<String, String> body
    ) {
        return jobRepository.findById(id)
            .map(job -> {
                job.setRecruiterEmail(body.get("email"));
                job.setRecruiterName(body.get("name"));
                return ResponseEntity.ok(jobRepository.save(job));
            })
            .orElse(ResponseEntity.notFound().build());
    }

    // ─── Manual Triggers ──────────────────────────────────────────────────────

    @PostMapping("/trigger/scrape")
    public ResponseEntity<Map<String, Object>> triggerScrape() {
        log.info("Manual scrape triggered via API");
        try {
            List<Job> jobs = scraperAgent.scrapeAll();
            return ResponseEntity.ok(Map.of(
                "message", "Scrape completed",
                "discovered", jobs.size()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Scrape failed: " + e.getMessage()));
        }
    }

    @PostMapping("/trigger/analyze/{id}")
    public ResponseEntity<JobMatchResult> triggerAnalysis(@PathVariable String id) {
        return jobRepository.findById(id)
            .map(job -> {
                JobMatchResult result = jdAnalyzerAgent.analyze(job);
                job.setMatchScore(result.getMatchScore());
                job.setAiAnalysisSummary(result.getReasoning());
                job.setStatus(Job.ApplicationStatus.ANALYZED);
                jobRepository.save(job);
                return ResponseEntity.ok(result);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    // ─── Ad-hoc Resume Tailoring ──────────────────────────────────────────────

    /**
     * Paste any JD and get a tailored resume + match score instantly.
     * This is your "immediate use" endpoint while the agent runs in the background.
     *
     * POST /api/tailor
     * Body: { "title": "Senior BE", "company": "Flipkart", "location": "Bangalore", "jd": "..." }
     */
    @PostMapping("/tailor")
    public ResponseEntity<Map<String, Object>> tailorResume(@RequestBody Map<String, String> body) {
        String jdText = body.get("jd");
        if (jdText == null || jdText.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "jd field is required"));
        }

        // Build a temporary Job for analysis
        Job tempJob = Job.builder()
            .id("adhoc-" + System.currentTimeMillis())
            .title(body.getOrDefault("title", "Unknown Role"))
            .company(body.getOrDefault("company", "Unknown Company"))
            .location(body.getOrDefault("location", "Bangalore"))
            .jobDescription(jdText)
            .source("MANUAL")
            .build();

        try {
            // Analyze
            JobMatchResult match = jdAnalyzerAgent.analyze(tempJob);

            // Tailor
            String tailoredResume = resumeTailorAgent.tailorResume(tempJob, match);
            String coverLetter = resumeTailorAgent.generateCoverLetter(tempJob, match);

            // Generate PDF
            tempJob.setId("adhoc-" + System.currentTimeMillis());
            String pdfPath = pdfGeneratorService.generateResumePdf(tempJob, tailoredResume);

            return ResponseEntity.ok(Map.of(
                "matchScore", match.getMatchScore(),
                "priority", match.getPriority(),
                "matchedSkills", match.getMatchedSkills(),
                "missingSkills", match.getMissingSkills(),
                "reasoning", match.getReasoning(),
                "keywordsHighlighted", match.getKeywordsToHighlight(),
                "tailoredResume", tailoredResume,
                "coverLetter", coverLetter,
                "pdfPath", pdfPath
            ));

        } catch (Exception e) {
            log.error("Ad-hoc tailor failed: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getMessage()));
        }
    }

    // ─── Download PDF ─────────────────────────────────────────────────────────

    @GetMapping("/jobs/{id}/resume")
    public ResponseEntity<Resource> downloadResume(@PathVariable String id) {
        return jobRepository.findById(id)
            .filter(job -> job.getTailoredResumePath() != null)
            .map(job -> {
                File file = new File(job.getTailoredResumePath());
                if (!file.exists()) return ResponseEntity.notFound().<Resource>build();
                Resource resource = new FileSystemResource(file);
                return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.getName() + "\"")
                    .body(resource);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/jobs/{id}/cover-letter")
    public ResponseEntity<Resource> downloadCoverLetter(@PathVariable String id) {
        return jobRepository.findById(id)
            .filter(job -> job.getCoverLetterPath() != null)
            .map(job -> {
                File file = new File(job.getCoverLetterPath());
                if (!file.exists()) return ResponseEntity.notFound().<Resource>build();
                Resource resource = new FileSystemResource(file);
                return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.getName() + "\"")
                    .body(resource);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    // ─── Stats Dashboard ──────────────────────────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        List<Object[]> statusSummary = jobRepository.getStatusSummary();

        Map<String, Long> byStatus = new java.util.LinkedHashMap<>();
        long total = 0;
        for (Object[] row : statusSummary) {
            long count = ((Number) row[1]).longValue();
            byStatus.put(row[0].toString(), count);
            total += count;
        }

        long todayApplied = jobRepository.countApplicationsSince(
            java.time.LocalDateTime.now().toLocalDate().atStartOfDay()
        );

        return ResponseEntity.ok(Map.of(
            "totalJobs", total,
            "byStatus", byStatus,
            "appliedToday", todayApplied,
            "pendingReview", byStatus.getOrDefault("PENDING_REVIEW", 0L)
        ));
    }

    // ─── Health ───────────────────────────────────────────────────────────────

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "agent", "Job Hunt Agent — Sathishkumar K");
    }
}
