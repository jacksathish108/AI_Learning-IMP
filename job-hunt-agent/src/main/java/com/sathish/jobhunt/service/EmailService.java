package com.sathish.jobhunt.service;

import com.sathish.jobhunt.config.AppConfig;
import com.sathish.jobhunt.model.Job;
import com.sathish.jobhunt.model.KafkaEvents;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.File;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;
    private final AppConfig appConfig;

    /**
     * Send application email directly to recruiter (fallback if auto-apply fails).
     */
    public boolean applyByEmail(Job job, String resumePdfPath, String coverLetterPdfPath) {
        if (job.getRecruiterEmail() == null || job.getRecruiterEmail().isBlank()) {
            log.info("No recruiter email for job {} — skipping email apply", job.getId());
            return false;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(appConfig.getCandidate().getEmail());
            helper.setTo(job.getRecruiterEmail());
            helper.setSubject("Application: " + job.getTitle() + " — " + appConfig.getCandidate().getName()
                + " | " + appConfig.getCandidate().getNoticePeriod() + " | 8.8 Years Java/Spring/Kafka");

            helper.setText(buildEmailBody(job), true);

            if (resumePdfPath != null && new File(resumePdfPath).exists()) {
                helper.addAttachment("Sathishkumar_Resume.pdf", new File(resumePdfPath));
            }
            if (coverLetterPdfPath != null && new File(coverLetterPdfPath).exists()) {
                helper.addAttachment("Sathishkumar_CoverLetter.pdf", new File(coverLetterPdfPath));
            }

            mailSender.send(message);
            log.info("Email application sent to {} for job: {}", job.getRecruiterEmail(), job.getTitle());
            return true;

        } catch (MessagingException e) {
            log.error("Email send failed for job {}: {}", job.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Notify Sathish that a job requires human review before applying.
     */
    public void notifyHumanReviewRequired(Job job, String resumePdfPath) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(appConfig.getCandidate().getEmail());
            helper.setTo(appConfig.getCandidate().getEmail()); // notify self
            helper.setSubject(String.format("[Job Agent] Review Required: %s at %s (Match: %d%%)",
                job.getTitle(), job.getCompany(), job.getMatchScore()));

            String body = """
                <h2>🤖 Job Hunt Agent — Human Review Required</h2>
                <table border="1" cellpadding="6" cellspacing="0">
                  <tr><td><b>Role</b></td><td>%s</td></tr>
                  <tr><td><b>Company</b></td><td>%s</td></tr>
                  <tr><td><b>Match Score</b></td><td>%d%%</td></tr>
                  <tr><td><b>Source</b></td><td>%s</td></tr>
                  <tr><td><b>URL</b></td><td><a href="%s">View Job</a></td></tr>
                  <tr><td><b>AI Analysis</b></td><td>%s</td></tr>
                </table>
                <br/>
                <p>Tailored resume is attached. To approve, call:</p>
                <code>POST /api/jobs/%s/approve</code>
                <br/><br/>
                <p>Or log in to the dashboard at <a href="http://localhost:8080">http://localhost:8080</a></p>
                """.formatted(
                    job.getTitle(), job.getCompany(), job.getMatchScore(),
                    job.getSource(), job.getJobUrl(), job.getAiAnalysisSummary(),
                    job.getId()
                );

            helper.setText(body, true);

            if (resumePdfPath != null && new File(resumePdfPath).exists()) {
                helper.addAttachment("Tailored_Resume.pdf", new File(resumePdfPath));
            }

            mailSender.send(message);
            log.info("Review notification sent for: {} at {}", job.getTitle(), job.getCompany());

        } catch (MessagingException e) {
            log.error("Failed to send review notification: {}", e.getMessage());
        }
    }

    /**
     * Send application submitted confirmation to self.
     */
    public void sendApplicationConfirmation(KafkaEvents.ApplicationSubmittedEvent event) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");

            helper.setFrom(appConfig.getCandidate().getEmail());
            helper.setTo(appConfig.getCandidate().getEmail());
            helper.setSubject(String.format("✅ Applied: %s at %s", event.getTitle(), event.getCompany()));
            helper.setText(String.format(
                "Application submitted!\n\nRole: %s\nCompany: %s\nSource: %s\nTime: %s",
                event.getTitle(), event.getCompany(), event.getSource(), event.getAppliedAt()
            ), false);

            mailSender.send(message);

        } catch (MessagingException e) {
            log.warn("Confirmation email failed: {}", e.getMessage());
        }
    }

    /**
     * Send follow-up email to recruiter 7 days after applying.
     */
    public void sendFollowUp(Job job) {
        if (job.getRecruiterEmail() == null) return;

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");

            helper.setFrom(appConfig.getCandidate().getEmail());
            helper.setTo(job.getRecruiterEmail());
            helper.setSubject("Following up: " + job.getTitle() + " Application — Sathishkumar K");
            helper.setText(buildFollowUpBody(job), true);

            mailSender.send(message);
            log.info("Follow-up sent for job: {} at {}", job.getTitle(), job.getCompany());

        } catch (MessagingException e) {
            log.error("Follow-up failed for job {}: {}", job.getId(), e.getMessage());
        }
    }

    // ─── Email Templates ──────────────────────────────────────────────────────

    private String buildEmailBody(Job job) {
        AppConfig.Candidate c = appConfig.getCandidate();
        return """
            <p>Dear Hiring Team,</p>
            <p>I'm applying for the <strong>%s</strong> position at <strong>%s</strong>.</p>
            <p>I'm a Senior Backend Engineer with <strong>8.8+ years</strong> of experience building
            high-throughput distributed systems with Java, Spring Boot, Apache Kafka, and Spring AI.
            Recent highlights:</p>
            <ul>
              <li>Production RAG pipeline: Spring AI + Oracle Vector DB + Ollama (Llama3)</li>
              <li>35%% API latency reduction via Redis + Caffeine multi-layer caching</li>
              <li>5M+ Kafka events/day at 3,000–5,000 TPS with 99.9%% uptime</li>
            </ul>
            <p><strong>Immediate joiner</strong> | Expected CTC: 38 LPA (Negotiable)</p>
            <p>Please find my tailored resume attached.</p>
            <p>Best regards,<br/>
            <strong>%s</strong><br/>
            %s | %s<br/>
            %s</p>
            """.formatted(
                job.getTitle(), job.getCompany(),
                c.getName(), c.getPhone(), c.getEmail(), c.getLinkedin()
            );
    }

    private String buildFollowUpBody(Job job) {
        AppConfig.Candidate c = appConfig.getCandidate();
        return """
            <p>Hi %s,</p>
            <p>I applied for the <strong>%s</strong> role at %s last week and wanted to
            follow up on the status of my application.</p>
            <p>I remain very interested in this opportunity and am available for an interview
            at your earliest convenience. I'm an <strong>immediate joiner</strong>.</p>
            <p>Please let me know if you need any additional information.</p>
            <p>Best regards,<br/><strong>%s</strong><br/>%s | %s</p>
            """.formatted(
                job.getRecruiterName() != null ? job.getRecruiterName() : "there",
                job.getTitle(), job.getCompany(),
                c.getName(), c.getPhone(), c.getLinkedin()
            );
    }
}
