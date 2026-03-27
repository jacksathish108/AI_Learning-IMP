package com.sathish.jobhunt.agent;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.sathish.jobhunt.config.AppConfig;
import com.sathish.jobhunt.model.Job;
import io.github.resilience4j.retry.annotation.Retry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.Optional;

/**
 * Agent 4 — Auto Apply Agent (Playwright)
 *
 * Automates job applications on:
 *   - LinkedIn Easy Apply
 *   - Naukri Apply
 *   - Email apply (via JavaMail — see EmailService)
 *
 * NOTE: Human review gate is checked before this agent fires.
 *       Set app.job-search.human-review-required=false for full automation.
 */
@Slf4j
@Component
public class AutoApplyAgent {

    private final AppConfig appConfig;
    private Playwright playwright;
    private Browser browser;

    public AutoApplyAgent(AppConfig appConfig) {
        this.appConfig = appConfig;
        initBrowser();
    }

    private void initBrowser() {
        try {
            this.playwright = Playwright.create();
            this.browser = playwright.chromium().launch(
                new BrowserType.LaunchOptions()
                    .setHeadless(appConfig.getPlaywright().isHeadless())
                    .setSlowMo(150)  // Slow down for stability
            );
            log.info("Playwright browser initialized (headless={})",
                appConfig.getPlaywright().isHeadless());
        } catch (Exception e) {
            log.error("Failed to initialize Playwright browser: {}", e.getMessage());
        }
    }

    // ─── LinkedIn Easy Apply ──────────────────────────────────────────────────

    @Retry(name = "playwright")
    public boolean applyOnLinkedIn(Job job, String resumePdfPath) {
        log.info("Attempting LinkedIn Easy Apply for: {} at {}", job.getTitle(), job.getCompany());
        BrowserContext context = browser.newContext();
        Page page = context.newPage();

        try {
            // Login to LinkedIn
            if (!loginToLinkedIn(page)) {
                log.error("LinkedIn login failed");
                return false;
            }

            // Navigate to job
            page.navigate(job.getJobUrl());
            page.waitForLoadState(LoadState.NETWORKIDLE);

            // Check Easy Apply button
            Locator easyApplyBtn = page.locator("button.jobs-apply-button, button[aria-label*='Easy Apply']");
            if (easyApplyBtn.count() == 0) {
                log.info("No Easy Apply button for job: {}", job.getJobUrl());
                return false;
            }

            easyApplyBtn.first().click();
            page.waitForTimeout(2000);

            // Fill application steps
            fillLinkedInApplicationSteps(page, resumePdfPath);

            // Submit
            Locator submitBtn = page.locator("button[aria-label*='Submit application']");
            if (submitBtn.count() > 0) {
                submitBtn.click();
                page.waitForTimeout(3000);
                log.info("LinkedIn Easy Apply submitted for: {} at {}", job.getTitle(), job.getCompany());

                // Take screenshot as proof
                page.screenshot(new Page.ScreenshotOptions()
                    .setPath(Paths.get("/tmp/screenshots/" + job.getId() + "_applied.png")));

                return true;
            }

            log.warn("Submit button not found — application may require manual steps");
            return false;

        } catch (Exception e) {
            log.error("LinkedIn apply failed for job {}: {}", job.getId(), e.getMessage());
            return false;
        } finally {
            context.close();
        }
    }

    private boolean loginToLinkedIn(Page page) {
        try {
            page.navigate("https://www.linkedin.com/login");
            page.waitForLoadState(LoadState.NETWORKIDLE);

            // Check if already logged in
            if (page.url().contains("feed")) return true;

            page.fill("#username", appConfig.getPlaywright().getLinkedinEmail());
            page.fill("#password", appConfig.getPlaywright().getLinkedinPassword());
            page.click("button[type='submit']");
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForTimeout(3000);

            boolean loggedIn = page.url().contains("feed") || page.url().contains("mynetwork");
            log.info("LinkedIn login: {}", loggedIn ? "SUCCESS" : "FAILED");
            return loggedIn;

        } catch (Exception e) {
            log.error("LinkedIn login exception: {}", e.getMessage());
            return false;
        }
    }

