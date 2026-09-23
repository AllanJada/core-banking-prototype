# Adding Zero-Knowledge Proofs with Noir — Implementation Roadmap

Status: plan, with M0 (the toolchain spike) done — see §8. No production code yet.

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

### 1.2 Java must not compute the commitment — and this reorders the milestones

The obvious shape is: Java computes `pedersen_hash([balance, salt])`, signs it, stores it; the
circuit recomputes it and checks the match. **Do not build that.**

Noir's `pedersen_hash` runs on the **Grumpkin** curve — BN254's embedded curve — with generators
derived as Keccak-256 outputs mapped onto group elements. There is no JVM library implementing
it, and matching it by hand means reimplementing Aztec's exact generator derivation plus
Grumpkin arithmetic. A discrepancy anywhere in that produces a hash that is *silently different*,
and the resulting failure is a hash mismatch across a field-arithmetic boundary between two
languages, with no useful error on either side.

The deeper objection is structural rather than practical: this would be **two implementations of
one function**, permanently. They would have to agree across every future toolchain upgrade.

So: **one implementation only.** The sidecar owns every Pedersen operation; Java calls it, stores
the returned commitment, and signs that with the existing Ed25519 key. Java never does field
arithmetic, and the value that gets signed is produced by the same code the circuit checks
against.

**Consequence: the sidecar is infrastructure, not a later milestone.** It has to exist before the
first commitment is written, which is why M1 below is the sidecar and commitments follow it.

**The sidecar is Node, not a shell wrapper around `bb`** — established at M0 (§8). The `bb` CLI
has only `prove`, `write_vk`, `verify` and some Aztec-specific commands; it exposes no hashing
subcommand at all, so there is nothing to shell out to. `@aztec/bb.js` — the same Barretenberg,
compiled to WASM — does expose `pedersenHash`, and **its output was confirmed identical to the
Noir circuit's** at M0. That settles the choice: the sidecar is a small Node service over
`@aztec/bb.js`, pinned to the same version as `bb`.

This is better than the shell-wrapper plan rather than merely different. The browser proving path
in M3 uses `noir_js` over the same `bb.js`, so prover and verifier share one library — the "one
implementation" property holds across the browser boundary too, not just inside the server.

### 1.3 Salt custody is a privacy decision, not a storage one

The prover needs the salt, so if the customer proves, the bank must release it. Two things follow
that are easy to miss:

- **A salt does not expire.** Anyone who has ever seen `(value, salt)` can verify that commitment
  forever. A customer who proves solvency once, to one counterparty, has handed that counterparty
  a permanent ability to confirm that exact balance.
- **Therefore salts must be per-commitment**, not per-account. That bounds the damage to a single
  period rather than to every balance the account ever held.

Decide this before the first commitment is written. Changing it afterwards invalidates everything
already signed, because the commitments cannot be recomputed — the inputs may be gone.

### 1.4 Version the commitment scheme from the first row

Noir is at a release candidate (§0), and breaking changes between candidates are normal. The
asymmetry that matters: **a circuit can be rewritten, but a commitment in the database is
permanent.** If `pedersen_hash`'s parameterisation changes, every stored commitment becomes
unverifiable with no migration path — you cannot recompute them, because the entire point is that
you may no longer hold the inputs.

So `ledger.commitments` carries a `scheme_version` from the first migration, recording which hash
and which toolchain version produced each root. Trivial to include now; impossible to retrofit
onto rows whose inputs are gone.

### 1.5 Field encoding for signed amounts

Noir `Field` elements have no signed comparison. Settlement positions go negative routinely, so
any circuit touching a position carries an offset (`FLOWS.md` §6.4(b) shows the shape).

Getting this wrong produces a circuit that **silently accepts what it should reject** — it does
not error, it proves a false statement. Treat the encoding as a specified, tested artefact:
decide the offset once, write it down, and test the boundaries (position exactly at the cap,
one past it, maximum negative) before trusting any proof built on it.

Money is `numeric(19,2)` in the ledger. Circuits should work in **integer minor units**
(cents), converted once at the boundary, never in decimals.

