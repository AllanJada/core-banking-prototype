package org.learning.mldsa.repositories;

import java.math.BigDecimal;

/**
 * One account's derived balance, as returned by the grouped balance query.
 *
 * Exists so the oversight view can read every balance in a single aggregate rather than
 * asking for one account at a time — the difference between one query and one per account
 * on a page that lists all of them.
 */
public record AccountBalance(Long accountId, BigDecimal balance) {
}
