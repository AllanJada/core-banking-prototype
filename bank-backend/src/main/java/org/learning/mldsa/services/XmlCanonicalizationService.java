package org.learning.mldsa.services;

import org.springframework.stereotype.Service;

import javax.xml.crypto.Data;
import javax.xml.crypto.OctetStreamData;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.TransformService;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Reduces an XML document to its canonical byte form (W3C Exclusive XML Canonicalization)
 * before it is hashed.
 *
 * This exists because XML has a problem PDFs do not. The same document can be serialised
 * several equally correct ways — attributes in a different order, an empty element written
 * {@code <B/>} rather than {@code <B></B>}, an XML declaration present or absent — and the
 * bytes differ every time while the meaning does not. Hashing the raw bytes would mean a
 * recipient who re-serialised the message with a different XML library, changing nothing
 * about what it says, would compute a different hash and conclude it had been tampered
 * with. Canonicalising first makes the hash a property of the document rather than of
 * whichever serialiser happened to write it.
 *
 * What it does not do, verified rather than assumed: canonicalization preserves whitespace
 * between elements, because whitespace is significant in XML in the general case. A
 * recipient who reformats or re-indents the document will still compute a different hash.
 * That is the standard's behaviour, not a gap here — the signature covers the document as
 * laid out, and the payload is transmitted and stored exactly as it was signed.
 *
 * No third-party dependency: the JDK ships an implementation under javax.xml.crypto.
 */
@Service
public class XmlCanonicalizationService {

    private static final String ALGORITHM = CanonicalizationMethod.EXCLUSIVE;

    public byte[] canonicalize(byte[] xml) {
        try {
            TransformService transformService = TransformService.getInstance(ALGORITHM, "DOM");
            // The single-argument init takes the transform's parameters; null means the
            // algorithm's defaults. The two-argument overload is for unmarshalling a
            // signature and throws here.
            transformService.init((TransformParameterSpec) null);

            Data canonical = transformService.transform(
                    new OctetStreamData(new ByteArrayInputStream(xml)), null);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ((OctetStreamData) canonical).getOctetStream().transferTo(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to canonicalize the XML payload", e);
        }
    }
}
