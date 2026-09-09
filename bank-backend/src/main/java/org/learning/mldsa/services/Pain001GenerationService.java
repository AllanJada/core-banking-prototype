package org.learning.mldsa.services;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.Marshaller;
import org.learning.mldsa.dtos.PostalAddress;
import org.learning.mldsa.dtos.SlipRequest;
import org.learning.mldsa.iso20022.pain001.*;
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
 * Turns a slip into an ISO 20022 {@code pain.001.001.09} customer credit transfer
 * initiation, alongside the PDF and from the same data.
 *
 * A payslip is not itself an ISO 20022 message — the catalogue has no such thing, because
 * it models payments rather than employment documents. What a slip describes, though, is a
 * credit transfer: an amount moving from the employer's account to the employee's. That is
 * what gets expressed here. The earnings and deductions breakdown has no counterpart in the
 * standard and stays on the PDF, which remains the human-readable artefact.
 *
 * Every message is validated against the official schema before it is returned. That gate
 * is the point of the exercise: the standard's own value is that a receiver can reject a
 * malformed message outright instead of interpreting it, and a generator that emitted
 * invalid XML and left someone downstream to notice would give that up.
 */
@Service
public class Pain001GenerationService {

    private static final String SCHEMA_RESOURCE = "/iso20022/pain.001.001.09.xsd";
    private static final String NAMESPACE = "urn:iso:std:iso:20022:tech:xsd:pain.001.001.09";

    /**
     * Names the scheme our account numbers belong to.
     *
     * These are not IBANs — Tanzania does not use them — so account identification goes
     * through Othr/Id with a proprietary scheme name saying what the number is. Putting a
     * non-IBAN in the IBAN field would produce schema-valid XML that a receiving bank would
     * either reject or, worse, act on as though it were an IBAN.
     */
    private static final String ACCOUNT_SCHEME = "BBAN";

    /** Marks a proprietary institution identifier, used when an institution has no BIC. */
    private static final String INSTITUTION_SCHEME = "PRTRY";

    /** The schema's own cap on an unstructured remittance line. */
    private static final int USTRD_MAX_LENGTH = 140;

    private final JAXBContext jaxbContext;
    private final Schema schema;
    private final DatatypeFactory datatypeFactory;

    @Value("${app.iso20022.initiating-party}")
    private String initiatingParty;

    // The same currency the ledger opens accounts in, rather than a second hardcoded copy
    // that could disagree with it.
    @Value("${app.ledger.default-currency}")
    private String currency;

