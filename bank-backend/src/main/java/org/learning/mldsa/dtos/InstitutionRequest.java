package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A commercial bank to license, with its single login. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InstitutionRequest {
    private String username;
    private String password;

    // Required: 3–8 letters or digits, stored uppercase and unique across the system.
    private String institutionCode;

    // Optional ISO 9362 BIC. Absent for every institution in this test system; ISO 20022
    // generation falls back to proprietary identification.
    private String bic;
}
