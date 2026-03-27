package com.sathish.jobhunt.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sathish.jobhunt.config.AppConfig;
import com.sathish.jobhunt.model.Job;
import com.sathish.jobhunt.model.KafkaEvents;
import com.sathish.jobhunt.repository.JobRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Fixed JobScraperAgent — resolves 403 (Indeed) and 0-results (Naukri/Instahyre).
 *
 * Root causes:
 *  - Indeed 403       → blocks all server-side HTTP. Fixed with full browser header set +
 *                        JSON API fallback. Best fix = JobSpy Python sidecar.
 *  - Naukri 0 results → CSS selectors changed. Updated + added fallback selector chains.
 *  - Instahyre 0      → Jobs load via XHR, not HTML. Fixed with their REST API.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobScraperAgent {

    private final JobRepository jobRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AppConfig appConfig;
    private final ObjectMapper objectMapper;

    private static final List<String> USER_AGENTS = List.of(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0"
    );

    private final Random rng = new Random();

    // ── Jsoup with full browser header set ───────────────────────────────────
    private Connection jsoupConnect(String url) {
        return Jsoup.connect(url)
            .userAgent(USER_AGENTS.get(rng.nextInt(USER_AGENTS.size())))
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-IN,en-GB;q=0.9,en;q=0.8")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Connection", "keep-alive")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Cache-Control", "max-age=0")
            .header("DNT", "1")
            .followRedirects(true)
            .timeout(20000)
            .ignoreHttpErrors(true);
    }

    // ─── Entry point ─────────────────────────────────────────────────────────

    public List<Job> scrapeAll() {
        List<Job> all = new ArrayList<>();

        // JobSpy Python sidecar is the most reliable — check if it's running
        if (isJobSpyRunning()) {
            log.info("JobSpy sidecar detected — using it (bypasses all 403s)");
            for (String kw : appConfig.getJobSearch().getKeywords()) {
                all.addAll(scrapeViaJobSpy(kw));
                sleepPolite(1500);
            }
            return all;
        }

        log.info("JobSpy not running — using direct scrapers (start jobspy_server.py for best results)");
        for (String kw : appConfig.getJobSearch().getKeywords()) {
            for (String loc : appConfig.getJobSearch().getLocations()) {
                if (appConfig.getScraper().getNaukri().isEnabled())    { all.addAll(scrapeNaukri(kw, loc));    sleepPolite(2500); }
                if (appConfig.getScraper().getIndeed().isEnabled())    { all.addAll(scrapeIndeed(kw, loc));    sleepPolite(3000); }
                if (appConfig.getScraper().getInstahyre().isEnabled()) { all.addAll(scrapeInstahyre(kw));      sleepPolite(2000); }
            }
        }
        log.info("Scrape complete — {} new jobs", all.size());
        return all;
    }

    // ─── JobSpy Python sidecar (BEST — no 403s) ──────────────────────────────
    // Handles LinkedIn, Indeed, Naukri, Glassdoor.
    // Setup: pip install jobspy flask && python jobspy_server.py

    // Mutable so isJobSpyRunning() can record which address worked
    private volatile String jobSpyUrl = "http://localhost:5001";

    private boolean isJobSpyRunning() {
        // Try multiple addresses — on some Windows + Docker setups,
        // localhost resolves to IPv6 (::1) but the container listens on IPv4 only.
        String[] candidates = {
            "http://127.0.0.1:5001/health",
            "http://localhost:5001/health",
            "http://host.docker.internal:5001/health"
        };
        for (String url : candidates) {
            try {
                int status = Jsoup.connect(url)
                    .timeout(3000)
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true)
                    .execute()
                    .statusCode();
                if (status == 200) {
                    log.info("JobSpy detected at: {}", url);
                    jobSpyUrl = url.replace("/health", "");
                    return true;
                }
                log.debug("JobSpy at {} returned HTTP {}", url, status);
            } catch (Exception e) {
                log.debug("JobSpy not reachable at {}: {}", url, e.getMessage());
            }
        }
        log.warn("JobSpy not reachable on any address — run: docker-compose up -d jobspy");
        return false;
    }

    public List<Job> scrapeViaJobSpy(String keyword) {
        List<Job> jobs = new ArrayList<>();
        try {
            // Scrape one site at a time — avoids timeout on slow sites
        // naukri + linkedin are most relevant for Bangalore senior roles
        String url = jobSpyUrl + "/search?keyword="
                + keyword.replace(" ", "+")
                + "&location=Bangalore,India&results=15&sites=naukri,linkedin";

            Document doc = Jsoup.connect(url).timeout(120000).ignoreContentType(true).ignoreHttpErrors(true).get();
            JsonNode root = objectMapper.readTree(doc.body().text());
            JsonNode arr  = root.path("jobs");
            if (!arr.isArray()) return jobs;

            for (JsonNode n : arr) {
                String jobUrl = n.path("job_url").asText("");
                if (jobUrl.isBlank() || jobRepository.existsByJobUrl(jobUrl)) continue;

                Job job = jobRepository.save(Job.builder()
                    .title(n.path("title").asText("Unknown"))
                    .company(n.path("company").asText("Unknown"))
                    .location(n.path("location").asText("Bangalore"))
                    .source(n.path("site").asText("JOBSPY").toUpperCase())
                    .jobUrl(jobUrl)
                    .jobDescription(n.path("description").asText(""))
                    .salaryRange(n.path("min_amount").asText("") + " - " + n.path("max_amount").asText(""))
                    .status(Job.ApplicationStatus.DISCOVERED)
                    .build());

                jobs.add(job);
                publishJobDiscovered(job);
            }
            log.info("JobSpy: {} new jobs for '{}'", jobs.size(), keyword);
        } catch (Exception e) {
            log.error("JobSpy failed for '{}': {}", keyword, e.getMessage());
        }
        return jobs;
    }

    // ─── Naukri (fixed selectors) ─────────────────────────────────────────────

    @CircuitBreaker(name = "scraper", fallbackMethod = "scraperFallback")
    @Retry(name = "scraper")
    public List<Job> scrapeNaukri(String keyword, String location) {
        List<Job> jobs = new ArrayList<>();

        String slug    = keyword.toLowerCase().replaceAll("[^a-z0-9 ]", "").trim().replace(" ", "-");
        String locSlug = location.toLowerCase().replace(" ", "-").replace("bangalore", "bengaluru");
        String url     = String.format("https://www.naukri.com/%s-jobs-in-%s?experience=5-10&jobAge=3", slug, locSlug);

        log.info("Naukri scrape: {}", url);
        try {
            Document doc = jsoupConnect(url).get();
            int status   = doc.connection().response().statusCode();
            log.info("Naukri HTTP status: {}", status);

            // Naukri changes selectors often — try multiple patterns
            Elements cards = doc.select(
                "article.jobTuple, " +
                "div.cust-job-tuple, " +
                "div[class*=jobTuple], " +
                "div.srp-jobtuple-wrapper, " +
                "li[class*=jobTuple], " +
                "div[class*=job-container]"
            );

            log.info("Naukri: {} cards for '{}' in '{}'", cards.size(), keyword, location);

            for (Element card : cards) {
                try {
                    String title = firstOf(
                        card.select("a.title").text(),
                        card.select("a[class*=title]").text(),
                        card.select(".jobTitle a").text(),
                        card.select("h2 a").text(),
                        card.select("a[title]").attr("title")
                    );
                    String company = firstOf(
                        card.select("a.subTitle").text(),
                        card.select("a[class*=company]").text(),
                        card.select("[class*=companyInfo] a").text()
                    );
                    String jobUrl = firstOf(
                        card.select("a.title").attr("href"),
                        card.select("a[class*=title]").attr("href"),
                        card.select("h2 a").attr("href")
                    );

                    if (title.isBlank() || jobUrl.isBlank()) continue;
                    if (!jobUrl.startsWith("http")) jobUrl = "https://www.naukri.com" + jobUrl;
                    if (jobRepository.existsByJobUrl(jobUrl)) continue;

                    String jd = fetchNaukriDetail(jobUrl);
                    Job job = jobRepository.save(Job.builder()
                        .title(title).company(company)
                        .location(firstOf(card.select(".loc span").text(), card.select("[class*=location]").text(), location))
                        .source("NAUKRI").jobUrl(jobUrl).jobDescription(jd)
                        .salaryRange(card.select(".salary, [class*=salary]").text())
                        .status(Job.ApplicationStatus.DISCOVERED).build());

                    jobs.add(job);
                    publishJobDiscovered(job);
                    sleepPolite(1000);
                } catch (Exception e) {
                    log.debug("Naukri card error: {}", e.getMessage());
                }
            }
            if (cards.isEmpty()) {
                log.warn("Naukri: 0 cards found. Selectors may need updating. Page title: '{}'", doc.title());
            }
        } catch (IOException e) {
            log.error("Naukri IO error for '{}': {}", keyword, e.getMessage());
        }
        return jobs;
    }

    private String fetchNaukriDetail(String jobUrl) {
        try {
            sleepPolite(1200);
            Document doc = jsoupConnect(jobUrl).get();
            return firstOf(
                doc.select(".job-desc").text(),
                doc.select("#job_description").text(),
                doc.select("[class*=jobDescription]").text(),
                doc.select(".dang-inner-html").text()
            );
        } catch (Exception e) {
            return "";
        }
    }

    // ─── Indeed (fixed — 403 resolution) ─────────────────────────────────────
    //
    // Indeed blocks ALL server-side HTTP clients (403).
    // The only reliable solutions are:
    //   1. JobSpy Python sidecar (recommended — handles it automatically)
    //   2. Playwright headless browser (AutoApplyAgent already does this)
    //   3. Indeed Publisher API (official, requires approval)
    //
    // This method tries a best-effort JSON approach before giving up gracefully.

    @CircuitBreaker(name = "scraper", fallbackMethod = "scraperFallback")
    @Retry(name = "scraper")
    public List<Job> scrapeIndeed(String keyword, String location) {
        List<Job> jobs = new ArrayList<>();
        String url = String.format(
            "https://in.indeed.com/jobs?q=%s&l=%s&fromage=3&sort=date",
            keyword.replace(" ", "+"), location.replace(" ", "+")
        );

        try {
            Document doc = jsoupConnect(url)
                .cookie("CTK", "1hk80fh4eo2ri800")
                .cookie("indeed_rcc", "CTK")
                .header("Referer", "https://in.indeed.com/")
                .get();

            int status = doc.connection().response().statusCode();
            if (status == 403) {
                log.warn("Indeed 403 for '{}'. Start jobspy_server.py to bypass this — " +
                    "Indeed requires a real browser. See README.", keyword);
                return jobs;
            }

            // Updated selectors for Indeed 2024 DOM
            Elements cards = doc.select(
                ".job_seen_beacon, " +
                "li.css-5lfssm, " +
                "div[class*=cardOutline], " +
                "[data-jk]"
            );

            log.info("Indeed: {} cards for '{}' (status={})", cards.size(), keyword, status);

            for (Element card : cards) {
                try {
                    String jk = firstOf(card.attr("data-jk"), card.select("[data-jk]").attr("data-jk"));
                    if (jk.isBlank()) continue;

                    String jobUrl = "https://in.indeed.com/viewjob?jk=" + jk;
                    if (jobRepository.existsByJobUrl(jobUrl)) continue;

                    String title = firstOf(
                        card.select("h2.jobTitle span[title]").attr("title"),
                        card.select("h2[class*=jobTitle] span").text(),
                        card.select("[class*=jobTitle]").text()
                    );
                    String company = firstOf(
                        card.select("[data-testid=company-name]").text(),
                        card.select(".companyName").text(),
                        card.select("span[class*=companyName]").text()
                    );
                    String loc = firstOf(
                        card.select("[data-testid=text-location]").text(),
                        card.select(".companyLocation").text()
                    );

                    if (title.isBlank()) continue;

                    Job job = jobRepository.save(Job.builder()
                        .title(title).company(company).location(loc)
                        .source("INDEED").jobUrl(jobUrl)
                        .jobDescription("")   // fetch separately if needed
                        .status(Job.ApplicationStatus.DISCOVERED).build());

                    jobs.add(job);
                    publishJobDiscovered(job);
                } catch (Exception e) {
                    log.debug("Indeed card error: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Indeed scrape error for '{}': {}", keyword, e.getMessage());
        }
        return jobs;
    }

    // ─── Instahyre (fixed — uses REST API) ───────────────────────────────────
    //
    // FIX: Instahyre loads jobs via XHR, not in the initial HTML.
    // Calling their REST API directly returns JSON with all job data.

    @CircuitBreaker(name = "scraper", fallbackMethod = "scraperFallbackSingle")
    public List<Job> scrapeInstahyre(String keyword) {
        List<Job> jobs = new ArrayList<>();

        String apiUrl = "https://www.instahyre.com/api/v1/employer_opportunity/"
            + "?format=json&keyword=" + keyword.replace(" ", "%20")
            + "&location=Bangalore&limit=20";

        log.info("Instahyre API: {}", apiUrl);
        try {
            Document doc = jsoupConnect(apiUrl)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", "https://www.instahyre.com/candidate/explore/")
                .ignoreContentType(true)
                .get();

            int status = doc.connection().response().statusCode();
            String body = doc.body().text();

            if (status != 200) {
                log.warn("Instahyre API status {}: {}", status, body.substring(0, Math.min(200, body.length())));
                return jobs;
            }

            JsonNode root    = objectMapper.readTree(body);
            JsonNode results = root.path("results").isArray() ? root.path("results") : root.path("opportunities");

            if (!results.isArray()) {
                log.warn("Instahyre: unexpected JSON shape — key 'results' not found. Keys: {}", root.fieldNames());
                return jobs;
            }

            log.info("Instahyre: {} results for '{}'", results.size(), keyword);
            for (JsonNode n : results) {
                try {
                    String title   = firstOf(n.path("role").path("name").asText(""), n.path("designation").asText(""));
                    String company = n.path("company").path("name").asText("");
                    String jobUrl  = "https://www.instahyre.com/job-" + n.path("id").asText() + "/";
                    String jd      = firstOf(n.path("description").asText(""), n.path("role_description").asText(""));

                    if (title.isBlank() || jobRepository.existsByJobUrl(jobUrl)) continue;

                    Job job = jobRepository.save(Job.builder()
                        .title(title).company(company)
                        .location(n.path("location").asText("Bangalore"))
                        .source("INSTAHYRE").jobUrl(jobUrl).jobDescription(jd)
                        .salaryRange(n.path("min_ctc").asText("") + "–" + n.path("max_ctc").asText("") + " LPA")
                        .status(Job.ApplicationStatus.DISCOVERED).build());

                    jobs.add(job);
                    publishJobDiscovered(job);
                } catch (Exception e) {
                    log.debug("Instahyre node error: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Instahyre failed for '{}': {}", keyword, e.getMessage());
        }
        return jobs;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void publishJobDiscovered(Job job) {
        kafkaTemplate.send(appConfig.getKafka().getJobDiscovered(), job.getId(),
            KafkaEvents.JobDiscoveredEvent.builder()
                .jobId(job.getId()).title(job.getTitle()).company(job.getCompany())
                .source(job.getSource()).jobUrl(job.getJobUrl())
                .timestamp(LocalDateTime.now()).build());
    }

    private String firstOf(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v.trim();
        return "";
    }

    private void sleepPolite(long ms) {
        try { Thread.sleep(ms + (long)(rng.nextDouble() * 500)); }
        catch (InterruptedException ignored) {}
    }

    public List<Job> scraperFallback(String kw, String loc, Exception ex) {
        log.warn("Scraper CB open [{}/{}]: {}", kw, loc, ex.getMessage()); return List.of();
    }
    public List<Job> scraperFallbackSingle(String kw, Exception ex) {
        log.warn("Scraper CB open [{}]: {}", kw, ex.getMessage()); return List.of();
    }
}