### 1.6 Where proving and verifying happen

`FLOWS.md` §6.5 chose a **verifier sidecar** over JNI or on-chain. That still holds, and the
precedent is stronger than it was: this repo already runs a specialised engine out-of-process
for exactly this reason — `PdfGenerationService` drives headless Chromium through Playwright,
and the Docker setup already builds on Playwright's own image. A `bb` sidecar is the same shape
of decision, and the compose file is the place it goes.

§1.2 widens its job: it is not only the verifier but the single owner of every Pedersen
operation in the system. Verification of a *customer's* proof still happens wherever the
verifier is — and the proof for M3 must be generated **in the browser**, or it demonstrates
nothing (§5).

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
      proof_of_funds/    Nargo.toml, src/main.nr   — milestone M3
      cap_compliance/    Nargo.toml, src/main.nr   — milestone M4
      common/            shared: commitment, merkle path, encoding
    sidecar/             Node service over @aztec/bb.js: commit + verify (§1.2, §8)
    scripts/
      verify-zkp.sh      the suite, in the style of the existing six
    .toolchain/          nargo and bb, pinned per-repo; versions.txt records what and why
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

### M0 — Toolchain spike — **done**, see §8

Prove the toolchain works on this machine before designing around it. Results and the three
findings that came out of it are in §8; the code was throwaway and nothing from it ships.

### M1 — The sidecar, before anything commits

This was originally scheduled after the commitments and the first circuit. It moved to the front
for the reason in §1.2: it owns every Pedersen operation in the system, so it has to exist
before the first commitment is written. Nothing verifies proofs yet — this milestone exists to
make one implementation of the hash rather than two.

- Small HTTP service wrapping `bb`, pinned by digest: `commit` (fields → commitment) and
  `verify` (`{circuit, proof, publicInputs}` → boolean).
- `ProofService` in Java calls it. Java never does field arithmetic.
- Added to `docker-compose.yml` alongside the existing three services.

**Done when:** the same inputs produce the same commitment from the sidecar and from a Noir
circuit asserting `pedersen_hash(inputs) == expected`. That equality is the entire point of the
milestone, and it must be demonstrated, not assumed — it is what the M2 commitments will rest on.

**Also done when:** the existing six suites still pass with the sidecar **absent**. Everything ZKP
must be additive; a dead sidecar degrades to "unverified", never to "refused". This holds for
every milestone after it too.

### M2 — Commitments, with no circuits at all

The precondition from `FLOWS.md` §6.2, and worth doing on its own merits: it makes the ledger
tamper-evident even if no proof is ever generated.

- `LedgerCommitmentService`: for an account and period, build a Merkle tree over that account's
  postings and publish the root. Hashing goes through the M1 sidecar.
- Sign the root with the institution's key — reusing `CryptoService`, with a **new envelope
  builder**, following the convention this codebase already enforces (every envelope ever signed
  must stay rebuildable in the form it was signed).
- Migration `V8`: `ledger.commitments` — account, period, root, `scheme_version` (§1.4),
  signature, signed-at. Salts are per-commitment (§1.3).
- A balance commitment per account/period: `pedersen_hash([balance_minor_units, salt])`.

**Done when:** a verification script rebuilds a root independently from the postings table and
gets the value the service stored; and altering one posting changes the root. Both checks belong
in SQL and a script, not in the service that produced them.

### M3 — Proof of funds (the flagship)

The case from `FLOWS.md` §6.3(a): a customer proves they hold at least X without handing over a
statement showing every transaction.

- Circuit `proof_of_funds`: private `balance`, `salt`; public `commitment`, `threshold`.
- Merkle path verification written by hand (see §0 — the stdlib helper is gone).
- Prove in the browser with `noir_js`, so the balance and salt never leave the customer's
  machine. **If proving happens server-side, this milestone proves nothing** — the server
  already knows the balance.
- Store results the way signatures already are: `FileTransfer` carries `signature` and
  `signatureValid` side by side; a proof carries `proof`, `publicInputs` and `proofValid` in the
  same shape, with a `ProofChip` in the UI mirroring `SignatureChip`.

