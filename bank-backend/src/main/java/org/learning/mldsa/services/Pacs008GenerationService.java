package org.learning.mldsa.services;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.Marshaller;
import org.learning.mldsa.iso20022.pacs008.*;
import org.learning.mldsa.models.Account;
import org.learning.mldsa.models.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.namespace.QName;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.GregorianCalendar;

/**
 * Writes the ISO 20022 {@code pacs.008.001.08} interbank message for a payment that crosses
 * institutions.
 *
 * This is the counterpart to Pain001GenerationService, and the distinction is the whole point
 * of having both: a {@code pain.001} is what a customer sends their own bank to *initiate* a
 * transfer, while a {@code pacs.008} is what that bank then sends the receiving bank to
 * settle it. The system had no use for the second message until the Central Bank became a
 * settlement operator and payments started crossing banks — the two-tier plan named it as a
 * follow-up for exactly that reason.
 *
 * Settlement method is CLRG: both institutions hold settlement accounts at the Central Bank
 * and the money moves between them there, which is settlement through a clearing system
 * rather than across either agent's own books (INDA/INGA).
 *
 * As with pain.001, every message is validated against the official schema before it is
 * returned, and the schema in resources is the same file the JAXB classes were generated
 * from, so the two cannot drift.
 */
@Service
public class Pacs008GenerationService {

    /** The message definition this service writes, recorded on every row it produces. */
    public static final String MESSAGE_TYPE = "pacs.008.001.08";

    private static final String SCHEMA_RESOURCE = "/iso20022/pacs.008.001.08.xsd";
    private static final String NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08";

    /** Our account numbers are not IBANs — see Pain001GenerationService for why that matters. */
    private static final String ACCOUNT_SCHEME = "BBAN";

    /** Marks a proprietary institution identifier, used when an institution has no BIC. */
    private static final String INSTITUTION_SCHEME = "PRTRY";

    /** The schema's own cap on an unstructured remittance line. */
    private static final int USTRD_MAX_LENGTH = 140;

    private final JAXBContext jaxbContext;
    private final Schema schema;
    private final DatatypeFactory datatypeFactory;

    @Value("${app.iso20022.clearing-system}")
    private String clearingSystem;

