package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.StatementDocument;
import org.learning.mldsa.models.Account;
import org.learning.mldsa.models.Posting;
import org.learning.mldsa.models.PostingDirection;
import org.learning.mldsa.models.User;
import org.learning.mldsa.repositories.PostingRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds account statements for a date range.
 *
 * A statement is a reading of the ledger, not a record of its own: nothing is stored when
 * one is produced, and asking for the same period twice yields the same figures. That
 * falls out of balances being derived — since a statement recomputes its opening balance
 * from every posting before the period, it cannot drift from the account it describes.
 */
@RequiredArgsConstructor
@Service
public class StatementService {

    // Statement periods are expressed in whole days, and this is the zone those days are
    // measured in. UTC to match the daily payment cap, so both windows agree about when a
    // day starts rather than each drawing the boundary somewhere different.
    private static final ZoneOffset STATEMENT_ZONE = ZoneOffset.UTC;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH).withZone(STATEMENT_ZONE);
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm 'UTC'", Locale.ENGLISH).withZone(STATEMENT_ZONE);

    private final AccountService accountService;
    private final PostingRepository postingRepository;
    private final PdfGenerationService pdfGenerationService;
    private final CryptoService cryptoService;

    public byte[] generateStatementPdf(Long userId, LocalDate from, LocalDate to) {
        return pdfGenerationService.generateStatementPdf(buildStatement(userId, from, to));
    }

    StatementDocument buildStatement(Long userId, LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new RuntimeException("A statement needs both a start and an end date");
        }
        if (to.isBefore(from)) {
            throw new RuntimeException("The end of the period cannot be before its start");
        }

        Account account = accountService.requireAccountFor(userId);
        User institution = account.getInstitution();

        Instant periodStart = from.atStartOfDay(STATEMENT_ZONE).toInstant();
        // Exclusive end: the start of the day after the requested one, so the whole of the
        // final day is included without a closed range's boundary ambiguity.
        Instant periodEndExclusive = to.plusDays(1).atStartOfDay(STATEMENT_ZONE).toInstant();

        BigDecimal openingBalance = postingRepository.sumBalanceBefore(account.getAccountId(), periodStart);
        List<Posting> postings = postingRepository.findForStatement(
                account.getAccountId(), periodStart, periodEndExclusive);

        BigDecimal runningBalance = openingBalance;
        BigDecimal totalCredits = BigDecimal.ZERO;
        BigDecimal totalDebits = BigDecimal.ZERO;
        List<StatementDocument.StatementLine> lines = new ArrayList<>();

        for (Posting posting : postings) {
            boolean isCredit = posting.getDirection() == PostingDirection.CREDIT;
            if (isCredit) {
                runningBalance = runningBalance.add(posting.getAmount());
                totalCredits = totalCredits.add(posting.getAmount());
            } else {
                runningBalance = runningBalance.subtract(posting.getAmount());
                totalDebits = totalDebits.add(posting.getAmount());
            }

            lines.add(new StatementDocument.StatementLine(
                    DATE_FORMAT.format(posting.getPostedAt()),
                    posting.getDescription() == null || posting.getDescription().isBlank()
                            ? "—" : posting.getDescription(),
                    isCredit ? "" : money(posting.getAmount()),
                    isCredit ? money(posting.getAmount()) : "",
                    money(runningBalance)
            ));
        }

        String periodFrom = DATE_FORMAT.format(periodStart);
        String periodTo = DATE_FORMAT.format(to.atStartOfDay(STATEMENT_ZONE).toInstant());
        Instant generatedAt = Instant.now();

        // Signed over the figures the statement asserts, not over the rendered bytes: a PDF
        // cannot contain a signature computed from itself. Altering a figure on the page
        // leaves a signature that no longer matches it. It does not detect edits to the
        // surrounding layout, and because the server holds every private key it is not proof
        // against this server itself.
        //
        // Signed with the INSTITUTION's key, not the customer's. A statement is a document
        // the bank issues *about* an account, so the party attesting to these figures is the
        // bank that holds it — which is also why a verifier checks it against the
        // institution's public key rather than the customer's. The envelope's shape is
        // unchanged; only its signer is.
        String envelope = cryptoService.buildStatementEnvelope(
                account.getAccountNumber(), periodFrom, periodTo,
                openingBalance, runningBalance, generatedAt.toString());
        String signature = cryptoService.sign(envelope, cryptoService.signingKeyOf(institution));

        return new StatementDocument(
                account.getAccountNumber(),
                account.getOwner().getName(),
                institution.getName(),
                institution.getInstitutionCode(),
                institution.getInstitutionNumber(),
                account.getCurrency(),
                periodFrom,
                periodTo,
                TIMESTAMP_FORMAT.format(generatedAt),
                money(openingBalance),
                // The closing balance is the opening balance plus the period's movements,
                // which is exactly what the running total arrived at on the last line.
                money(runningBalance),
                money(totalCredits),
                money(totalDebits),
                generatedAt.toString(),
                signature,
                lines
        );
    }

    /** Money as it is printed: grouped thousands, always two decimals. */
    private String money(BigDecimal amount) {
        return String.format(Locale.ENGLISH, "%,.2f", amount);
    }
}