**Done when:** a proof generated in the browser verifies through the M1 sidecar *and* with
`bb verify` on the command line, against a commitment the bank signed — and the balance appears
nowhere in the proof or the public inputs. That last part wants checking by reading the
artefacts, not by assuming.

**Negative tests, which are the ones that matter:** a proof for a balance *below* the threshold
must fail; a proof against a *different* commitment must fail; a tampered public input must fail.

**Decide the demo beat before writing the circuit.** This is the hardest thing in the project to
demonstrate, because nothing visibly happens — that is the point. The framing that works is a
contrast: the same customer, first the statement PDF showing every transaction, then a proof
showing only "≥ 5,000,000". That choice determines what the public inputs should be, so make it
first.

### M4 — Cap compliance

`FLOWS.md` §6.3(b): an institution proves its position stays within its net debit cap without
revealing the position.

- Circuit `cap_compliance`, using the offset encoding decided in §1.5.
- Verified at `AdminService.settlement` instead of reading positions directly.

**Boundary tests are the deliverable here**, not the happy path: position exactly at the cap, one
minor unit past it, and the maximum negative position the encoding permits.

**Worth deciding whether to build at all.** In this deployment one database holds every bank, so
the Central Bank can simply compute the position itself — this milestone demonstrates a mechanism
that would matter only if the tiers were genuinely separate systems. That is a legitimate thing
to build and a legitimate thing to skip; what it is not is a thing to present as though the
Central Bank could not already see the answer.

### M5 — Only if the tiers ever separate

Private settlement movements (§6.3(c)) and client-side authorisation (§6.3(d)). (d) is the only
one that touches the non-repudiation gap in the README, and it does so by **moving key custody**,
not by adding cryptography — which makes it the most invasive change in this document.

Not scheduled. Listed so the order is visible.

---

## 4. Where this touches the existing code

| Milestone | Java | New |
|---|---|---|
| M1 | `ProofService` (calls the sidecar; no field arithmetic) | sidecar, compose service |
| M2 | `CryptoService` (new envelope builder), `AccountService` (read postings) | `LedgerCommitmentService`, `V8` migration |
| M3 | `StatementService` gains a sibling that emits a commitment rather than a document | `zk/circuits/proof_of_funds`, `ProofChip` |
| M4 | `AdminService.settlement`, `PaymentService` inter-bank branch | `zk/circuits/cap_compliance` |

Nothing through M3 changes the behaviour of an existing code path: M1 adds a service nothing yet
calls, M2 writes a new table, and M3's verifier is the customer's counterparty rather than this
system. M4 is the first milestone where an existing decision — the Central Bank reading a
settlement position — is replaced rather than supplemented.

---

## 5. Traps worth naming in advance

- **Garbage in, valid proof out.** A circuit proves you know inputs matching a commitment. If
  the commitment is wrong, the proof is still valid. This is the single most common way ZKP
  systems are misunderstood, and §6.6 says it too — it bears repeating because it is the one
  that invalidates everything else.
- **Proving server-side defeats the purpose** for M3. If the server can see the balance, it
  gains nothing from a proof about it.
- **The circuit is code, and can be wrong.** The offset encoding in §1.5 is a live example: a
  wrong encoding yields valid proofs of false statements, with no error anywhere.
- **One hash, one implementation.** Computing the commitment in both Java and Noir would be two
  implementations of one function that must agree forever, across a curve the JVM has no library
  for. §1.2 is why the sidecar owns it.
- **A wrong circuit does not throw.** Every defect this project has fixed recently announced
  itself — an overdrawn balance, a duplicate row, a failed assertion. This class does not, which
  is why the negative tests in M3 and the boundary tests in M4 *are* the deliverable rather than
  a courtesy. It is also the strongest argument for doing REMAINING-WORK §6.1 first: there is
  currently no harness to hang those tests on.
- **Toolchain drift.** Noir is at a *release candidate*. Expect breaking changes; pin versions
  and expect to re-pin deliberately.
