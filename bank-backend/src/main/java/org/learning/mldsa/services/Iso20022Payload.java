package org.learning.mldsa.services;

/**
 * An ISO 20022 payload travelling with a transfer, and the reference that ties them
 * together.
 *
 * The UETR is carried here rather than minted inside the transfer service because it has to
 * exist before the payload is built — the message embeds it — and the same value then has to
 * reach the persisted transfer and the signed envelope. Generating it at the point of
 * origination and passing it along is what keeps all three consistent; minting it in more
 * than one place would produce references that disagree.
 */
public record Iso20022Payload(String uetr, byte[] xml, String filename) {
}
