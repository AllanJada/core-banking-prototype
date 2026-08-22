# Secure File Transfer — Frontend

React + TypeScript + Vite + MUI, talking to the Spring Boot backend (`mldsa`) over REST.

## Setup

```bash
npm install
```

Check `.env` — `VITE_API_BASE_URL` should point at your running backend (defaults to
`http://localhost:8080`).

## Run

```bash
npm run dev
```

Opens on http://localhost:5173 (this must match the CORS origin allowed in the backend's
`SecurityConfig`).

## Demo login

Users are seeded by the backend's `DemoDataSeeder` on first startup:

| username    | password     |
|-------------|--------------|
| bank-alpha  | password123  |
| bank-beta   | password123  |
| bank-gamma  | password123  |

## What's here

- `src/api/client.ts` — typed fetch wrappers for every backend endpoint (login, inbox,
  outbox, send, download). All backend errors come back as `{message}` JSON and are
  surfaced as plain `Error`s here.
- `src/context/AuthContext.tsx` — holds the logged-in user (id + username only, no
  token — matches the demo's "pass userId per request" auth model). Persisted to
  `localStorage` so a page refresh doesn't log you out.
- `src/pages/LoginPage.tsx` — sign-in form.
- `src/pages/DashboardPage.tsx` — inbox/outbox tabs + "Send file" action.
- `src/components/SendFileDialog.tsx` — recipient picker (pulled from `GET /api/v1/users`)
  + file picker.
- `src/components/TransferTable.tsx` / `StatusChip.tsx` — shared table and status-chip
  rendering for both inbox and outbox.
- `src/theme.ts` — MUI theme: navy/teal/amber palette, IBM Plex Sans/Mono pairing (the
  mono face is used specifically on data cells — filenames, timestamps — so the tables
  read like a ledger).

## Verified before delivery

- `npx tsc --noEmit` — no type errors
- `npm run build` — production build succeeds
- `npm run lint` (oxlint) — 0 errors, 2 non-blocking style warnings

## Known gaps (by design, matching the demo's agreed scope)

- No real auth — `userId` is passed on every request, no session/token. Anyone who
  guesses another user's id can see their inbox/outbox. Fine for a 3-user demo, not
  fine beyond it.
- No pagination — inbox/outbox load everything in one call.
- No file type/size validation client-side (backend enforces a 50MB cap).
