package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A postal address held as discrete components, mirroring ISO 20022's {@code PstlAdr}.
 *
 * This replaced a single free-text address field, and the reason is the whole point of the
 * standard rather than tidiness: ISO 20022 requires structured components, and unstructured
 * addresses are being withdrawn across the major schemes. Capturing the parts separately
 * from the start means generating a payload later is a mapping exercise, whereas splitting
 * a free-text line back into street, town and country afterwards is guesswork that no
 * amount of code makes reliable.
 *
 * Only the fields this project actually needs are modelled. {@code PstlAdr} defines around
 * fifteen, most of which a payslip has no use for.
 *
 * "Hybrid" is the minimum the schemes accept — town and country present, the rest optional
 * — which is what {@link #isHybridComplete()} checks.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostalAddress {

    /** ISO 20022 StrtNm. */
    private String streetName;

    /** ISO 20022 BldgNb. */
    private String buildingNumber;

    /** ISO 20022 PstCd. */
    private String postCode;

    /** ISO 20022 TwnNm — required for even a hybrid address. */
    private String townName;

    /** ISO 20022 Ctry: a two-letter ISO 3166-1 alpha-2 code, not a country name. */
    private String country;

    /**
     * Whether this address carries at least the town and country the schemes require.
     *
     * Not enforced at the DTO level — a slip is still a document a human reads, and refusing
     * to render one over a missing postcode would be the wrong trade. The generator is where
     * this becomes a hard gate, because that is where an incomplete address stops being a
     * presentation problem and starts producing a payload a bank would reject.
     */
    public boolean isHybridComplete() {
        return isPresent(townName) && isPresent(country);
    }

    /**
     * The address as a single line, for display on the rendered document.
     *
     * Derived rather than stored: keeping a separate free-text copy alongside the components
     * would be two sources of truth that could disagree about the same address.
     */
    public String toDisplayLine() {
        String street = Stream.of(buildingNumber, streetName)
                .filter(PostalAddress::isPresent)
                .collect(Collectors.joining(" "));

        return Stream.of(street, postCode, townName, country)
                .filter(PostalAddress::isPresent)
                .collect(Collectors.joining(", "));
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
