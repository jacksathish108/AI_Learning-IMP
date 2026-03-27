package com.sathish.jobhunt.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "jobs", indexes = {
    @Index(name = "idx_status", columnList = "status"),
    @Index(name = "idx_source", columnList = "source"),
    @Index(name = "idx_match_score", columnList = "matchScore")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String company;

    private String location;

    @Column(length = 50)
    private String source;       // LINKEDIN, NAUKRI, INDEED, INSTAHYRE

    @Column(unique = true)
    private String jobUrl;

    @Column(columnDefinition = "TEXT")
    private String jobDescription;

    // AI Analysis results
    private Integer matchScore;  // 0-100

    @Column(columnDefinition = "TEXT")
    private String matchedSkills;  // JSON array

    @Column(columnDefinition = "TEXT")
    private String missingSkills;  // JSON array

    @Column(columnDefinition = "TEXT")
    private String aiAnalysisSummary;

    // Tailored resume
    @Column(columnDefinition = "TEXT")
    private String tailoredResumePath;

    @Column(columnDefinition = "TEXT")
    private String coverLetterPath;

    // Application tracking
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private ApplicationStatus status = ApplicationStatus.DISCOVERED;

    private LocalDateTime appliedAt;
    private String applicationReference;
    private String recruiterEmail;
    private String recruiterName;
    private String salaryRange;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @CreationTimestamp
    private LocalDateTime discoveredAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum ApplicationStatus {
        DISCOVERED,
        ANALYZED,
        SCORE_TOO_LOW,
        RESUME_TAILORED,
        PENDING_REVIEW,    // Waiting for human approval
        APPLYING,
        APPLIED,
        REJECTED,
        SHORTLISTED,
        INTERVIEW_SCHEDULED,
        OFFER_RECEIVED,
        FAILED
    }
}
