package org.learning.mldsa.models;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "users", schema = "identity")
@Data
public class User {
    @Id
    @GeneratedValue( strategy =  GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;
    // Unique in the database, not only checked by UserService: login looks accounts up by
    // name, and two rows sharing one would make that lookup ambiguous.
    @Column(name = "user_name", nullable = false, unique = true)
    private String name;
    @Column(name = "password")
    private String password;

    // ML-DSA-65 keys do not fit the default varchar(255): Base64-encoded, a public key is
    // 2,632 characters. Hence the TEXT override, matching V8__mldsa_key_and_signature_widths.
    @Column(name = "public_key", columnDefinition = "text")
    private String publicKey;

    // Smaller than the public key, and smaller than Ed25519's was: the JDK encodes an
    // ML-DSA private key as its 32-byte seed, 72 characters once Base64-encoded. TEXT
    // anyway, so none of these columns needs revisiting if that encoding ever changes.
    @Column(name = "private_key", columnDefinition = "text")
    private String privateKey;

    // What this account is allowed to do, not just which dashboard it sees — this value
    // is carried in the account's JWT and drives every authorization check on the API.
    // Non-null: UserService always assigns one at creation.
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role;

    /**
     * The institution's Business Identifier Code (ISO 9362), when it has one.
     *
     * Optional, and expected to be absent here: this is a test system whose institutions are
     * not registered with SWIFT, so there are no real BICs to use. ISO 20022 generation
     * falls back to a proprietary financial-institution identification when this is null,
     * which is the correct modelling for an institution without a BIC rather than a
     * workaround — inventing a plausible-looking BIC would be worse, since a syntactically
     * valid code that belongs to some other bank is actively misleading.
     */
    @Column(name = "bic", length = 11)
    private String bic;

    /**
     * A short, stable code identifying an institution — "ALPHA", "NMB" — unique across the
     * system. Set only on INSTITUTION accounts and null for everyone else.
     *
     * Used for display today. Account and card numbers may later carry it as a prefix, but
     * nothing routes on it: which bank holds an account is Account.institution.
     */
    @Column(name = "institution_code", unique = true, length = 8)
    private String institutionCode;

    /**
     * The institution's numeric bank number, assigned when it is licensed, and the digits
     * that prefix the account and card numbers it issues — the way a real account number or
     * card IIN identifies the bank behind it. Set only on INSTITUTION accounts.
     *
     * Separate from institutionCode because that one is alphanumeric for people to read,
     * while these numbers have to stay all-digits (and, for cards, Luhn-checkable). Nothing
     * routes on it: which bank holds an account is Account.institution.
     */
    @Column(name = "institution_number", unique = true, length = 3)
    private String institutionNumber;

}
