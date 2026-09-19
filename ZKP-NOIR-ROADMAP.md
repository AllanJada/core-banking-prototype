# Adding Zero-Knowledge Proofs with Noir — Implementation Roadmap

Status: plan only. Nothing here is built yet.

[`FLOWS.md` §6](FLOWS.md) already argues *where* zero-knowledge proofs fit this system and
*why* — the three distrust boundaries, the commitment precondition, and what ZKP would not
fix. **That reasoning is not repeated here.** Read it first; this document is the other half:
the toolchain, the order of work, what each milestone has to prove before it counts as done,
and the decisions that are expensive to change once code exists.

---

## 0. Toolchain, as it actually stands today

Checked against upstream on 2026-09-19 rather than from memory, because this ecosystem moves
faster than most and a roadmap pinned to stale versions is worse than none.

| Component | Version | Notes |
|---|---|---|
| Noir (`nargo`) | **v1.0.0-rc.3**, released 2026-09-18 | Still release-candidate. `noirup` installs it; the release bundle carries `nargo`, `noir-execute`, `noir-profiler` and `noir-inspector` at the archive root |
| Barretenberg (`bb`) | **v5.2.0**, released 2026-08-17 | Proving/verifying backend, shipped from `aztec-packages` |
| JVM bindings | **none** | Unchanged. There is no native Noir prover or verifier for Java |

### Three corrections to what `FLOWS.md` §6.4 currently shows

Verified directly against the `noir-lang/noir` stdlib source, not the docs site (the docs are
behind in at least one place):

1. **`std::merkle::compute_merkle_root` no longer exists.** There is no `merkle.nr` in the
   standard library at all. The third circuit snippet in §6.4 will not compile. Merkle path
   verification is now something you write yourself — a fold over the path, hashing pairwise —
   or take from a community library. This is a small loop, but it is *security-relevant* code
   that used to be someone else's problem and is now ours.
2. **`pedersen_hash` is still in the stdlib and is not deprecated.** The commitment snippets in
   §6.4 (a) and (b) are still valid. Good: it means the commitment scheme needs no external
   dependency.
3. **Poseidon2 is only half in the stdlib.** `std::hash::poseidon2_permutation` is exposed, but
   the ergonomic sponge (`Poseidon2Hasher`) is `pub(crate)`. The docs site claims Poseidon is
   "outside the standard library" — partly true. Since Poseidon2's advantage is mainly cheaper
   Merkle trees, and our trees are small, **start with `pedersen_hash`** and only move if
   proving time actually becomes the constraint.

**Pin every version.** `Nargo.toml` records the Noir version; the sidecar image should pin `bb`
by digest. A proof that verified last week and fails today because a backend auto-updated is the
worst possible debugging session, and it is entirely avoidable.

---

## 1. Decisions to make before writing any circuit

These are the ones that are painful to reverse, because they get baked into committed data that
outlives the code.

### 1.1 What exactly is committed, and by whom

A proof is only as good as the commitment behind it (`FLOWS.md` §6.2, §6.6). Before a circuit
exists, decide:

- **Commitment scheme:** `pedersen_hash([value, salt])`. The salt is what stops a verifier
  brute-forcing a small value space — a balance commitment with no salt is guessable, since
  there are not that many plausible balances.
- **Who signs the commitment:** the institution, with the Ed25519 key it already uses for
  statements. This is the existing trust model, unchanged; the proof rides on top of it.
- **Where the commitment lives:** a new table. It must be queryable by an outside verifier
  without the customer's help, or the customer becomes a trusted intermediary and the point is
  lost.
- **Salt custody:** the prover needs the salt to prove. If the bank holds it and the customer
  proves, the bank must hand it over — and anyone holding `(value, salt)` can verify the
  commitment directly. Decide whether the salt is per-commitment, and who stores it, **before**
  the first commitment is written.

### 1.2 Field encoding for signed amounts

Noir `Field` elements have no signed comparison. Settlement positions go negative routinely, so
any circuit touching a position carries an offset (`FLOWS.md` §6.4(b) shows the shape).

Getting this wrong produces a circuit that **silently accepts what it should reject** — it does
not error, it proves a false statement. Treat the encoding as a specified, tested artefact:
decide the offset once, write it down, and test the boundaries (position exactly at the cap,
one past it, maximum negative) before trusting any proof built on it.