- **Proving time is the user's time.** Browser proving on a mid-range phone is the constraint to
  measure at M0, not to discover at M3.
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
2. Settle §1.5 (offset encoding) on paper, in this document. §1.2, §1.3 and §1.4 are already
   decided above — the sidecar owns the hash, salts are per-commitment, and the scheme is
   versioned from the first row — but they are decisions, so disagree with them now rather than
   after commitments exist.
3. Choose the M3 demo framing, because it determines the circuit's public inputs.

Only then start M1. None of these requires writing a circuit, and all of them are expensive to
revisit once data has been signed.

**Step 1 is done** — see §8. Steps 2 and 3 remain, and neither needs a machine.

---

## 8. M0 results (2026-09-21)

Run on this machine, against the pinned versions. The spike code was throwaway; the numbers and
the findings are what it was for.

### Toolchain

Installed **project-local** rather than globally: `zk/.toolchain/toolbin/`, with the versions
recorded in `zk/.toolchain/versions.txt`. No shell profile was modified and nothing was installed
system-wide, so the pin is per-repo and removing the directory removes the toolchain.

`nargo 1.0.0-rc.3` · `bb 5.2.0` · `@aztec/bb.js 5.2.0`

### Numbers, for a trivial circuit (`assert(x != y)`)

| Step | Time | Output |
|---|---|---|
| `nargo compile` | <1 s | 1.4 KB ACIR |
| `nargo execute` | <1 s | 96 B witness |
| `bb write_vk` | 4.2 s | 3.7 KB vk |
| `bb prove` | 0.58 s | **14.7 KB proof** |
| `bb verify` | 0.03 s | — |

Scheme is `ultra_honk`, 8 threads. **Proof size is constant-ish for UltraHonk**, so ~14 KB is the
floor for anything — worth knowing before designing an API that returns proofs. Proving a real
circuit will be slower than 0.58 s, but the toy case being fast means the hardware is not the
constraint; circuit size will be.

### Three findings

**1. `bb` has no hashing subcommand — the sidecar must be Node.** The CLI offers `prove`,
`write_vk`, `verify`, `check`, `gates`, `proof_stats`, `write_solidity_verifier` and some
Aztec-specific commands. There is no `pedersen`, no `hash`, no `commit`. The plan in §1.2 as
originally written — "wrap `bb`, add a `commit` endpoint" — had nothing to wrap. `@aztec/bb.js`
does expose `pedersenHash`, so the sidecar is a Node service over that. §1.2 is updated.

**2. The M1 acceptance criterion already passes.** The whole plan rests on the sidecar and the
circuit producing the same commitment. Tested directly:

```
pedersen_hash([500000, 42])
  Noir circuit : 0x029e62fbf74abb199f8d996ef3bb8bde9f532a9895a93b55aefb02058fa9b9ce
  @aztec/bb.js : 0x029e62fbf74abb199f8d996ef3bb8bde9f532a9895a93b55aefb02058fa9b9ce
```

Identical. That is the single riskiest assumption in this document, and it holds — keep this
vector as a fixture, because it is exactly the regression test for a future toolchain bump.

Note the API shape: `pedersenHash({ inputs: Uint8Array[], hashIndex: number })`, fields as 32-byte
big-endian buffers. `bb.js` 5.2.0 exports no `Fr` class, so callers do the encoding themselves.

**3. `bb verify` exit codes are trustworthy.** `0` on success, `1` on failure, and a proof with a
single flipped byte is rejected (`verification failed at reduction step`). The sidecar can key
off the exit status rather than parsing stdout — but note the failure is still printed to stdout
rather than stderr, so a naive `bb verify | grep` would read a *failure* as output and succeed.
Use the exit code.

---

*Sources for §0: [Noir releases](https://github.com/noir-lang/noir/releases),
[noir-lang/noir stdlib source](https://github.com/noir-lang/noir/tree/master/noir_stdlib/src),
[aztec-packages releases](https://github.com/AztecProtocol/aztec-packages/releases),
[Noir hash documentation](https://noir-lang.org/docs/noir/standard_library/cryptographic_primitives/hashes).*