    public Pain001GenerationService() {
        try {
            this.jaxbContext = JAXBContext.newInstance(Document.class);
            this.datatypeFactory = DatatypeFactory.newInstance();

            // Loaded once at startup: compiling a schema is expensive, and a missing or
            // broken XSD should stop the application rather than surface on the first send.
            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            try (InputStream xsd = Pain001GenerationService.class.getResourceAsStream(SCHEMA_RESOURCE)) {
                if (xsd == null) {
                    throw new IllegalStateException("pain.001 schema not found at " + SCHEMA_RESOURCE);
                }
                this.schema = schemaFactory.newSchema(new StreamSource(xsd));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialise ISO 20022 pain.001 generation", e);
        }
    }

    /**
     * Builds and validates the payload.
     *
     * @param uetr the transfer's end-to-end reference, minted at origination and carried
     *             into the message unchanged
     * @return the XML as UTF-8 bytes, guaranteed to have passed schema validation
     */
    public byte[] generatePain001(SlipRequest slip, User sender, User receiver, String uetr, Instant createdAt) {
        requireMappable(slip);

        Document document = buildDocument(slip, sender, receiver, uetr, createdAt);
        byte[] xml = marshal(document);
        validateOrThrow(xml);
        return xml;
    }

    /**
     * Rejects a slip that cannot produce a valid message, with a reason naming the field.
     *
     * Checked before marshalling rather than relying on schema validation alone, because a
     * schema failure reports a violated XSD rule, and "cvc-minLength-valid: value has
     * length 0" is not something the person who left the town blank can act on.
     */
    private void requireMappable(SlipRequest slip) {
        if (isBlank(slip.getOrganizationName())) {
            throw new RuntimeException("The organization name is required to generate an ISO 20022 payload");
        }
        if (isBlank(slip.getEmployeeName())) {
            throw new RuntimeException("The employee name is required to generate an ISO 20022 payload");
        }
        if (isBlank(slip.getPayerAccount()) || isBlank(slip.getPayeeAccount())) {
            throw new RuntimeException("Both payer and payee account numbers are required to generate an ISO 20022 payload");
        }
        PostalAddress address = slip.getOrganizationAddress();
        if (address == null || !address.isHybridComplete()) {
            // The gate Phase 1 deliberately left off the document itself: a town and a
            // country are the minimum the payment schemes accept.
            throw new RuntimeException(
                    "The organization address needs at least a town and a two-letter country code to generate an ISO 20022 payload");
        }
        if (address.getCountry().length() != 2) {
            throw new RuntimeException("The country must be a two-letter ISO 3166-1 code");
        }
        if (slip.netPay().signum() <= 0) {
            throw new RuntimeException("Net pay must be greater than zero to generate an ISO 20022 payload");
        }
    }

    private Document buildDocument(SlipRequest slip, User sender, User receiver, String uetr, Instant createdAt) {
        // Net pay is what actually moves: gross earnings less deductions is the sum the
        // employee is credited, and a credit transfer carries one amount.
        BigDecimal amount = slip.netPay().setScale(2, RoundingMode.UNNECESSARY);

        // The identifier fields are Max35Text, and a UUID string is 36 characters, so the
        // reference is carried in them without its hyphens. The UETR element itself takes
        // the full form — its type is a UUIDv4 pattern that requires them.
        String compactReference = compactReference(uetr);

        GroupHeader85 groupHeader = new GroupHeader85();
        groupHeader.setMsgId(compactReference);
        groupHeader.setCreDtTm(toXmlDateTime(createdAt));
        groupHeader.setNbOfTxs("1");
        groupHeader.setCtrlSum(amount);
        groupHeader.setInitgPty(party(initiatingParty, null));

        PaymentInstruction30 payment = new PaymentInstruction30();
        payment.setPmtInfId(compactReference);
        payment.setPmtMtd(PaymentMethod3Code.TRF);
        payment.setNbOfTxs("1");
        payment.setCtrlSum(amount);
        payment.setReqdExctnDt(executionDate(slip.getDate()));
        // The employer is the debtor: this is money leaving their account.
        payment.setDbtr(party(slip.getOrganizationName(), slip.getOrganizationAddress()));
        payment.setDbtrAcct(account(slip.getPayerAccount(), currency));
        payment.setDbtrAgt(agent(sender));
        payment.setChrgBr(ChargeBearerType1Code.SLEV);
        payment.getCdtTrfTxInf().add(transaction(slip, receiver, uetr, amount));

        CustomerCreditTransferInitiationV09 initiation = new CustomerCreditTransferInitiationV09();
        initiation.setGrpHdr(groupHeader);
        initiation.getPmtInf().add(payment);

        Document document = new Document();
        document.setCstmrCdtTrfInitn(initiation);
        return document;
    }

    private CreditTransferTransaction34 transaction(SlipRequest slip, User receiver, String uetr,
                                                     BigDecimal amount) {
        ActiveOrHistoricCurrencyAndAmount instructedAmount = new ActiveOrHistoricCurrencyAndAmount();
        instructedAmount.setValue(amount);
        instructedAmount.setCcy(currency);

        AmountType4Choice amountChoice = new AmountType4Choice();
        amountChoice.setInstdAmt(instructedAmount);

        PaymentIdentification6 paymentId = new PaymentIdentification6();
        paymentId.setEndToEndId(compactReference(uetr));
        // The schema carries a UETR field of its own, whose type is a UUIDv4 pattern — it
        // enforces the version-4 nibble and lowercase hex. This is exactly the identifier
        // minted at origination, passed through unchanged and never regenerated here.
        paymentId.setUETR(uetr);

        CreditTransferTransaction34 transaction = new CreditTransferTransaction34();
        transaction.setPmtId(paymentId);
        transaction.setAmt(amountChoice);
        transaction.setCdtrAgt(agent(receiver));
        // The employee is the creditor. No address: the slip does not collect one, and
        // inventing a placeholder would be worse than omitting an optional element.
        transaction.setCdtr(party(slip.getEmployeeName(), null));
        transaction.setCdtrAcct(account(slip.getPayeeAccount(), currency));
        transaction.setRmtInf(remittance(slip));
        return transaction;
    }

    /**
     * What the payment is for, as free-text remittance information.
     *
     * Unstructured on purpose. Structured remittance models invoices, creditor references
     * and tax records — there is no payroll shape in it, so an earnings and deductions
     * breakdown has nowhere honest to go. A short description of the pay period is the
     * useful, truthful thing to carry; the detail stays on the PDF.
     *
     * Ustrd is capped at 140 characters by the schema, so this is trimmed rather than left
     * to fail validation.
     */
    private RemittanceInformation16 remittance(SlipRequest slip) {
        String text = remittanceText(slip);
        RemittanceInformation16 remittanceInformation = new RemittanceInformation16();
        remittanceInformation.getUstrd().add(text.length() > USTRD_MAX_LENGTH
                ? text.substring(0, USTRD_MAX_LENGTH)
                : text);
        return remittanceInformation;
    }

    private String remittanceText(SlipRequest slip) {
        StringBuilder text = new StringBuilder();
        text.append(isBlank(slip.getTitle()) ? "Salary payment" : slip.getTitle().trim());
        if (!isBlank(slip.getPayPeriod())) {
            text.append(" - ").append(slip.getPayPeriod().trim());
        }
        if (!isBlank(slip.getEmployeeName())) {
            text.append(" - ").append(slip.getEmployeeName().trim());
        }
        return text.toString();
    }

    /** A party, with a structured address when one is supplied. */
    private PartyIdentification135 party(String name, PostalAddress address) {
        PartyIdentification135 party = new PartyIdentification135();
        party.setNm(name);
        if (address != null) {
            party.setPstlAdr(postalAddress(address));
        }
        return party;
    }

    private PostalAddress24 postalAddress(PostalAddress source) {
        PostalAddress24 address = new PostalAddress24();
        address.setStrtNm(blankToNull(source.getStreetName()));
        address.setBldgNb(blankToNull(source.getBuildingNumber()));
        address.setPstCd(blankToNull(source.getPostCode()));
        address.setTwnNm(source.getTownName().trim());
        address.setCtry(source.getCountry().trim().toUpperCase());
        return address;
    }

    /**
     * An account, identified by its number under a named proprietary scheme.
     *
     * Never IBAN — see ACCOUNT_SCHEME.
     */
    private CashAccount38 account(String accountNumber, String currency) {
        GenericAccountIdentification1 generic = new GenericAccountIdentification1();
        generic.setId(accountNumber.trim());
        AccountSchemeName1Choice scheme = new AccountSchemeName1Choice();
        scheme.setPrtry(ACCOUNT_SCHEME);
        generic.setSchmeNm(scheme);

        AccountIdentification4Choice identification = new AccountIdentification4Choice();
        identification.setOthr(generic);

        CashAccount38 account = new CashAccount38();
        account.setId(identification);
        account.setCcy(currency);
        return account;
    }

    /**
     * A financial institution: by BIC when it has one, otherwise by a proprietary identifier.
     *
     * The institutions in this system are not SWIFT-registered, so the fallback is the
     * normal path here rather than an edge case. It is still correct modelling: the schema
     * provides Othr precisely for institutions without a BIC, and emitting an invented BIC
     * would be a fabricated claim about a real bank.
     */
    private BranchAndFinancialInstitutionIdentification6 agent(User institution) {
        FinancialInstitutionIdentification18 identification = new FinancialInstitutionIdentification18();
        identification.setNm(institution.getName());

        if (!isBlank(institution.getBic())) {
            identification.setBICFI(institution.getBic().trim().toUpperCase());
        } else {
            GenericFinancialIdentification1 generic = new GenericFinancialIdentification1();
            generic.setId(String.valueOf(institution.getUserId()));
            FinancialIdentificationSchemeName1Choice scheme = new FinancialIdentificationSchemeName1Choice();
            scheme.setPrtry(INSTITUTION_SCHEME);
            generic.setSchmeNm(scheme);
            identification.setOthr(generic);
        }

        BranchAndFinancialInstitutionIdentification6 agent = new BranchAndFinancialInstitutionIdentification6();
        agent.setFinInstnId(identification);
        return agent;
    }

    private DateAndDateTime2Choice executionDate(LocalDate date) {
        DateAndDateTime2Choice choice = new DateAndDateTime2Choice();
        choice.setDt(toXmlDate(date == null ? LocalDate.now(ZoneOffset.UTC) : date));
        return choice;
    }

    private byte[] marshal(Document document) {
        try {
            Marshaller marshaller = jaxbContext.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            marshaller.setProperty(Marshaller.JAXB_ENCODING, StandardCharsets.UTF_8.name());

            // The generated Document class carries no @XmlRootElement, so the root element
            // and its namespace are supplied here.
            JAXBElement<Document> root = new JAXBElement<>(
                    new QName(NAMESPACE, "Document"), Document.class, document);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            marshaller.marshal(root, out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate the ISO 20022 payload", e);
        }
    }

    /**
     * The hard gate: reject anything the official schema does not accept.
     *
     * Deliberately a rejection rather than a logged warning. An invalid payload that still
     * got sent would be discovered by whoever received it, at which point the PDF has
     * already been signed and delivered alongside it.
     */
    private void validateOrThrow(byte[] xml) {
        try {
            Validator validator = schema.newValidator();
            validator.validate(new StreamSource(new ByteArrayInputStream(xml)));
        } catch (SAXException e) {
            throw new RuntimeException("The generated ISO 20022 payload failed schema validation: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read the generated ISO 20022 payload for validation", e);
        }
    }

    private XMLGregorianCalendar toXmlDateTime(Instant instant) {
        GregorianCalendar calendar = GregorianCalendar.from(instant.atZone(ZoneOffset.UTC));
        return datatypeFactory.newXMLGregorianCalendar(calendar);
    }

    private XMLGregorianCalendar toXmlDate(LocalDate date) {
        // FIELD_UNDEFINED for the timezone: an execution date is a calendar day, and
        // attaching an offset to it would imply a precision it does not have.
        return datatypeFactory.newXMLGregorianCalendarDate(
                date.getYear(), date.getMonthValue(), date.getDayOfMonth(), DatatypeConstants.FIELD_UNDEFINED);
    }

    /**
     * The reference in the 32-character form the Max35Text identifier fields can hold.
     *
     * Derived from the UETR rather than being a separate value, so every identifier in the
     * message still traces back to the one reference minted at origination.
     */
    private static String compactReference(String uetr) {
        return uetr.replace("-", "");
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
