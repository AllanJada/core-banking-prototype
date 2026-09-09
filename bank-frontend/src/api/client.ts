import type {
  Account,
  Page,
  AdminAccount,
  AdminSummary,
  AuthSession,
  Role,
  DebitCard,
  DepositRequest,
  FileTransfer,
  Payment,
  PaymentLink,
  PaymentLinkRequest,
  PaymentRequest,
  Posting,
  SlipRequest,
  User,
} from "../types";
import { loadSession } from "./session";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

interface ApiErrorBody {
  timestamp: string;
  status: number;
  message: string;
}

/**
 * Attaches the bearer token to a request when there is one.
 *
 * Every endpoint except login and account creation requires it — the backend derives
 * the acting account from this token, which is why none of the calls below pass a
 * userId any more.
 */
function authHeaders(extra: HeadersInit = {}): HeadersInit {
  const session = loadSession();
  return session ? { ...extra, Authorization: `Bearer ${session.token}` } : extra;
}

/**
 * Renders the paging query string.
 *
 * Omits absent values so the backend applies its own defaults rather than this deciding
 * them in a second place.
 */
function pageQuery(page?: number, size?: number): string {
  const params = new URLSearchParams();
  if (page !== undefined) params.set("page", String(page));
  if (size !== undefined) params.set("size", String(size));
  const query = params.toString();
  return query ? `?${query}` : "";
}

/**
 * Every backend error comes back as {timestamp, status, message} (see GlobalExceptionHandler
 * and RestAuthenticationErrorHandler). This unwraps that into a plain Error so callers can
 * just read err.message.
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

export async function login(username: string, password: string): Promise<AuthSession> {
  const response = await fetch(`${API_BASE_URL}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  const body = await handleResponse<{ token: string } & User>(response);
  return {
    token: body.token,
    user: { userId: body.userId, username: body.username, role: body.role },
  };
}

/**
 * Confirms the stored token is still accepted, returning who the backend thinks we are.
 * Used on startup so an expired token surfaces as a clean sign-out rather than as a
 * failed request inside an already-rendered dashboard.
 */
export async function getCurrentUser(): Promise<User> {
  const response = await fetch(`${API_BASE_URL}/api/v1/auth/me`, { headers: authHeaders() });
  return handleResponse<User>(response);
}

/** Platform totals for the Bank role's console. */
export async function getAdminSummary(): Promise<AdminSummary> {
  const response = await fetch(`${API_BASE_URL}/api/v1/admin/summary`, { headers: authHeaders() });
  return handleResponse<AdminSummary>(response);
}

/** Every account with its balance and card status. Bank role only. */
export async function getAdminAccounts(): Promise<AdminAccount[]> {
  const response = await fetch(`${API_BASE_URL}/api/v1/admin/accounts`, { headers: authHeaders() });
  return handleResponse<AdminAccount[]>(response);
}

/** Every payment across all accounts, refused ones included. Bank role only. */
export async function getAdminPayments(page?: number, size?: number): Promise<Page<Payment>> {
  const response = await fetch(`${API_BASE_URL}/api/v1/admin/payments${pageQuery(page, size)}`, {
    headers: authHeaders(),
  });
  return handleResponse<Page<Payment>>(response);
}

/** Every file transfer between institutions. Bank role only. */
export async function getAdminTransfers(page?: number, size?: number): Promise<Page<FileTransfer>> {
  const response = await fetch(`${API_BASE_URL}/api/v1/admin/transfers${pageQuery(page, size)}`, {
    headers: authHeaders(),
  });
  return handleResponse<Page<FileTransfer>>(response);
}

/** Provisions an account. Only the Bank role may do this once one exists. */
export async function createUser(
  username: string,
  password: string,
  role: Role
): Promise<User> {
  const response = await fetch(`${API_BASE_URL}/api/v1/users`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify({ username, password, role }),
  });
  return handleResponse<User>(response);
}

/**
 * Every account in the system — an oversight view, so the backend allows it only for the
 * Bank role. Other roles get a 403 rather than a filtered list, on purpose: the ordinary
 * "who can I send to" question is listCounterparties() below.
 */
export async function listAllUsers(): Promise<User[]> {
  const response = await fetch(`${API_BASE_URL}/api/v1/users`, { headers: authHeaders() });
  return handleResponse<User[]>(response);
}

/**
 * The accounts the signed-in user may send to. The backend decides what that means
 * (same-role counterparties, excluding yourself), so the picker can't offer a recipient
 * the API would then refuse.
 */
export async function listCounterparties(): Promise<User[]> {
  const response = await fetch(`${API_BASE_URL}/api/v1/users/counterparties`, {
    headers: authHeaders(),
  });
  return handleResponse<User[]>(response);
}

/** The signed-in customer's own account, with its balance derived server-side. */
export async function getMyAccount(): Promise<Account> {
  const response = await fetch(`${API_BASE_URL}/api/v1/accounts/me`, { headers: authHeaders() });
  return handleResponse<Account>(response);
}

/** The signed-in customer's posting history, newest first. */
export async function getMyPostings(page?: number, size?: number): Promise<Page<Posting>> {
  const response = await fetch(`${API_BASE_URL}/api/v1/accounts/me/postings${pageQuery(page, size)}`, {
    headers: authHeaders(),
  });
  return handleResponse<Page<Posting>>(response);
}

/**
 * Downloads a transfer's ISO 20022 payload.
 *
 * The backend re-verifies the payload against its signed hash before serving it, so a
 * failure here means the payment instruction no longer matches what was signed — not
 * merely that a file was missing.
 */
