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
    @Column(name = "user_name")
    private String name;
    @Column(name = "password")
    private String password;

    // Ed25519 keys are small: 44 bytes encoded for the public key and 48 for the private,
    // which is ~60 and ~64 characters once Base64-encoded. Both fit the default
    // varchar(255) comfortably, so the TEXT override these needed under ML-DSA-65
    // (~2.6KB and ~5.4KB respectively) is no longer warranted.
    @Column(name = "public_key")
    private String publicKey;

    @Column(name = "private_key")
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

}
