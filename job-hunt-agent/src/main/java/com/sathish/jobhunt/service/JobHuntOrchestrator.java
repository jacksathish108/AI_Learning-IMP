package com.sathish.jobhunt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sathish.jobhunt.agent.*;
import com.sathish.jobhunt.config.AppConfig;
import com.sathish.jobhunt.model.Job;
import com.sathish.jobhunt.model.JobMatchResult;
import com.sathish.jobhunt.model.KafkaEvents;
import com.sathish.jobhunt.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Orchestrator — all @KafkaListener methods accept String and deserialize
 * manually with Jackson. This avoids the "Only String, Bytes, or byte[]
 * supported" error from JsonMessageConverter type mismatches.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobHuntOrchestrator {

    private final JdAnalyzerAgent jdAnalyzerAgent;
    private final ResumeTailorAgent resumeTailorAgent;
    private final AutoApplyAgent autoApplyAgent;
    private final PdfGeneratorService pdfGeneratorService;
    private final EmailService emailService;
    private final JobRepository jobRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;

    // ─── Step 1: job.discovered → analyze ────────────────────────────────────

    @KafkaListener(topics = "job.discovered", groupId = "analyzer-group")
    @Transactional
    public void onJobDiscovered(String message) {
        try {
            KafkaEvents.JobDiscoveredEvent event =
                objectMapper.readValue(message, KafkaEvents.JobDiscoveredEvent.class);

            log.info("EVENT job.discovered: {} at {}", event.getTitle(), event.getCompany());

            Job job = jobRepository.findById(event.getJobId()).orElse(null);
            if (job == null) { log.warn("Job not found: {}", event.getJobId()); return; }
            if (job.getJobDescription() == null || job.getJobDescription().isBlank()) {
                log.warn("Empty JD, skipping: {}", job.getId()); return;
            }

            JobMatchResult matchResult = jdAnalyzerAgent.analyze(job);

            // Hard disqualify — wrong role type, wrong language, etc.
            if (matchResult.isDisqualified()) {
                job.setStatus(Job.ApplicationStatus.SCORE_TOO_LOW);
                job.setMatchScore(0);
                job.setAiAnalysisSummary("DISQUALIFIED: " + matchResult.getDisqualifyReason());
                jobRepository.save(job);
                log.info("DISQUALIFIED: {} at {} — {}",
                    job.getTitle(), job.getCompany(), matchResult.getDisqualifyReason());
                return;
            }

            job.setMatchScore(matchResult.getMatchScore());
            job.setMatchedSkills(matchResult.getMatchedSkills() != null
                ? String.join(", ", matchResult.getMatchedSkills()) : "");
            job.setMissingSkills(matchResult.getMissingSkills() != null
                ? String.join(", ", matchResult.getMissingSkills()) : "");
            job.setAiAnalysisSummary(matchResult.getReasoning());

            if (matchResult.getMatchScore() < appConfig.getJobSearch().getMinMatchScore()
                    || "SKIP".equals(matchResult.getPriority())) {
                job.setStatus(Job.ApplicationStatus.SCORE_TOO_LOW);
                log.info("Score too low ({}%, {}) — skipping: {} at {}",
                    matchResult.getMatchScore(), matchResult.getPriority(),
                    job.getTitle(), job.getCompany());
            } else {
                job.setStatus(Job.ApplicationStatus.ANALYZED);
                kafkaTemplate.send(appConfig.getKafka().getJobAnalyzed(), job.getId(),
                    objectMapper.writeValueAsString(
                        KafkaEvents.JobAnalyzedEvent.builder()
                            .jobId(job.getId())
                            .matchScore(matchResult.getMatchScore())
                            .priority(matchResult.getPriority())
                            .shouldApply(true)
                            .timestamp(LocalDateTime.now())
                            .build()));
            }
            jobRepository.save(job);

        } catch (Exception e) {
            log.error("onJobDiscovered failed: {}", e.getMessage(), e);
        }
    }

    // ─── Step 2: job.analyzed → tailor resume ────────────────────────────────

    @KafkaListener(topics = "job.analyzed", groupId = "tailor-group")
    @Transactional
    public void onJobAnalyzed(String message) {
        try {
            KafkaEvents.JobAnalyzedEvent event =
                objectMapper.readValue(message, KafkaEvents.JobAnalyzedEvent.class);

            log.info("EVENT job.analyzed: {} (score={})", event.getJobId(), event.getMatchScore());
            if (!event.isShouldApply()) return;

            Job job = jobRepository.findById(event.getJobId()).orElse(null);
            if (job == null) return;

            long todayCount = jobRepository.countApplicationsSince(
                LocalDateTime.now().toLocalDate().atStartOfDay());
            if (todayCount >= appConfig.getJobSearch().getMaxApplicationsPerDay()) {
                log.warn("Daily limit reached — queuing for tomorrow"); return;
            }

            JobMatchResult matchResult = buildMatchResult(job, event);
            String tailoredResume = resumeTailorAgent.tailorResume(job, matchResult);
            String coverLetter    = resumeTailorAgent.generateCoverLetter(job, matchResult);

            String resumePath = pdfGeneratorService.generateResumePdf(job, tailoredResume);
            String coverPath  = pdfGeneratorService.generateCoverLetterPdf(job, coverLetter);

            job.setTailoredResumePath(resumePath);
            job.setCoverLetterPath(coverPath);

            if (appConfig.getJobSearch().isHumanReviewRequired()) {
                job.setStatus(Job.ApplicationStatus.PENDING_REVIEW);
                log.info("PENDING REVIEW: {} at {} ({}%)",
                    job.getTitle(), job.getCompany(), job.getMatchScore());
                emailService.notifyHumanReviewRequired(job, resumePath);
            } else {
                job.setStatus(Job.ApplicationStatus.RESUME_TAILORED);
                kafkaTemplate.send(appConfig.getKafka().getResumeTailored(), job.getId(),
                    objectMapper.writeValueAsString(
                        KafkaEvents.ResumeTailoredEvent.builder()
                            .jobId(job.getId())
                            .resumePath(resumePath)
                            .coverLetterPath(coverPath)
                            .timestamp(LocalDateTime.now())
                            .build()));
            }
            jobRepository.save(job);

        } catch (Exception e) {
            log.error("onJobAnalyzed failed: {}", e.getMessage(), e);
        }
    }

    // ─── Step 3: resume.tailored → apply ─────────────────────────────────────

    @KafkaListener(topics = "resume.tailored", groupId = "apply-group")
    @Transactional
    public void onResumeTailored(String message) {
        try {
            KafkaEvents.ResumeTailoredEvent event =
                objectMapper.readValue(message, KafkaEvents.ResumeTailoredEvent.class);

            log.info("EVENT resume.tailored: {}", event.getJobId());

            Job job = jobRepository.findById(event.getJobId()).orElse(null);
            if (job == null) return;

            job.setStatus(Job.ApplicationStatus.APPLYING);
            jobRepository.save(job);

            boolean applied = autoApplyAgent.apply(job, event.getResumePath());
            if (!applied) {
                applied = emailService.applyByEmail(job, event.getResumePath(), event.getCoverLetterPath());
            }

            if (applied) {
                job.setStatus(Job.ApplicationStatus.APPLIED);
                job.setAppliedAt(LocalDateTime.now());
                kafkaTemplate.send(appConfig.getKafka().getApplicationSubmitted(), job.getId(),
                    objectMapper.writeValueAsString(
                        KafkaEvents.ApplicationSubmittedEvent.builder()
                            .jobId(job.getId())
                            .company(job.getCompany())
                            .title(job.getTitle())
                            .source(job.getSource())
                            .appliedAt(LocalDateTime.now())
                            .build()));
                log.info("Applied: {} at {}", job.getTitle(), job.getCompany());
            } else {
                job.setStatus(Job.ApplicationStatus.FAILED);
                job.setNotes("Auto-apply failed — manual application required");
            }
            jobRepository.save(job);

        } catch (Exception e) {
            log.error("onResumeTailored failed: {}", e.getMessage(), e);
        }
    }

    // ─── Step 4: application.submitted → confirm ─────────────────────────────

    @KafkaListener(topics = "application.submitted", groupId = "tracker-group")
    public void onApplicationSubmitted(String message) {
        try {
            KafkaEvents.ApplicationSubmittedEvent event =
                objectMapper.readValue(message, KafkaEvents.ApplicationSubmittedEvent.class);
            log.info("EVENT application.submitted: {} at {}", event.getTitle(), event.getCompany());
            emailService.sendApplicationConfirmation(event);
        } catch (Exception e) {
            log.error("onApplicationSubmitted failed: {}", e.getMessage());
        }
    }

    // ─── Manual approve (from dashboard) ─────────────────────────────────────

    @Transactional
    public void approvePendingJob(String jobId) throws Exception {
        Job job = jobRepository.findById(jobId)
            .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));

        if (job.getStatus() != Job.ApplicationStatus.PENDING_REVIEW) {
            throw new IllegalStateException("Job is not in PENDING_REVIEW state");
        }

        log.info("Human approved: {} at {}", job.getTitle(), job.getCompany());

        kafkaTemplate.send(appConfig.getKafka().getResumeTailored(), job.getId(),
            objectMapper.writeValueAsString(
                KafkaEvents.ResumeTailoredEvent.builder()
                    .jobId(job.getId())
                    .resumePath(job.getTailoredResumePath())
                    .coverLetterPath(job.getCoverLetterPath())
                    .timestamp(LocalDateTime.now())
                    .build()));

        job.setStatus(Job.ApplicationStatus.RESUME_TAILORED);
        jobRepository.save(job);
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private JobMatchResult buildMatchResult(Job job, KafkaEvents.JobAnalyzedEvent event) {
        JobMatchResult r = new JobMatchResult();
        r.setMatchScore(event.getMatchScore());
        r.setPriority(event.getPriority());
        r.setReasoning(job.getAiAnalysisSummary());
        r.setMatchedSkills(job.getMatchedSkills() != null
            ? List.of(job.getMatchedSkills().split(", ")) : List.of());
        r.setMissingSkills(job.getMissingSkills() != null
            ? List.of(job.getMissingSkills().split(", ")) : List.of());
        r.setKeywordsToHighlight(r.getMatchedSkills());
        return r;
    }
}
