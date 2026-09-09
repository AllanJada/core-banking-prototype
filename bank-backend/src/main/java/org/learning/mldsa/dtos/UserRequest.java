package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.learning.mldsa.models.Role;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserRequest {
    private String username;
    private String password;
    // Optional — defaults to NORMAL_USER in UserService if omitted. Retail customers are
    // the role this system will have most of, so an unspecified account is the least
    // privileged one rather than a file-transfer counterparty.
    private Role role;

    // Optional ISO 9362 BIC, only meaningful for institutions. Absent for every account in
    // this test system; ISO 20022 generation falls back to proprietary identification.
    private String bic;
}