export async function downloadPayload(
  transferId: number,
  originalFilename: string
): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/v1/files/${transferId}/payload`, {
    headers: authHeaders(),
  });

  if (!response.ok) {
    let message = `Payload download failed (${response.status})`;
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
  link.download = originalFilename.replace(/\.[^.]+$/, "") + ".xml";
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.URL.revokeObjectURL(url);
}

/**
 * Downloads the account statement for a date range as a PDF.
 *
 * Goes through fetch rather than a plain link so the auth header can be attached — the
 * endpoint is protected like every other, and a bare href cannot carry a bearer token.
 */
export async function downloadStatement(from: string, to: string): Promise<void> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/accounts/me/statement?from=${from}&to=${to}`,
    { headers: authHeaders() }
  );

  if (!response.ok) {
    let message = `Could not generate the statement (${response.status})`;
    try {
      const body: ApiErrorBody = await response.json();
      if (body.message) message = body.message;
    } catch {
      // Not JSON — keep the generic message.
    }
    throw new Error(message);
  }

  const blob = await response.blob();
  const url = window.URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `statement-${from}-to-${to}.pdf`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.URL.revokeObjectURL(url);
}

/**
 * Issues a debit card. The response carries the full card number — the only time it is
 * ever returned, so it has to be shown to the customer now.
 */
export async function issueCard(pin: string): Promise<DebitCard> {
  const response = await fetch(`${API_BASE_URL}/api/v1/cards`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify({ pin }),
  });
  return handleResponse<DebitCard>(response);
}

/** The customer's card, masked. Null when none has been issued (the API returns 204). */
export async function getMyCard(): Promise<DebitCard | null> {
  const response = await fetch(`${API_BASE_URL}/api/v1/cards/me`, { headers: authHeaders() });
  if (response.status === 204) return null;
  return handleResponse<DebitCard>(response);
}

export async function changeCardPin(currentPin: string, newPin: string): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/v1/cards/me/change-pin`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify({ currentPin, pin: newPin }),
  });
  await handleResponse<void>(response);
}

export async function blockCard(): Promise<DebitCard> {
  const response = await fetch(`${API_BASE_URL}/api/v1/cards/me/block`, {
    method: "POST",
    headers: authHeaders(),
  });
  return handleResponse<DebitCard>(response);
}

/** Pays money into the signed-in customer's own account. */
export async function deposit(request: DepositRequest): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/v1/payments/deposits`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(request),
  });
  await handleResponse<unknown>(response);
}

/**
 * Sends money to another account.
 *
 * A refusal (insufficient funds, a limit, an unknown recipient) comes back as an error
 * with the reason the backend recorded, not as a successful response with a failed status.
 */
export async function sendPayment(request: PaymentRequest): Promise<Payment> {
  const response = await fetch(`${API_BASE_URL}/api/v1/payments`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(request),
  });
  return handleResponse<Payment>(response);
}

/** Outgoing payments for the signed-in customer, refused attempts included. */
export async function getMyPayments(page?: number, size?: number): Promise<Page<Payment>> {
  const response = await fetch(`${API_BASE_URL}/api/v1/payments${pageQuery(page, size)}`, {
    headers: authHeaders(),
  });
  return handleResponse<Page<Payment>>(response);
}

/** Creates a shareable request for payment into the signed-in customer's account. */
export async function createPaymentLink(request: PaymentLinkRequest): Promise<PaymentLink> {
  const response = await fetch(`${API_BASE_URL}/api/v1/payment-links`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(request),
  });
  return handleResponse<PaymentLink>(response);
}

/** Payment requests the signed-in customer has created. */
export async function getMyPaymentLinks(page?: number, size?: number): Promise<Page<PaymentLink>> {
  const response = await fetch(`${API_BASE_URL}/api/v1/payment-links${pageQuery(page, size)}`, {
    headers: authHeaders(),
  });
  return handleResponse<Page<PaymentLink>>(response);
}

/** Looks up a link so its amount and recipient can be shown before paying. */
export async function getPaymentLink(linkId: string): Promise<PaymentLink> {
  const response = await fetch(`${API_BASE_URL}/api/v1/payment-links/${linkId}`, {
    headers: authHeaders(),
  });
  return handleResponse<PaymentLink>(response);
}

export async function payPaymentLink(linkId: string): Promise<Payment> {
  const response = await fetch(`${API_BASE_URL}/api/v1/payment-links/${linkId}/pay`, {
    method: "POST",
    headers: authHeaders(),
  });
  return handleResponse<Payment>(response);
}

export async function cancelPaymentLink(linkId: string): Promise<PaymentLink> {
  const response = await fetch(`${API_BASE_URL}/api/v1/payment-links/${linkId}/cancel`, {
    method: "POST",
    headers: authHeaders(),
  });
  return handleResponse<PaymentLink>(response);
}

export async function getInbox(page?: number, size?: number): Promise<Page<FileTransfer>> {
  const response = await fetch(`${API_BASE_URL}/api/v1/files/inbox${pageQuery(page, size)}`, {
    headers: authHeaders(),
  });
  return handleResponse<Page<FileTransfer>>(response);
}

export async function getOutbox(page?: number, size?: number): Promise<Page<FileTransfer>> {
  const response = await fetch(`${API_BASE_URL}/api/v1/files/outbox${pageQuery(page, size)}`, {
    headers: authHeaders(),
  });
  return handleResponse<Page<FileTransfer>>(response);
}

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
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(request),
  });
  return handleResponse<FileTransfer>(response);
}

/**
 * Downloads flow through fetch (not a plain <a href>) so we can attach the auth header
 * and reliably trigger a save-as with the original filename.
 */
export async function downloadFile(
  transferId: number,
  originalFilename: string
): Promise<void> {
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
