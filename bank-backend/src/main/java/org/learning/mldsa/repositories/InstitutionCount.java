package org.learning.mldsa.repositories;

/** How many accounts of a single type one institution holds, as returned by a grouped count. */
public record InstitutionCount(Long institutionId, Long count) {
}
