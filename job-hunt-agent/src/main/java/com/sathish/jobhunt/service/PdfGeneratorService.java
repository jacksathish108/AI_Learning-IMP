package com.sathish.jobhunt.service;

import com.itextpdf.html2pdf.ConverterProperties;
import com.itextpdf.html2pdf.HtmlConverter;
import com.sathish.jobhunt.config.AppConfig;
import com.sathish.jobhunt.model.Job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Converts tailored resume text → HTML (Thymeleaf) → PDF (iText)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfGeneratorService {

    private final TemplateEngine templateEngine;
    private final AppConfig appConfig;

    /**
     * Generate a PDF from the tailored resume string.
     * Returns the absolute path to the generated PDF.
     */
    public String generateResumePdf(Job job, String tailoredResumeText) {
        String outputDir = appConfig.getPdf().getOutputDir();
        ensureDir(outputDir);

        String filename = sanitize(job.getCompany()) + "_" + sanitize(job.getTitle()) + "_resume.pdf";
        String outputPath = outputDir + "/" + filename;

        try {
            String html = buildResumeHtml(tailoredResumeText, job);
            htmlToPdf(html, outputPath);
            log.info("Resume PDF generated: {}", outputPath);
            return outputPath;

        } catch (Exception e) {
            log.error("PDF generation failed for job {}: {}", job.getId(), e.getMessage());
            throw new RuntimeException("PDF generation failed", e);
        }
    }

    public String generateCoverLetterPdf(Job job, String coverLetterText) {
        String outputDir = appConfig.getPdf().getOutputDir();
        ensureDir(outputDir);

        String filename = sanitize(job.getCompany()) + "_" + sanitize(job.getTitle()) + "_cover.pdf";
        String outputPath = outputDir + "/" + filename;

        try {
            String html = buildCoverLetterHtml(coverLetterText, job);
            htmlToPdf(html, outputPath);
            log.info("Cover letter PDF generated: {}", outputPath);
            return outputPath;

        } catch (Exception e) {
            log.error("Cover letter PDF failed for job {}: {}", job.getId(), e.getMessage());
            throw new RuntimeException("Cover letter PDF failed", e);
        }
    }

    private String buildResumeHtml(String resumeText, Job job) {
        Context ctx = new Context();
        ctx.setVariable("resumeText", resumeText);
        ctx.setVariable("job", job);
        ctx.setVariable("candidate", appConfig.getCandidate());

        // If Thymeleaf template exists, use it; else use inline HTML
        try {
            return templateEngine.process("resume", ctx);
        } catch (Exception e) {
            return buildInlineResumeHtml(resumeText);
        }
    }

    private String buildInlineResumeHtml(String resumeText) {
        // Convert plain text to HTML with basic formatting
        String htmlContent = resumeText
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replaceAll("\n\n", "</p><p>")
            .replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");

        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="UTF-8"/>
              <style>
                body { font-family: 'Calibri', Arial, sans-serif; font-size: 11pt;
                       margin: 40px; color: #222; line-height: 1.5; }
                h1 { font-size: 18pt; color: #1a1a2e; margin-bottom: 4px; }
                h2 { font-size: 12pt; color: #16213e; border-bottom: 1.5px solid #0f3460;
                     padding-bottom: 3px; margin-top: 16px; }
                p { margin: 4px 0; }
                .header { text-align: center; margin-bottom: 16px; }
                .contact { font-size: 9.5pt; color: #444; }
                ul { margin: 4px 0; padding-left: 18px; }
                li { margin-bottom: 3px; }
              </style>
            </head>
            <body>
              <p>%s</p>
            </body>
            </html>
            """.formatted(htmlContent);
    }

    private String buildCoverLetterHtml(String coverText, Job job) {
        AppConfig.Candidate c = appConfig.getCandidate();
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="UTF-8"/>
              <style>
                body { font-family: 'Calibri', Arial, sans-serif; font-size: 11pt;
                       margin: 60px; color: #222; line-height: 1.7; }
                .header { margin-bottom: 24px; }
                .name { font-size: 14pt; font-weight: bold; }
                .contact { font-size: 10pt; color: #555; }
                .body { margin-top: 24px; }
              </style>
            </head>
            <body>
              <div class="header">
                <div class="name">%s</div>
                <div class="contact">%s | %s | %s</div>
              </div>
              <div class="body">
                <p>Hiring Team, %s</p>
                <br/>
                %s
                <br/>
                <p>Best regards,<br/><strong>%s</strong></p>
              </div>
            </body>
            </html>
            """.formatted(
                c.getName(), c.getPhone(), c.getEmail(), c.getLinkedin(),
                job.getCompany(),
                coverText.replace("\n", "<br/>"),
                c.getName()
            );
    }

    private void htmlToPdf(String html, String outputPath) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(outputPath)) {
            ConverterProperties props = new ConverterProperties();
            HtmlConverter.convertToPdf(html, fos, props);
        }
    }

    private String sanitize(String input) {
        return input == null ? "unknown" : input.replaceAll("[^a-zA-Z0-9_-]", "_").substring(0, Math.min(30, input.length()));
    }

    private void ensureDir(String dir) {
        try { Files.createDirectories(Paths.get(dir)); }
        catch (IOException e) { log.warn("Could not create PDF output dir: {}", dir); }
    }
}
