package org.learning.mldsa.controllers;

import lombok.RequiredArgsConstructor;
import org.learning.mldsa.dtos.ComplianceProveRequest;
import org.learning.mldsa.dtos.ComplianceProveResponse;
import org.learning.mldsa.dtos.ComplianceVerifyRequest;
import org.learning.mldsa.dtos.ComplianceVerifyResponse;
import org.learning.mldsa.services.ZkComplianceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Standalone prototype endpoints for the zero-knowledge compliance-proof capability
 * (backed by the separate Rust service in zk-compliance-service/, run with
 * `cargo run --release`, default port 7878).
 *
 * NOT WIRED INTO THE BANKING SYSTEM: nothing here is called by SlipController,
 * FileTransferService, or any other part of the existing payslip / file-transfer flow, and
 * this controller does not read or write FileTransfer records. It exists purely so the
 * capability can be exercised and reviewed through its own endpoints -- POST a set of
 * earnings/deductions, get back a proof; POST a proof back, get back a yes/no -- before any
 * decision is made about attaching it to a real transfer. Wiring it into SlipController
 * (e.g. proving a payslip's net pay at send time and storing the proof alongside the
 * FileTransfer record) is a small, separate follow-up once this is reviewed.
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
}
