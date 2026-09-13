package org.learning.mldsa.dtos;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Whether the system is still waiting for its first Central Bank overseer.
 *
 * Public, so the sign-in screen can offer first-time setup on an empty database. It reveals
 * only that no overseer exists yet, which is the one moment that fact is useful to anyone.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BootstrapStatusResponse {
    private boolean open;
}
