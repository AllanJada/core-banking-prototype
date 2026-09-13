package org.learning.mldsa.services;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Margin;
import org.learning.mldsa.dtos.SlipRequest;
import org.learning.mldsa.dtos.StatementDocument;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Renders documents as HTML (via Thymeleaf) and prints that HTML to PDF using a real
 * headless Chromium instance (via Playwright) — the same mechanism as a browser's own
 * "Print to PDF", so tables/wrapping/layout behave exactly as they would on screen. This
 * is what makes a multi-page statement paginate correctly for free, rather than having to
 * be laid out against a low-level PDF drawing API.
 *
 * REQUIRED ONE-TIME SETUP, easy to miss: Playwright's Java package does NOT bundle a
 * browser. After a build, run once:
 *   mvn exec:java -e -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium"
 * Without this, Playwright.create() / .chromium().launch() will fail at runtime looking
 * for a browser executable that was never downloaded.
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

        return renderPdf(templateEngine.process("slip", context));
    }

    /**
     * @return an account statement as raw PDF bytes. Unlike a slip this is a reading of
     *         data the server already holds, so there is no client-supplied content in it
     *         at all beyond the requested date range.
     */
    public byte[] generateStatementPdf(StatementDocument statement) {
        Context context = new Context();
        context.setVariable("statement", statement);

        return renderPdf(templateEngine.process("statement", context));
    }

    /**
     * Drives headless Chromium to print the given HTML.
     *
     * Shared by every document this service produces: launching a browser is the expensive
     * and easy-to-leak part, and having one copy of it means a new document type is a
     * template plus a context, not another lifecycle to get right.
     */
    private byte[] renderPdf(String html) {
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
