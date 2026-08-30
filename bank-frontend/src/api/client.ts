import type { AuthResult, FileTransfer, SlipRequest, User } from "../types";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

interface ApiErrorBody {
  timestamp: string;
  status: number;
  message: string;
}

// Set by AuthContext — synchronously, on module load and on login()/logout(), never from a
// React effect (see that file's comment for why: an effect can run after a just-mounted
// page's own first-request effect, which would send that request with no token attached). A
// plain module-level variable is enough since this app only ever has one signed-in user per
// browser tab.
let authToken: string | null = null;

export function setAuthToken(token: string | null): void {
  authToken = token;
}

function authHeaders(): Record<string, string> {
  return authToken ? { Authorization: `Bearer ${authToken}` } : {};
}

/**
 * Every backend error comes back as {timestamp, status, message} (see GlobalExceptionHandler).
 * This unwraps that into a plain Error so callers can just read err.message.
 */
async function handleResponse<T>(response: Response): Promise<T> {
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try {
      const body: ApiErrorBody = await response.json();
      if (body.message) {
        message = body.message;
      }
    } catch {
      // Response wasn't JSON — fall back to the generic message above.
    }
    throw new Error(message);
  }

  // Downloads return raw bytes, not JSON — callers use fetch directly for those.
  const text = await response.text();
  return text ? (JSON.parse(text) as T) : (undefined as T);
}

export async function login(username: string, password: string): Promise<AuthResult> {
  const response = await fetch(`${API_BASE_URL}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  return handleResponse<AuthResult>(response);
}

// Requires a bearer token now (SecurityConfig no longer permits this endpoint anonymously) —
// every caller of listUsers() today is already inside a logged-in screen, so this only ever
// tightens what was previously true anyway.
export async function listUsers(): Promise<User[]> {
  const response = await fetch(`${API_BASE_URL}/api/v1/users`, {
    headers: authHeaders(),
  });
  return handleResponse<User[]>(response);
}

// No userId parameter — the backend reads the caller's own id from the bearer token attached
// by authHeaders() instead of a client-supplied value (see FileTransferController).
export async function getInbox(): Promise<FileTransfer[]> {
  const response = await fetch(`${API_BASE_URL}/api/v1/files/inbox`, {
    headers: authHeaders(),
  });
  return handleResponse<FileTransfer[]>(response);
}

export async function getOutbox(): Promise<FileTransfer[]> {
  const response = await fetch(`${API_BASE_URL}/api/v1/files/outbox`, {
    headers: authHeaders(),
  });
  return handleResponse<FileTransfer[]>(response);
}

// No senderId parameter — see the comment on getInbox() above; the same change applies to
// every endpoint that used to trust a client-supplied identity.
export async function sendFile(receiverId: number, file: File): Promise<FileTransfer> {
  const formData = new FormData();
  formData.append("file", file);
  formData.append("receiverId", String(receiverId));

  const response = await fetch(`${API_BASE_URL}/api/v1/files/send`, {
    method: "POST",
    headers: authHeaders(),
    body: formData,
  });
  return handleResponse<FileTransfer>(response);
}

/**
 * Generates and sends a slip in one call — the backend renders the PDF server-side
 * from these fields and signs the exact resulting bytes, so unlike sendFile() there's
 * no local File object and nothing to preview client-side beforehand.
 */
export async function sendSlip(request: SlipRequest): Promise<FileTransfer> {
  const response = await fetch(`${API_BASE_URL}/api/v1/slips/send`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...authHeaders() },
    body: JSON.stringify(request),
  });
  return handleResponse<FileTransfer>(response);
}

/**
 * Downloads flow through fetch (not a plain <a href>) so we can attach the auth header and
 * reliably trigger a save-as with the original filename.
 */
export async function downloadFile(transferId: number, originalFilename: string): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/v1/files/${transferId}/download`, {
    headers: authHeaders(),
  });

  if (!response.ok) {
    let message = `Download failed (${response.status})`;
    try {
      const body: ApiErrorBody = await response.json();
      if (body.message) message = body.message;
    } catch {
      // fall back to generic message
    }
    throw new Error(message);
  }

  const blob = await response.blob();
  const url = window.URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = originalFilename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.URL.revokeObjectURL(url);
}
