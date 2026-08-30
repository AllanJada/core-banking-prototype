package org.learning.mldsa.controllers;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.ComplianceProveRequest;
import org.learning.mldsa.dtos.ComplianceProveResponse;
import org.learning.mldsa.dtos.ComplianceVerifyRequest;
import org.learning.mldsa.dtos.ComplianceVerifyResponse;
import org.learning.mldsa.services.FileTransferService;
import org.learning.mldsa.services.ZkComplianceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Endpoints for the zero-knowledge compliance-proof capability (backed by the separate Rust
 * service in zk-compliance-service/, run with `cargo run --release`, default port 7878).
 *
 * WIRED IN: every payslip sent through SlipController now gets a compliance proof attached
 * automatically -- see FileTransferService#attachComplianceProof, called from
 * signEncryptAndPersist. That happens entirely server-side with no request here; /prove and
 * /verify below remain standalone endpoints for exercising the capability directly (proving
 * an arbitrary set of earnings/deductions, or verifying a proof, without any FileTransfer
 * involved), and /proof/{transferId} is the read path for a proof that was already attached
 * to a real transfer at send time.
 *
 * Demonstrates: proving a computed total (net pay = earnings - deductions) is correct
 * without revealing the individual line items that produced it -- only the total, the
 * entry count, and the proof are ever exposed here.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/compliance")
public class ComplianceController {

    private final ZkComplianceService zkComplianceService;
    private final FileTransferService fileTransferService;

    @GetMapping("/health")
    ResponseEntity<?> health() {
        boolean healthy = zkComplianceService.isHealthy();
        HttpStatus status = healthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(Map.of("reachable", healthy));
    }

    @PostMapping("/prove")
    ResponseEntity<?> prove(@RequestBody ComplianceProveRequest request) {
        try {
            ZkComplianceService.ProveResult result =
                    zkComplianceService.prove(request.getEarningsCents(), request.getDeductionsCents());
            return ResponseEntity.ok(new ComplianceProveResponse(
                    result.netPayCents, result.numEntries, result.proofBase64, result.proofSizeBytes));
        } catch (ZkComplianceService.ZkServiceException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/verify")
    ResponseEntity<?> verify(@RequestBody ComplianceVerifyRequest request) {
        try {
            ZkComplianceService.VerifyResult result = zkComplianceService.verify(
                    request.getProofBase64(), request.getNetPayCents(), request.getNumEntries());
            return ResponseEntity.ok(new ComplianceVerifyResponse(result.valid, result.error));
        } catch (ZkComplianceService.ZkServiceException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
        }
    }

    // Scoped to sender-or-receiver inside the service layer, not the controller -- same
    // Controller-Service-Repository layering as every other endpoint in this codebase.
    @GetMapping("/proof/{transferId}")
    ResponseEntity<?> getProof(@PathVariable Long transferId, @AuthenticationPrincipal Long callerId) {
        return fileTransferService.getComplianceProof(transferId, callerId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
