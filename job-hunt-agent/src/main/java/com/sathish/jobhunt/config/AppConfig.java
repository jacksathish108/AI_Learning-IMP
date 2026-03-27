package com.sathish.jobhunt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app")
@Data
public class AppConfig {

    private Candidate candidate = new Candidate();
    private JobSearch jobSearch = new JobSearch();
    private Scraper scraper = new Scraper();
    private Playwright playwright = new Playwright();
    private KafkaTopics kafka = new KafkaTopics();
    private Pdf pdf = new Pdf();

    @Data
    public static class Candidate {
        private String name;
        private String email;
        private String phone;
        private String location;
        private String currentCtc;
        private String expectedCtc;
        private String noticePeriod;
        private String linkedin;
        private String github;
        private String resumePath;
    }

    @Data
    public static class JobSearch {
        private List<String> keywords;
        private List<String> locations;
        private int minMatchScore = 65;
        private int maxApplicationsPerDay = 20;
        private boolean humanReviewRequired = true;
    }

    @Data
    public static class Scraper {
        private ScraperSource naukri = new ScraperSource();
        private ScraperSource linkedin = new ScraperSource();
        private ScraperSource indeed = new ScraperSource();
        private ScraperSource instahyre = new ScraperSource();
        private int scrapeIntervalHours = 6;

        @Data
        public static class ScraperSource {
            private String baseUrl;
            private boolean enabled = true;
        }
    }

    @Data
    public static class Playwright {
        private boolean headless = true;
        private String linkedinEmail;
        private String linkedinPassword;
        private String naukriEmail;
        private String naukriPassword;
    }

    @Data
    public static class KafkaTopics {
        private String jobDiscovered = "job.discovered";
        private String jobAnalyzed = "job.analyzed";
        private String resumeTailored = "resume.tailored";
        private String applicationSubmitted = "application.submitted";
        private String followUpRequired = "followup.required";
    }

    @Data
    public static class Pdf {
        private String outputDir = "/tmp/resumes";
    }
}
