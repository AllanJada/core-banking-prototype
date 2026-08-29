package org.learning.mldsa.models;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "users")
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

    // ML-DSA-65 keys, Base64-encoded X.509/PKCS8. TEXT rather than varchar(255) as a
    // deliberate safety margin, though in practice these are small: the JDK's built-in
    // implementation stores the compact seed form for the private key (~72 chars
    // Base64), not the ~5.4KB raw expanded key size FIPS 204 implies; the public key is
    // close to its raw size (~2.6KB Base64).
    @Column(name = "public_key", columnDefinition = "TEXT")
    private String publicKey;

    @Column(name = "private_key", columnDefinition = "TEXT")
    private String privateKey;

    // ML-KEM-768 keys, same encoding/storage rationale as above (public ~1.6KB Base64,
    // private ~116 chars). Used to encrypt/decrypt file contents, independent of the
    // ML-DSA pair above which only ever signs/verifies.
    @Column(name = "kem_public_key", columnDefinition = "TEXT")
    private String kemPublicKey;

    @Column(name = "kem_private_key", columnDefinition = "TEXT")
    private String kemPrivateKey;

}