    private void fillLinkedInApplicationSteps(Page page, String resumePdfPath) {
        // Upload resume if prompted
        Locator uploadBtn = page.locator("input[type='file']");
        if (uploadBtn.count() > 0) {
            uploadBtn.setInputFiles(Paths.get(resumePdfPath));
            page.waitForTimeout(2000);
        }

        // Handle multi-step modal
        for (int step = 0; step < 10; step++) {
            // Fill phone if asked
            Locator phoneField = page.locator("input[id*='phone'], input[aria-label*='phone']");
            if (phoneField.count() > 0 && phoneField.inputValue().isEmpty()) {
                phoneField.fill(appConfig.getCandidate().getPhone());
            }

            // Fill current CTC / expected CTC fields
            Locator currentCtcField = page.locator("input[aria-label*='current'], input[aria-label*='Current CTC']");
            if (currentCtcField.count() > 0 && currentCtcField.inputValue().isEmpty()) {
                currentCtcField.fill("3000000"); // 30 LPA in numeric
            }
            Locator expectedCtcField = page.locator("input[aria-label*='expected'], input[aria-label*='Expected CTC']");
            if (expectedCtcField.count() > 0 && expectedCtcField.inputValue().isEmpty()) {
                expectedCtcField.fill("3800000"); // 38 LPA
            }

            // Fill years of experience
            Locator expField = page.locator("input[aria-label*='experience'], input[id*='years']");
            if (expField.count() > 0 && expField.inputValue().isEmpty()) {
                expField.fill("8");
            }

            // Click Next / Review / Submit
            Locator nextBtn = page.locator("button[aria-label='Continue to next step'], button:has-text('Next')");
            Locator reviewBtn = page.locator("button[aria-label='Review your application']");
            Locator submitBtn = page.locator("button[aria-label*='Submit application']");

            if (submitBtn.count() > 0) break;
            if (reviewBtn.count() > 0) { reviewBtn.click(); page.waitForTimeout(1500); continue; }
            if (nextBtn.count() > 0) { nextBtn.click(); page.waitForTimeout(1500); }
            else break;
        }
    }

    // ─── Naukri Apply ─────────────────────────────────────────────────────────

    @Retry(name = "playwright")
    public boolean applyOnNaukri(Job job, String resumePdfPath) {
        log.info("Attempting Naukri apply for: {} at {}", job.getTitle(), job.getCompany());
        BrowserContext context = browser.newContext();
        Page page = context.newPage();

        try {
            if (!loginToNaukri(page)) return false;

            page.navigate(job.getJobUrl());
            page.waitForLoadState(LoadState.NETWORKIDLE);

            // Click Apply
            Locator applyBtn = page.locator("button#apply-button, a[id*='apply'], button:has-text('Apply')").first();
            if (applyBtn.count() == 0) return false;

            applyBtn.click();
            page.waitForTimeout(3000);

            // Naukri may show a quick-apply modal
            Locator quickApply = page.locator("button:has-text('Apply')").last();
            if (quickApply.count() > 0) {
                quickApply.click();
                page.waitForTimeout(2000);
            }

            log.info("Naukri application submitted for: {} at {}", job.getTitle(), job.getCompany());
            return true;

        } catch (Exception e) {
            log.error("Naukri apply failed for job {}: {}", job.getId(), e.getMessage());
            return false;
        } finally {
            context.close();
        }
    }

    private boolean loginToNaukri(Page page) {
        try {
            page.navigate("https://www.naukri.com/nlogin/login");
            page.waitForLoadState(LoadState.NETWORKIDLE);

            if (!page.url().contains("login")) return true; // Already logged in

            page.fill("input[placeholder*='Email']", appConfig.getPlaywright().getNaukriEmail());
            page.fill("input[placeholder*='Password']", appConfig.getPlaywright().getNaukriPassword());
            page.click("button[type='submit'], div.loginButton");
            page.waitForTimeout(4000);

            return !page.url().contains("login");
        } catch (Exception e) {
            log.error("Naukri login failed: {}", e.getMessage());
            return false;
        }
    }

    // ─── Route to correct platform ────────────────────────────────────────────

    public boolean apply(Job job, String resumePdfPath) {
        return switch (job.getSource()) {
            case "LINKEDIN" -> applyOnLinkedIn(job, resumePdfPath);
            case "NAUKRI" -> applyOnNaukri(job, resumePdfPath);
            default -> {
                log.info("No auto-apply for source '{}' — use email apply", job.getSource());
                yield false;
            }
        };
    }

    @PreDestroy
    public void cleanup() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        log.info("Playwright browser closed");
    }
}
