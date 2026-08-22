package org.learning.mldsa.services;

import org.springframework.core.io.Resource;

public record FileDownload(Resource resource, String originalFilename) {
}
