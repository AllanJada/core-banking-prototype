package org.learning.mldsa.services;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Margin;
import org.learning.mldsa.dtos.SlipRequest;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Renders a payslip as HTML (via Thymeleaf) and prints that HTML to PDF using a real
 * headless Chromium instance (via Playwright) — the same mechanism as a browser's own
 * "Print to PDF", so tables/wrapping/layout behave exactly as they would on screen.
 *
 * REQUIRED DEPENDENCIES (add to pom.xml, not included here):
 *   org.springframework.boot:spring-boot-starter-thymeleaf
 *   com.microsoft.playwright:playwright, version 1.48.0 or later
 *
 * REQUIRED ONE-TIME SETUP, easy to miss: Playwright's Java package does NOT bundle a
 * browser. After adding the dependency, run once (from the project root, after a build):
 *   mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
 * or equivalently:
 *   java -cp target/classes:$(find ~/.m2 -name 'playwright-*.jar' | tr '\n' ':') com.microsoft.playwright.CLI install chromium
 * Without this, Playwright.create() / .chromium().launch() will fail at runtime looking
 * for a browser executable that was never downloaded.
 *
 * NOT VERIFIED BY COMPILATION OR EXECUTION — written without Maven, Thymeleaf, or
 * Playwright available in the environment this was authored in. Review carefully; render
 * a slip end-to-end before relying on this.
 */
@Service
public class PdfGenerationService {

    private final TemplateEngine templateEngine;

    public PdfGenerationService(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    /**
     * @return the rendered PDF as raw bytes. These bytes are what the caller should hash
     *         and sign directly — nothing about them passes through client-controlled
     *         input after this method returns; the only client input involved was the
     *         structured field values in {@code request}, embedded into a fixed template.
     */
    public byte[] generateSlipPdf(SlipRequest request) {
        Context context = new Context();
        context.setVariable("slip", request);
        context.setVariable("totalEarnings", request.totalEarnings());
        context.setVariable("totalDeductions", request.totalDeductions());
        context.setVariable("netPay", request.netPay());

        String html = templateEngine.process("slip", context);

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            try {
                Page page = browser.newPage();
                page.setContent(html);
                return page.pdf(new Page.PdfOptions()
                        .setFormat("A4")
                        .setPrintBackground(true)
                        .setMargin(new Margin()
                                .setTop("30px")
                                .setBottom("30px")
                                .setLeft("20px")
                                .setRight("20px")));
            } finally {
                browser.close();
            }
        }
    }
}
