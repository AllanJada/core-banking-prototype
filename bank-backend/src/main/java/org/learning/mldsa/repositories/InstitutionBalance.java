package org.learning.mldsa.repositories;

import java.math.BigDecimal;

/**
 * The summed balance of one institution's accounts of a single type, as returned by the
 * grouped query — customer funds held, or a settlement position.
 *
 * Grouped in the database for the same reason AccountBalance is: a list of institutions
 * should cost one aggregate, not one query per institution.
 */
public record InstitutionBalance(Long institutionId, BigDecimal balance) {
}