Money is `numeric(19,2)` in the ledger. Circuits should work in **integer minor units**
(cents), converted once at the boundary, never in decimals.

### 1.3 Where verification happens

`FLOWS.md` §6.5 chose a **verifier sidecar** over JNI or on-chain. That still holds, and the
precedent is stronger than it was: this repo already runs a specialised engine out-of-process
for exactly this reason — `PdfGenerationService` drives headless Chromium through Playwright,
and the Docker setup already builds on Playwright's own image. A `bb` sidecar is the same shape
of decision, and the compose file is the place it goes.

---

## 2. Repository layout

Keep circuits out of the Java tree. They have their own toolchain, their own lockfile, and
their own test command.

```
mldsa/
  bank-backend/          unchanged
  bank-frontend/         unchanged
  zk/
    circuits/
      proof_of_funds/    Nargo.toml, src/main.nr   — milestone M2
      cap_compliance/    Nargo.toml, src/main.nr   — milestone M4
      common/            shared: commitment, merkle path, encoding
    sidecar/             bb wrapper: HTTP verify + prove
    scripts/
      verify-zkp.sh      the suite, in the style of the existing six
    README.md            how to install the toolchain and run the circuits
```

`zk/` gets its own `.gitignore` for `target/` and proof artefacts. Proofs and witnesses are
build output; the only things committed are circuit source, `Nargo.toml`, and the verification
key.

---

## 3. Milestones

Each is independently useful and independently verifiable. The rule this project already
follows applies: **a milestone is done when something outside the application confirms it**,
not when the code looks right.

### M0 — Toolchain spike (half a day)

Prove the toolchain works on this machine before designing around it.

- Install `noirup`, pin `nargo` v1.0.0-rc.3 and `bb` v5.2.0.
- `nargo new`, a circuit asserting `x != y`, `nargo execute`, `bb prove`, `bb verify`.
- Record wall-clock proving time and proof size.

**Done when:** a proof verifies from the command line, and the numbers are written down. If
proving a toy circuit is already slow on this hardware, that changes the plan — better to learn
it now than at M4.

**Deliberately throwaway.** Nothing from M0 ships.

### M1 — Commitments, with no circuits at all

This is the precondition from `FLOWS.md` §6.2, and it is worth doing on its own merits: it makes
the ledger tamper-evident even if no proof is ever generated.

- `LedgerCommitmentService`: for an account and period, build a Merkle tree over that account's
  postings and publish the root.
- Sign the root with the institution's key — reusing `CryptoService`, with a **new envelope
  builder**, following the convention this codebase already enforces (every envelope ever signed
  must stay rebuildable in the form it was signed).
- Migration `V8`: `ledger.commitments` — account, period, root, salt policy, signature, signed-at.
- A balance commitment per account/period: `pedersen_hash([balance_minor_units, salt])`.

**Done when:** a verification script rebuilds a root independently from the postings table and
gets the same value the service stored; and altering one posting changes the root. Both checks
belong in SQL and a script, not in the service that produced them.

**Note this needs a Pedersen implementation in Java** to build the same commitment the circuit
will check. That is the first real integration cost, and it is worth confirming a usable one
exists before committing to `pedersen_hash` — if not, the sidecar grows a `commit` endpoint and
Java stops computing commitments itself.

### M2 — Proof of funds (the flagship)

The case from `FLOWS.md` §6.3(a): a customer proves they hold at least X without handing over a
statement showing every transaction.

- Circuit `proof_of_funds`: private `balance`, `salt`; public `commitment`, `threshold`.
- Merkle path verification written by hand (see §0 — the stdlib helper is gone).
- Prove in the browser with `noir_js`, so the balance and salt never leave the customer's
  machine. **If proving happens server-side, this milestone proves nothing** — the server
  already knows the balance.

**Done when:** a proof generated in the browser verifies with `bb verify` on the command line,
against a commitment the bank signed, and the balance appears nowhere in the proof or the public
inputs. That last part wants checking by actually reading the artefacts, not by assuming.

**Negative tests, which are the ones that matter:** a proof for a balance *below* the threshold
must fail to verify; a proof against a *different* commitment must fail; a tampered public input
must fail.

### M3 — The verifier sidecar

Only now does the Java system need to verify anything.

