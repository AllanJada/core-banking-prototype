# A ML-DSA Bank File Transfer System

Status: work in progress

## Overview

This project is an early-stage system for transferring sensitive files between financial
institutions. It is being built incrementally, starting with a foundational demonstration
of the core workflow: a user signs in, sends a file to another institution, and both
sender and receiver can track the status of that transfer through an inbox and outbox
view.

The current stage of the project intentionally omits several concerns that a production
system would require (real authentication, message signing, encrypted transport between
institutions). These are documented under Roadmap below and are being addressed in later
iterations.

## Tech Stack

### Backend

- Java, Spring Boot
- Spring Web (REST API)
- Spring Data JPA
- Spring Security (used currently for password hashing and CORS configuration only)
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
  controllers     REST endpoints
  dtos            Request and response payloads
  exceptions      Centralized error handling
  models          JPA entities
  repositories    Spring Data JPA repositories
  seed            Startup data seeding for demo users
  services        Business logic
```

The frontend is a single-page application structured as follows:

```
src
  api          Typed fetch wrappers for each backend endpoint
  components   Shared UI components (tables, dialogs, status indicators)
  context      Authentication state
  pages        Login and dashboard views
  types        Shared TypeScript types matching backend DTOs
```

## Current Functionality

- User sign-in against a fixed set of seeded institution accounts, with passwords
  hashed using bcrypt
- File upload from one institution to another over a REST endpoint
- An inbox view, scoped to the signed-in user, listing files sent to them
- An outbox view, scoped to the signed-in user, listing files they have sent and the
  current status of each
- A status lifecycle for each transfer: SENT when uploaded, DOWNLOADED once the
  recipient retrieves it
- Files are stored on disk under a server-generated identifier, never under their
  original client-supplied filename, to avoid path traversal and overwrite issues
- Centralized exception handling, returning structured JSON error responses rather
  than default framework error pages

## Authentication Model (Current Stage)

Authentication is deliberately minimal at this stage. There are no sessions and no
tokens. After a successful login, the frontend holds the authenticated user's ID and
passes it as a request parameter on each subsequent call. The backend uses this ID to
scope inbox, outbox, and download queries to that user.

This is a known and accepted limitation for the current stage of the project. It is
not intended to represent the authentication model of a production system.

## Users

Institution accounts are seeded automatically on backend startup rather than created
through a registration flow, since the current stage does not include user
registration.

## Roadmap

The following items are known gaps and are planned for later iterations, not omissions
from the current stage's intended scope:

- Replacing the current identification scheme with a proper authentication mechanism
  (session-based or token-based)
- Introducing message-level integrity and authenticity using post-quantum digital
  signatures (ML-DSA), independent of the transport layer
- Evaluating transport options for institutions that require file-drop-style
  integration (SFTP) alongside the REST-based approach used for institutions that can
  integrate directly against this API
- Checksum verification for transferred files
- An audit trail covering all status transitions for a given transfer
- Pagination for inbox and outbox views
- Resumable or chunked upload support for large files

## Requirements

- Java 21 or later
- Maven
- Node.js and npm
- PostgreSQL

## Running the Backend

The backend is built and run using Maven. Database connection details and file storage
location are configured in `application.properties`.

## Running the Frontend

```
npm install
npm run dev
```

The frontend expects the backend to be reachable at the URL configured in `.env`
(`VITE_API_BASE_URL`), and the backend's CORS configuration must allow the frontend's
origin.