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

    // ML-DSA-65 keys are large once Base64-encoded (public ~2.6KB, private ~5.4KB) —
    // TEXT avoids silent truncation under Hibernate's default varchar(255) column size.
    @Column(name = "public_key", columnDefinition = "TEXT")
    private String publicKey;

    @Column(name = "private_key", columnDefinition = "TEXT")
    private String privateKey;

    // Distinguishes the two account categories added later: regular institution
    // accounts (the original dashboard) vs. "bank" accounts (the sidebar dashboard).
    // Existing rows created before this field existed will read as null — treat that
    // as INSTITUTION at the call site rather than assuming it's always populated.
    @Enumerated(EnumType.STRING)
    @Column(name = "user_type")
    private UserType userType;

}
