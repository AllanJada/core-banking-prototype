# Secure Interbank File Transfer System

Status: work in progress

## Overview

This project is a system for transferring sensitive files between financial
institutions. It began as a foundational demonstration of the core workflow and has
since grown to include cryptographic signing, server-generated documents, and two
distinct account types with separate portals.

The current stage still intentionally omits several concerns a production system
would require (real session or token based authentication, true non-repudiation, and
confidentiality of stored files). These are documented under Roadmap below.

## Tech Stack

### Backend

- Java, Spring Boot
- Spring Web (REST API)
- Spring Data JPA
- Spring Security (password hashing and CORS configuration)
- Bouncy Castle (`bcprov-jdk18on`, ML-DSA-65 digital signatures)
- Thymeleaf (HTML templating for generated documents)
- Microsoft Playwright for Java (headless Chromium PDF rendering)
- PostgreSQL
- Lombok
- Maven

### Frontend

- React
- TypeScript
- Vite
- React Router
- Material UI (MUI)

### Database

- PostgreSQL, accessed through Spring Data JPA with Hibernate's schema auto-update
  enabled for this stage of development

## Architecture

The backend follows a feature-sliced, layered structure:

```
org.learning.mldsa
  configs        Security and CORS configuration
  controllers    REST endpoints (auth, users, file transfers, slip generation)
  dtos            Request and response payloads
  exceptions      Centralized error handling
  models          JPA entities
  repositories    Spring Data JPA repositories
  services        Business logic, cryptography, PDF generation
resources/templates  Thymeleaf template for generated payslips
```

The frontend is a single-page application structured as follows:

```
src
  api          Typed fetch wrappers for each backend endpoint
  components   Shared UI components (tables, dialogs, status chips, nav)
  context      Authentication state
  pages        Login, bank login, institution dashboard, bank dashboard
  types        Shared TypeScript types matching backend DTOs
```

## Current Functionality

### Accounts and Access

- Two distinct account types: institution accounts and bank accounts, each with
  their own login route and dashboard
- Institution accounts sign in at the main login screen and land on a tabbed
  dashboard (Inbox / Outbox)
- Bank accounts sign in through a separate "Bank Login" route, reachable from a
  hamburger menu on the main login screen, and land on a sidebar-navigated
  dashboard (Inbox / Outbox / Compose)
- Accounts are created through the API (no self-service registration screen exists
  yet); passwords are hashed with bcrypt
- Every account is provisioned with its own ML-DSA-65 key pair at creation time

### File Transfer

- Files can be sent two ways: composing a structured payslip that the server
  generates as a signed PDF (see below), which is currently the only supported way
  to send a file. A previous raw file-upload mechanism was removed from both
  dashboards in favor of this generate-and-sign flow, specifically to prevent a
  file's contents from being altered between creation and sending
- An inbox view, scoped to the signed-in account, listing files sent to it
- An outbox view, scoped to the signed-in account, listing files it has sent and
  each one's current status
- A status lifecycle for each transfer: SENT when created, DOWNLOADED once the
  recipient retrieves it
- Files are stored on disk under a server-generated identifier, never under their
  original or claimed filename

### Cryptographic Signing

- Every file transfer is signed with ML-DSA-65 (a post-quantum digital signature
  algorithm, FIPS 204) at the moment it is sent, using the sending account's own
  private key
- The file's SHA-384 hash, sender, receiver, filename, and timestamp are bound
  together into a signed envelope, not just the file content alone
- On download, the system independently rehashes the file as it currently exists on
  disk and re-verifies the signature against the sender's stored public key before
  serving it. A failed check is recorded and the download is refused
- The inbox and outbox both display the signature status of each transfer, honestly
  distinguishing a file that has been signed but not yet checked (SIGNED) from one
  that has actually been re-verified on download (VERIFIED) or has failed
  verification (INVALID)

### Server-Generated Documents

- A "Compose" flow lets a user fill in structured payslip fields (employee details,
  earnings, deductions, accounts) rather than uploading a file directly
- The backend renders this into a PDF using a Thymeleaf template and a headless
  Chromium instance (Playwright), then signs the exact rendered bytes in the same
  request, so there is no point at which the document's contents could be swapped
  before signing
- The frontend can preview a locally selected PDF (for direct uploads, where still
  applicable to older data) before it is sent, using the browser's native PDF
  renderer

### Error Handling

- Centralized exception handling returns structured JSON error responses rather
  than default framework error pages

## Authentication Model (Current Stage)

Authentication is deliberately minimal at this stage. There are no sessions and no
tokens. After a successful login, the frontend holds the authenticated account's ID
and type, and passes the ID as a request parameter on each subsequent call. The
backend uses this ID to scope inbox, outbox, and download queries to that account.
Recipient pickers are filtered client side to accounts of the same type (an
institution only sees other institutions, a bank only sees other banks); this is not
enforced by the backend.

This is a known and accepted limitation for the current stage of the project. It is
not intended to represent the authentication model of a production system.

## Key Custody (Current Stage)

Every account's ML-DSA-65 private key is generated at account creation and stored
directly in the database alongside its public key. This means the server holds both
halves of every account's key pair. Signing proves a file was not altered after this
server processed it, but it does not yet provide genuine non-repudiation between two
institutions that do not trust the same central server, since the server itself is in
a position to have signed on any account's behalf. This is a known, documented
limitation, not an oversight, and is the primary item at the top of the roadmap below.

## Roadmap

The following items have been researched but are not yet implemented. They are
ordered roughly by dependency: earlier items are prerequisites for later ones, not
just easier.

- Routing private key storage through a KMS or Vault, so the raw key material never
  sits in a plaintext database column. This is treated as the prerequisite for
  everything else below
- Generating an ISO 20022 compliant XML payload (`pain.001`) alongside the PDF for
  each slip, validated against the official schema before signing
- ML-KEM (post-quantum key encapsulation, FIPS 203) for at-rest encryption of stored
  files, and separately, as part of a TLS hybrid key exchange at the transport layer
- Zero-knowledge proofs, starting with a client-side proof of private key possession
  (which would close the non-repudiation gap described above by keeping the private
  key off this server entirely), followed by range proofs and eventually selective
  disclosure of individual slip fields without revealing the full document
- Pagination for inbox and outbox views
- A checksum-and-audit trail beyond the signature fields already present

Full detail on each of these is maintained separately in the project's research
documents rather than duplicated here.

## Requirements

- Java 21 or later
- Maven
- Node.js and npm
- PostgreSQL
- A Bouncy Castle provider (`bcprov-jdk18on`, 1.78 or later) for ML-DSA-65 support
- Google Chromium, installed via Playwright's own installer, for PDF generation

## Running the Backend

The backend is built and run using Maven. Database connection details and file
storage location are configured in `application.properties`. On first run after
adding the PDF generation dependency, Playwright's Chromium browser must be installed
separately; it is not bundled with the Maven dependency itself.

## Running the Frontend

```
npm install
npm run dev
```

The frontend expects the backend to be reachable at the URL configured in `.env`
(`VITE_API_BASE_URL`), and the backend's CORS configuration must allow the frontend's
origin.