    public Pacs008GenerationService() {
        try {
            this.jaxbContext = JAXBContext.newInstance(Document.class);
            this.datatypeFactory = DatatypeFactory.newInstance();

            // Compiled once at startup: a missing or broken XSD should stop the application
            // rather than surface on the first inter-bank payment.
            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            try (InputStream xsd = Pacs008GenerationService.class.getResourceAsStream(SCHEMA_RESOURCE)) {
                if (xsd == null) {
                    throw new IllegalStateException("pacs.008 schema not found at " + SCHEMA_RESOURCE);
                }
                this.schema = schemaFactory.newSchema(new StreamSource(xsd));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialise ISO 20022 pacs.008 generation", e);
        }
    }

    /**
     * Builds and validates the interbank message for one credit transfer.
     *
     * @param uetr the payment's reference, which is also the ledger's transactionRef for its
     *             four postings — carried into the message unchanged, never regenerated
     * @return the XML as UTF-8 bytes, guaranteed to have passed schema validation
     */
    public byte[] generatePacs008(Account debtorAccount, Account creditorAccount, BigDecimal amount,
                                  String description, String uetr, Instant createdAt) {
        Document document = buildDocument(debtorAccount, creditorAccount, amount, description, uetr, createdAt);
        byte[] xml = marshal(document);
        validateOrThrow(xml);
        return xml;
    }

    private Document buildDocument(Account debtorAccount, Account creditorAccount, BigDecimal amount,
                                   String description, String uetr, Instant createdAt) {
        User debtorAgent = debtorAccount.getInstitution();
        User creditorAgent = creditorAccount.getInstitution();
        // The identifier fields are Max35Text and a UUID string is 36 characters, so they
        // carry it without hyphens; the UETR element itself takes the full form, its type
        // being a UUIDv4 pattern that requires them.
        String compactReference = compactReference(uetr);
        BigDecimal settledAmount = amount.setScale(2, RoundingMode.UNNECESSARY);
        String currency = debtorAccount.getCurrency();
        LocalDate settlementDate = LocalDate.ofInstant(createdAt, ZoneOffset.UTC);

        GroupHeader93 groupHeader = new GroupHeader93();
        groupHeader.setMsgId(compactReference);
        groupHeader.setCreDtTm(toXmlDateTime(createdAt));
        groupHeader.setNbOfTxs("1");
        groupHeader.setTtlIntrBkSttlmAmt(currencyAmount(settledAmount, currency));
        groupHeader.setIntrBkSttlmDt(toXmlDate(settlementDate));
        groupHeader.setSttlmInf(settlementInstruction());
        groupHeader.setInstgAgt(agent(debtorAgent));
        groupHeader.setInstdAgt(agent(creditorAgent));

        CreditTransferTransaction39 transaction = new CreditTransferTransaction39();
        transaction.setPmtId(paymentIdentification(uetr, compactReference));
        transaction.setIntrBkSttlmAmt(currencyAmount(settledAmount, currency));
        transaction.setIntrBkSttlmDt(toXmlDate(settlementDate));
        // Following service level: this system levies no charges on a transfer, and claiming
        // the debtor or creditor bore one would be untrue.
        transaction.setChrgBr(ChargeBearerType1Code.SLEV);
        transaction.setDbtr(party(debtorAccount.getOwner().getName()));
        transaction.setDbtrAcct(account(debtorAccount));
        transaction.setDbtrAgt(agent(debtorAgent));
        transaction.setCdtrAgt(agent(creditorAgent));
        transaction.setCdtr(party(creditorAccount.getOwner().getName()));
        transaction.setCdtrAcct(account(creditorAccount));
        if (!isBlank(description)) {
            transaction.setRmtInf(remittance(description));
        }

        FIToFICustomerCreditTransferV08 creditTransfer = new FIToFICustomerCreditTransferV08();
        creditTransfer.setGrpHdr(groupHeader);
        creditTransfer.getCdtTrfTxInf().add(transaction);

        Document document = new Document();
        document.setFIToFICstmrCdtTrf(creditTransfer);
        return document;
    }

    /**
     * How the two banks settle: across the Central Bank, which holds both their settlement
     * accounts.
     *
     * CLRG rather than INDA or INGA, which would say the money moved on one of the two
     * agents' own books. Neither agent holds an account with the other here — that is the
     * whole point of the settlement tier.
     */
    private SettlementInstruction7 settlementInstruction() {
        ClearingSystemIdentification3Choice clearing = new ClearingSystemIdentification3Choice();
        // Proprietary: this system is not a registered clearing system with an external code.
        clearing.setPrtry(clearingSystem);

        SettlementInstruction7 settlement = new SettlementInstruction7();
        settlement.setSttlmMtd(SettlementMethod1Code.CLRG);
        settlement.setClrSys(clearing);
        return settlement;
    }

    private PaymentIdentification7 paymentIdentification(String uetr, String compactReference) {
        PaymentIdentification7 identification = new PaymentIdentification7();
        identification.setEndToEndId(compactReference);
        identification.setTxId(compactReference);
        // The schema's own UETR field, whose type enforces the version-4 nibble and lowercase
        // hex. This is the ledger's transaction reference, passed through unchanged.
        identification.setUETR(uetr);
        return identification;
    }

    private ActiveCurrencyAndAmount currencyAmount(BigDecimal amount, String currency) {
        ActiveCurrencyAndAmount value = new ActiveCurrencyAndAmount();
        value.setValue(amount);
        value.setCcy(currency);
        return value;
    }

    private PartyIdentification135 party(String name) {
        PartyIdentification135 party = new PartyIdentification135();
        party.setNm(name);
        return party;
    }

    /** An account identified by its number under a named proprietary scheme, never as an IBAN. */
    private CashAccount38 account(Account source) {
        GenericAccountIdentification1 generic = new GenericAccountIdentification1();
        generic.setId(source.getAccountNumber());
        AccountSchemeName1Choice scheme = new AccountSchemeName1Choice();
        scheme.setPrtry(ACCOUNT_SCHEME);
        generic.setSchmeNm(scheme);

        AccountIdentification4Choice identification = new AccountIdentification4Choice();
        identification.setOthr(generic);

        CashAccount38 account = new CashAccount38();
        account.setId(identification);
        account.setCcy(source.getCurrency());
        return account;
    }

    /**
     * A financial institution: by BIC when it has one, otherwise by its bank code under a
     * proprietary scheme.
     *
     * The institutions here are not SWIFT-registered, so the fallback is the normal path.
     * The identifier is the institution's own bank code — the same one that prefixes the
     * account numbers in this very message — rather than an internal database id, so the
     * message is self-consistent to anyone reading it.
     */
    private BranchAndFinancialInstitutionIdentification6 agent(User institution) {
        FinancialInstitutionIdentification18 identification = new FinancialInstitutionIdentification18();
        identification.setNm(institution.getName());

        if (!isBlank(institution.getBic())) {
            identification.setBICFI(institution.getBic().trim().toUpperCase());
        } else {
            GenericFinancialIdentification1 generic = new GenericFinancialIdentification1();
            generic.setId(institution.getInstitutionCode());
            FinancialIdentificationSchemeName1Choice scheme = new FinancialIdentificationSchemeName1Choice();
            scheme.setPrtry(INSTITUTION_SCHEME);
            generic.setSchmeNm(scheme);
            identification.setOthr(generic);
        }

        BranchAndFinancialInstitutionIdentification6 agent = new BranchAndFinancialInstitutionIdentification6();
        agent.setFinInstnId(identification);
        return agent;
    }

    /** What the payment was for, trimmed to the schema's limit rather than left to fail it. */
    private RemittanceInformation16 remittance(String description) {
        String text = description.trim();
        RemittanceInformation16 remittanceInformation = new RemittanceInformation16();
        remittanceInformation.getUstrd().add(
                text.length() > USTRD_MAX_LENGTH ? text.substring(0, USTRD_MAX_LENGTH) : text);
        return remittanceInformation;
    }

    private byte[] marshal(Document document) {
        try {
            Marshaller marshaller = jaxbContext.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            marshaller.setProperty(Marshaller.JAXB_ENCODING, StandardCharsets.UTF_8.name());

            // The generated Document class carries no @XmlRootElement, so the root element and
            // its namespace are supplied here.
            JAXBElement<Document> root = new JAXBElement<>(
                    new QName(NAMESPACE, "Document"), Document.class, document);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            marshaller.marshal(root, out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate the ISO 20022 settlement message", e);
        }
    }

    /**
     * The hard gate: reject anything the official schema does not accept.
     *
     * A payment whose message cannot be written is refused outright — the transaction rolls
     * back, so no money moves on an instruction that could not be expressed. That is the
     * stricter half of the bargain the standard offers: a receiving bank may reject a
     * malformed message, and a sender that emitted one anyway has moved money nobody can act
     * on.
     */
    private void validateOrThrow(byte[] xml) {
        try {
            Validator validator = schema.newValidator();
            validator.validate(new StreamSource(new ByteArrayInputStream(xml)));
        } catch (SAXException e) {
            throw new RuntimeException(
                    "The generated ISO 20022 settlement message failed schema validation: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read the generated settlement message for validation", e);
        }
    }

    private XMLGregorianCalendar toXmlDateTime(Instant instant) {
        GregorianCalendar calendar = GregorianCalendar.from(instant.atZone(ZoneOffset.UTC));
        return datatypeFactory.newXMLGregorianCalendar(calendar);
    }

    private XMLGregorianCalendar toXmlDate(LocalDate date) {
        // FIELD_UNDEFINED for the timezone: a settlement date is a calendar day, and attaching
        // an offset would imply a precision it does not have.
        return datatypeFactory.newXMLGregorianCalendarDate(
                date.getYear(), date.getMonthValue(), date.getDayOfMonth(), DatatypeConstants.FIELD_UNDEFINED);
    }

    private static String compactReference(String uetr) {
        return uetr.replace("-", "");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
