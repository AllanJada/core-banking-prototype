package org.learning.mldsa.security;

import org.learning.mldsa.models.Role;

/**
 * The caller's identity as established by their JWT — the authentication principal for
 * every request that reaches a controller.
 *
 * Controllers read this via @AuthenticationPrincipal instead of taking a userId request
 * parameter. That difference is the whole point of this module: a userId in a query
 * string is a claim the client makes about itself, whereas these values were signed by
 * this server at login and re-verified on the way in, so scoping a query to
 * user.userId() can no longer be bypassed by editing a URL.
 */
public record AuthenticatedUser(Long userId, String username, Role role) {
}
