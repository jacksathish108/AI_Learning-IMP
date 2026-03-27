package com.sathish.jobhunt.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

public class KafkaEvents {

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class JobDiscoveredEvent {
        private String jobId;
        private String title;
        private String company;
        private String source;
        private String jobUrl;
        private LocalDateTime timestamp;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class JobAnalyzedEvent {
        private String jobId;
        private int matchScore;
        private String priority;
        private boolean shouldApply;
        private LocalDateTime timestamp;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ResumeTailoredEvent {
        private String jobId;
        private String resumePath;
        private String coverLetterPath;
        private LocalDateTime timestamp;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ApplicationSubmittedEvent {
        private String jobId;
        private String company;
        private String title;
        private String source;
        private String applicationRef;
        private LocalDateTime appliedAt;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class FollowUpRequiredEvent {
        private String jobId;
        private String company;
        private String recruiterEmail;
        private int daysSinceApplied;
        private LocalDateTime timestamp;
    }
}