- Small HTTP service wrapping `bb verify`: `{circuit, proof, publicInputs}` → boolean.
- `ProofVerificationService` in Java calls it.
- Added to `docker-compose.yml` alongside the existing three services.
- Store results the way signatures already are: `FileTransfer` carries `signature` and
  `signatureValid` side by side; a proof carries `proof`, `publicInputs` and `proofValid` in the
  same shape, with a `ProofChip` in the UI mirroring `SignatureChip`.

**Done when:** the existing six suites still pass with the sidecar absent — proof verification
must be *additive*, never a new way for a payment to fail. A dead sidecar should degrade to
"unverified", not "refused".

### M4 — Cap compliance

`FLOWS.md` §6.3(b): an institution proves its position stays within its net debit cap without
revealing the position.

- Circuit `cap_compliance`, using the offset encoding decided in §1.2.
- Verified at `AdminService.settlement` instead of reading positions directly.

**Boundary tests are the deliverable here**, not the happy path: position exactly at the cap, one
minor unit past it, and the maximum negative position the encoding permits.

**Honest note:** in this deployment one database holds every bank, so the Central Bank can simply
compute the position itself. This milestone is a *demonstration* of the mechanism that would
matter if the tiers were genuinely separate systems — which is worth building and worth saying
plainly when presenting it.

### M5 — Only if the tiers ever separate

Private settlement movements (§6.3(c)) and client-side authorisation (§6.3(d)). (d) is the only
one that touches the non-repudiation gap in the README, and it does so by **moving key custody**,
not by adding cryptography — which makes it the most invasive change in this document.

Not scheduled. Listed so the order is visible.

---

## 4. Where this touches the existing code

| Milestone | Java | New |
|---|---|---|
| M1 | `CryptoService` (new envelope builder), `AccountService` (read postings) | `LedgerCommitmentService`, `V8` migration |
| M2 | `StatementService` gains a sibling that emits a commitment rather than a document | `zk/circuits/proof_of_funds` |
| M3 | — | `ProofVerificationService`, sidecar, compose service, `ProofChip` |
| M4 | `AdminService.settlement`, `PaymentService` inter-bank branch | `zk/circuits/cap_compliance` |

Nothing before M3 requires the Java system to change behaviour, which is deliberate: M1 and M2
can be built and demonstrated without touching a single existing code path.

---

## 5. Traps worth naming in advance

- **Garbage in, valid proof out.** A circuit proves you know inputs matching a commitment. If
  the commitment is wrong, the proof is still valid. This is the single most common way ZKP
  systems are misunderstood, and §6.6 says it too — it bears repeating because it is the one
  that invalidates everything else.
- **Proving server-side defeats the purpose** for M2. If the server can see the balance, it
  gains nothing from a proof about it.
- **The circuit is code, and can be wrong.** The offset encoding in §1.2 is a live example: a
  wrong encoding yields valid proofs of false statements, with no error anywhere.
- **Toolchain drift.** Noir is at a *release candidate*. Expect breaking changes; pin versions
  and expect to re-pin deliberately.
- **Proving time is the user's time.** Browser proving on a mid-range phone is the constraint to
  measure at M0, not to discover at M2.
- **Proofs do not replace I1–I5.** Those check the ledger is internally consistent. A proof says
  "I know values matching this commitment" — it says nothing about whether the commitment
  described the whole ledger.

---

## 6. What this does not include

- Any blockchain. Noir emits a Solidity verifier; that is only worth it if settlement actually
  moves on-chain, and it does not.
- Replacing Ed25519. Signatures answer "who produced this"; proofs answer "is this true without
  showing me". Both stay.
- Recursive proofs, proof aggregation, or a trusted-setup ceremony.

---

## 7. Suggested first session

1. **M0**, end to end, and write down the timings.
2. Decide §1.1 (salt custody) and §1.2 (offset encoding) — on paper, in this document.
3. Confirm a usable Java Pedersen implementation exists, or decide the sidecar computes
   commitments instead.

Only then start M1. Steps 2 and 3 are the ones that are expensive to get wrong later, and
neither requires writing a circuit to settle.

---

*Sources for §0: [Noir releases](https://github.com/noir-lang/noir/releases),
[noir-lang/noir stdlib source](https://github.com/noir-lang/noir/tree/master/noir_stdlib/src),
[aztec-packages releases](https://github.com/AztecProtocol/aztec-packages/releases),
[Noir hash documentation](https://noir-lang.org/docs/noir/standard_library/cryptographic_primitives/hashes).*
