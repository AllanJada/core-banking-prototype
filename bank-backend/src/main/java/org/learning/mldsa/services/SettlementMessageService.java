package org.learning.mldsa.services;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.SettlementMessageResponse;
import org.learning.mldsa.models.Account;
import org.learning.mldsa.models.Payment;
import org.learning.mldsa.models.SettlementMessage;
import org.learning.mldsa.models.User;
import org.learning.mldsa.repositories.SettlementMessageRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

/**
 * The interbank messages behind inter-bank payments: writing one when money crosses banks,
 * and serving it back with its integrity re-checked.
 *
 * The order of operations matters and is the same one the rest of this system uses for signed
 * artefacts — generate, validate against the schema, canonicalise, hash, sign, store, record —
 * with no window in between where the message could differ from what was signed. It runs
 * inside the payment's own transaction, so a payment and its instruction commit together or
 * not at all: there is no state where money moved without the message that instructed it.
 */
@RequiredArgsConstructor
@Service
public class SettlementMessageService {

    private final Pacs008GenerationService pacs008GenerationService;
    private final XmlCanonicalizationService xmlCanonicalizationService;
    private final FileStorageService fileStorageService;
    private final CryptoService cryptoService;
    private final SettlementMessageRepository settlementMessageRepository;

    /**
     * Writes the pacs.008 for a payment that has just crossed institutions.
     *
     * Signed by the debtor agent: the paying bank is the party instructing the other, so it is
     * the party whose signature the message carries.
     */
    @Transactional
    public SettlementMessage record(Payment payment, Account debtorAccount, Account creditorAccount) {
        User debtorAgent = debtorAccount.getInstitution();
        User creditorAgent = creditorAccount.getInstitution();
        String uetr = payment.getTransactionRef();

        byte[] xml = pacs008GenerationService.generatePacs008(
                debtorAccount, creditorAccount, payment.getAmount(), payment.getDescription(),
                uetr, payment.getCreatedAt());

        // Hashed after canonicalisation, so the hash is a property of the document rather than
        // of whichever library happened to serialise it (see XmlCanonicalizationService).
        String xmlHash = cryptoService.hashFile(xmlCanonicalizationService.canonicalize(xml));

        String envelope = cryptoService.buildSettlementMessageEnvelope(
                uetr, debtorAgent.getInstitutionCode(), creditorAgent.getInstitutionCode(),
                payment.getAmount(), xmlHash, payment.getCreatedAt().toEpochMilli());
        String signature = cryptoService.sign(envelope, cryptoService.signingKeyOf(debtorAgent));

        String storedFilename = fileStorageService.store(xml, "pacs008-" + uetr + ".xml");

        SettlementMessage message = new SettlementMessage();
        message.setPayment(payment);
        message.setDebtorAgent(debtorAgent);
        message.setCreditorAgent(creditorAgent);
        message.setDebtorAgentCode(debtorAgent.getInstitutionCode());
        message.setCreditorAgentCode(creditorAgent.getInstitutionCode());
        message.setUetr(uetr);
        message.setMessageType(Pacs008GenerationService.MESSAGE_TYPE);
        message.setAmount(payment.getAmount());
        message.setCurrency(debtorAccount.getCurrency());
        message.setStoredFilename(storedFilename);
        message.setXmlHash(xmlHash);
        message.setSignature(signature);
        message.setCreatedAt(payment.getCreatedAt());

        return settlementMessageRepository.save(message);
    }

    /** The messages this institution sent or received, each marked with which it was. */
    public Page<SettlementMessageResponse> listForInstitution(Long institutionId, Pageable pageable) {
        return settlementMessageRepository.findForInstitution(institutionId, pageable)
                .map(message -> toResponse(message, institutionId));
    }

    /** Every message, for the Central Bank as settlement operator. */
    public Page<SettlementMessageResponse> listAll(Pageable pageable) {
        return settlementMessageRepository.findAllByOrderByCreatedAtDescMessageIdDesc(pageable)
                .map(message -> toResponse(message, null));
    }

    /** One message, but only if the asking institution is a party to it. */
    public SettlementMessage requireForInstitution(Long messageId, Long institutionId) {
        return settlementMessageRepository.findForInstitution(messageId, institutionId)
                .orElseThrow(() -> new RuntimeException("Settlement message not found"));
    }

    public SettlementMessage require(Long messageId) {
        return settlementMessageRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("Settlement message not found"));
    }

    /**
     * The message's XML, re-verified at the moment it is served rather than trusted.
     *
     * Both checks matter and catch different things: the hash proves the stored bytes are the
     * ones that were signed, and the signature proves the debtor agent produced them. The
     * envelope is rebuilt from the values persisted with the message, never recomputed from
     * the institutions' current state, so a later change to either bank cannot silently
     * invalidate — or silently repair — an old signature.
     */
    public byte[] loadVerified(SettlementMessage message) {
        byte[] xml = readStored(message);

        String hash = cryptoService.hashFile(xmlCanonicalizationService.canonicalize(xml));
        if (!hash.equals(message.getXmlHash())) {
            throw new RuntimeException("This settlement message has been altered since it was signed");
        }

        String envelope = cryptoService.buildSettlementMessageEnvelope(
                message.getUetr(), message.getDebtorAgentCode(), message.getCreditorAgentCode(),
                message.getAmount(), message.getXmlHash(), message.getCreatedAt().toEpochMilli());
        if (!cryptoService.verify(envelope, message.getSignature(),
                cryptoService.decodePublicKey(message.getDebtorAgent().getPublicKey()))) {
            throw new RuntimeException("This settlement message's signature does not verify");
        }

        return xml;
    }

    /** @param viewerInstitutionId the institution asking, or null for the Central Bank */
    private static SettlementMessageResponse toResponse(SettlementMessage message, Long viewerInstitutionId) {
        String direction = null;
        if (viewerInstitutionId != null) {
            direction = viewerInstitutionId.equals(message.getDebtorAgent().getUserId()) ? "SENT" : "RECEIVED";
        }
        return new SettlementMessageResponse(
                message.getMessageId(),
                message.getPayment().getPaymentId(),
                message.getUetr(),
                message.getMessageType(),
                message.getDebtorAgent().getName(),
                message.getDebtorAgentCode(),
                message.getCreditorAgent().getName(),
                message.getCreditorAgentCode(),
                message.getAmount(),
                message.getCurrency(),
                message.getXmlHash(),
                message.getCreatedAt(),
                direction);
    }

    private byte[] readStored(SettlementMessage message) {
        try {
            return fileStorageService.loadAsResource(message.getStoredFilename())
                    .getContentAsByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read the stored settlement message", e);
        }
    }
}
