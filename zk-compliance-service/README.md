# zk-compliance-service

A standalone Rust microservice that proves a payslip's net pay was computed correctly --
`net_pay = sum(earnings) - sum(deductions)` -- **without revealing the individual earnings
and deduction line items**. Only the net pay total and the number of entries are ever
public. It uses a zero-knowledge STARK (via [Winterfell](https://github.com/facebook/winterfell),
Meta's open-source STARK prover/verifier), which relies on collision-resistant hashing
rather than elliptic-curve math, so unlike SNARK schemes such as Groth16 it is not broken
by Shor's algorithm.

**This is a prototype, deliberately not wired into the rest of banking-dilithium.**
Nothing in `bank-backend`'s existing payslip-send flow (`SlipController`,
`FileTransferService`, `FileTransfer`) calls this service or knows it exists. The only
things that talk to it are:

1. This service's own HTTP API (below), and
2. A small, separate Spring Boot module added alongside it --
   `ZkComplianceService.java` + `ComplianceController.java` + 4 DTOs under
   `bank-backend/.../services`, `.../controllers`, `.../dtos` -- exposed at
   `/api/v1/compliance/{health,prove,verify}`. That module is equally standalone: it is
   not called by anything else in the backend either.

Wiring this into the real payslip flow (e.g. generating a proof when a payslip is sent in
`SlipController`, and storing it alongside the `FileTransfer` record) is intentionally left
as a future step once this prototype has been reviewed.

## What it actually proves (and what it doesn't, yet)

The circuit proves the *arithmetic* is correct: that the claimed net pay really is the sum
of some hidden earnings minus some hidden deductions, and that there are exactly
`numEntries` of them. It does **not** yet prove that each hidden line item is a plausible or
correctly-signed amount (e.g. that no entry is a huge negative "earning" used to smuggle
value out unnoticed). That would need a per-entry range-proof / bit-decomposition gadget
added to the AIR constraints -- noted here as scoped-out v1 future work, not an oversight.

## Running it

```bash
cd zk-compliance-service
cargo run --release
# payslip zk-STARK proof service listening on 0.0.0.0:7878
```

Override the bind address with `PROOF_SERVICE_ADDR` (e.g. `PROOF_SERVICE_ADDR=127.0.0.1:9000 cargo run --release`).

No external services, databases, or network access are required -- the only dependencies
are `serde`, `serde_json`, `tiny_http`, and `winterfell`/`winter-prover`, all pulled from
crates.io.

## HTTP API

**`GET /health`** -> `200 {"status":"ok"}`

**`POST /prove`**
```json
// request
{"earningsCents": [500000, 20000], "deductionsCents": [15000, 5000]}
// response (200)
{"netPayCents": 500000, "numEntries": 4, "proofBase64": "...", "proofSizeBytes": 4609}
// response (400) if earningsCents.length + deductionsCents.length > 15 (trace capacity)
{"error": "too many entries: max 15, got ..."}
```

**`POST /verify`**
```json
// request
{"proofBase64": "...", "netPayCents": 500000, "numEntries": 4}
// response (200) -- proof checks out
{"valid": true}
// response (200) -- proof was checked but rejected (wrong claim, or tampered proof)
{"valid": false, "error": "..."}
// response (400) -- request itself was malformed (bad base64, bad JSON)
{"error": "..."}
```

Try it directly:
```bash
curl -s http://localhost:7878/health

curl -s -X POST http://localhost:7878/prove \
  -H 'Content-Type: application/json' \
  -d '{"earningsCents":[500000,20000],"deductionsCents":[15000,5000]}'

# then, using the proofBase64 from the response above:
curl -s -X POST http://localhost:7878/verify \
  -H 'Content-Type: application/json' \
  -d '{"proofBase64":"<paste here>","netPayCents":500000,"numEntries":4}'
```

## Calling it from Spring Boot (already wired, just not into the payslip flow)

With this service running, `bank-backend`'s own standalone endpoints work the same way:

```bash
curl -s http://localhost:8080/api/v1/compliance/health

curl -s -X POST http://localhost:8080/api/v1/compliance/prove \
  -H 'Content-Type: application/json' \
  -d '{"earningsCents":[500000,20000],"deductionsCents":[15000,5000]}'

curl -s -X POST http://localhost:8080/api/v1/compliance/verify \
  -H 'Content-Type: application/json' \
  -d '{"proofBase64":"<paste here>","netPayCents":500000,"numEntries":4}'
```

`ZkComplianceService` defaults to `http://localhost:7878` and can be pointed elsewhere via
the `app.zk-proof-service-url` property in `application.properties` (no code change
needed). If this service isn't running, the compliance endpoints respond `503` with a
clear error rather than the backend failing to start -- there is no hard dependency between
the two.

## Design notes worth knowing

- The trace is a fixed 16 rows (power-of-two, required by the FFT-based STARK machinery),
  which caps a single proof at 15 earnings+deductions entries combined
  (`stark::MAX_ENTRIES`). A payslip with more line items than that would need either a
  bigger fixed trace size or a variable-length circuit design -- not implemented here.
- Proof size and verification time in this run: ~4.5 KB for a 4-entry payslip, verified
  in a few milliseconds -- consistent with Winterfell's own published range for small
  circuits (their docs cite 15-300 KB / 3-5 ms for their benchmark circuits; this one is
  smaller than their benchmark, hence the smaller proof).
- `ProofOptions` are set for roughly 96-bit conjectured security (32 queries, blowup
  factor 8), matching Winterfell's own documented "reasonable security" example.
