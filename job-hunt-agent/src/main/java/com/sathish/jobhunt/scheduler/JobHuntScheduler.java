package com.sathish.jobhunt.scheduler;

import com.sathish.jobhunt.agent.JobScraperAgent;
import com.sathish.jobhunt.config.AppConfig;
import com.sathish.jobhunt.model.Job;
import com.sathish.jobhunt.model.KafkaEvents;
import com.sathish.jobhunt.repository.JobRepository;
import com.sathish.jobhunt.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled triggers for the job hunt pipeline.
 *
 * Schedule overview:
 *   - 09:00 daily  → scrape all sources + kick off analysis
 *   - 15:00 daily  → second scrape pass (afternoon postings)
 *   - 10:00 daily  → follow-up emails to recruiters (7-day rule)
 *   - Every 6 hrs  → re-queue any stuck ANALYZED jobs for tailoring
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobHuntScheduler {

    private final JobScraperAgent jobScraperAgent;
    private final JobRepository jobRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final EmailService emailService;
    private final AppConfig appConfig;

    // ─── Morning scrape: 09:00 every day ──────────────────────────────────────

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Kolkata")
    public void morningJobScrape() {
        log.info("=== MORNING JOB SCRAPE STARTED ===");
        runScrape();
    }

    // ─── Afternoon scrape: 15:00 every day ────────────────────────────────────

    @Scheduled(cron = "0 0 15 * * *", zone = "Asia/Kolkata")
    public void afternoonJobScrape() {
        log.info("=== AFTERNOON JOB SCRAPE STARTED ===");
        runScrape();
    }

    private void runScrape() {
        try {
            List<Job> discovered = jobScraperAgent.scrapeAll();
            log.info("Scrape completed — {} new jobs discovered", discovered.size());
        } catch (Exception e) {
            log.error("Scrape failed: {}", e.getMessage());
        }
    }

    // ─── Follow-up: 10:00 every day ───────────────────────────────────────────

    @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Kolkata")
    public void sendFollowUps() {
        log.info("Checking for follow-up candidates...");
        LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);

        List<Job> followUpJobs = jobRepository.findJobsRequiringFollowUp(sevenDaysAgo);
        log.info("Found {} jobs requiring follow-up", followUpJobs.size());

        for (Job job : followUpJobs) {
            try {
                emailService.sendFollowUp(job);

                // Publish follow-up event
                kafkaTemplate.send(appConfig.getKafka().getFollowUpRequired(), job.getId(),
                    KafkaEvents.FollowUpRequiredEvent.builder()
                        .jobId(job.getId())
                        .company(job.getCompany())
                        .recruiterEmail(job.getRecruiterEmail())
                        .daysSinceApplied(7)
                        .timestamp(LocalDateTime.now())
                        .build());

                // Update notes so we don't follow up again
                job.setNotes((job.getNotes() != null ? job.getNotes() + "\n" : "")
                    + "Follow-up sent: " + LocalDateTime.now());
                // Update recruiterEmail to null to prevent re-queuing
                job.setRecruiterEmail(null);
                jobRepository.save(job);

            } catch (Exception e) {
                log.error("Follow-up failed for job {}: {}", job.getId(), e.getMessage());
            }
        }
    }

    // ─── Retry stuck ANALYZED jobs every 6 hours ──────────────────────────────

    @Scheduled(cron = "0 0 */6 * * *", zone = "Asia/Kolkata")
    public void retryStuckJobs() {
        log.info("Checking for stuck ANALYZED jobs...");

        List<Job> stuckJobs = jobRepository.findByStatusOrderByMatchScoreDesc(
            Job.ApplicationStatus.ANALYZED
        );

        if (stuckJobs.isEmpty()) return;

        log.info("Re-queuing {} stuck ANALYZED jobs", stuckJobs.size());

        for (Job job : stuckJobs) {
            kafkaTemplate.send(appConfig.getKafka().getJobAnalyzed(), job.getId(),
                KafkaEvents.JobAnalyzedEvent.builder()
                    .jobId(job.getId())
                    .matchScore(job.getMatchScore() != null ? job.getMatchScore() : 65)
                    .priority("MEDIUM")
                    .shouldApply(true)
                    .timestamp(LocalDateTime.now())
                    .build());
        }
    }

    // ─── Daily stats log: 08:00 ───────────────────────────────────────────────

    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Kolkata")
    public void logDailyStats() {
        log.info("=== JOB HUNT DAILY STATS ===");
        List<Object[]> summary = jobRepository.getStatusSummary();
        summary.forEach(row ->
            log.info("  Status: {} | Count: {}", row[0], row[1])
        );

        long todayApplied = jobRepository.countApplicationsSince(
            LocalDateTime.now().toLocalDate().atStartOfDay()
        );
        log.info("  Applied today: {}", todayApplied);
        log.info("============================");
    }
}
