import type {
  Account,
  Page,
  AdminSummary,
  AuthSession,
  Customer,
  DebitCard,
  DepositRequest,
  FileTransfer,
  Institution,
  InstitutionPayment,
  InstitutionRequest,
  InstitutionSummary,
  Payment,
  PaymentLink,
  PaymentLinkRequest,
  PaymentPreview,
  PaymentRequest,
  Posting,
  Settlement,
  SettlementRefusal,
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
 * Every endpoint except login and first-time setup requires it — the backend derives
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

/** Institutions with their aggregates — never their customers. Bank role only. */
export async function getAdminInstitutions(page?: number, size?: number): Promise<Page<Institution>> {
  const response = await fetch(`${API_BASE_URL}/api/v1/admin/institutions${pageQuery(page, size)}`, {
    headers: authHeaders(),
  });
  return handleResponse<Page<Institution>>(response);
}

/**
 * Settlement positions and the bank-to-bank movements behind them. Bank role only.
 *
 * This replaced the earlier every-payment listing: the Central Bank supervises institutions,
 * so it sees banks moving money between each other, never one customer paying another.
 */
export async function getAdminSettlement(page?: number, size?: number): Promise<Settlement> {
  const response = await fetch(`${API_BASE_URL}/api/v1/admin/settlement${pageQuery(page, size)}`, {
    headers: authHeaders(),
  });
  return handleResponse<Settlement>(response);
}

/** Payments a bank could not settle, with the cause its customer was not given. */
export async function getAdminSettlementRefusals(
  page?: number,
  size?: number
): Promise<Page<SettlementRefusal>> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/admin/settlement/refusals${pageQuery(page, size)}`,
    { headers: authHeaders() }
  );
  return handleResponse<Page<SettlementRefusal>>(response);
}

/** The signed-in institution's own aggregates and settlement position. */
export async function getInstitutionSummary(): Promise<InstitutionSummary> {
  const response = await fetch(`${API_BASE_URL}/api/v1/institution/summary`, {
    headers: authHeaders(),
  });
  return handleResponse<InstitutionSummary>(response);
}

/** Payments by the signed-in institution's own customers, refused ones included. */
export async function getInstitutionPayments(
  page?: number,
  size?: number
): Promise<Page<InstitutionPayment>> {
  const response = await fetch(`${API_BASE_URL}/api/v1/institution/payments${pageQuery(page, size)}`, {
    headers: authHeaders(),
  });
  return handleResponse<Page<InstitutionPayment>>(response);
}

/** Every file transfer between institutions. Bank role only. */
export async function getAdminTransfers(page?: number, size?: number): Promise<Page<FileTransfer>> {
  const response = await fetch(`${API_BASE_URL}/api/v1/admin/transfers${pageQuery(page, size)}`, {
    headers: authHeaders(),
  });
  return handleResponse<Page<FileTransfer>>(response);
}

/** Whether the system still has no Central Bank overseer, so first-time setup is open. */
export async function getBootstrapStatus(): Promise<{ open: boolean }> {
  const response = await fetch(`${API_BASE_URL}/api/v1/users/bootstrap`);
  return handleResponse<{ open: boolean }>(response);
}

/**
 * Creates the first Central Bank overseer on an empty system. Needs no token; the backend
 * refuses it once any overseer exists.
 */
export async function bootstrapOverseer(username: string, password: string): Promise<User> {
  const response = await fetch(`${API_BASE_URL}/api/v1/users`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password, role: "BANK" }),
  });
  return handleResponse<User>(response);
}

/** Licenses an institution; its settlement account is opened with it. Bank role only. */
export async function createInstitution(request: InstitutionRequest): Promise<Institution> {
  const response = await fetch(`${API_BASE_URL}/api/v1/admin/institutions`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(request),
  });
  return handleResponse<Institution>(response);
}

/** Adds another Central Bank overseer. Bank role only. */
export async function createOverseer(username: string, password: string): Promise<User> {
  const response = await fetch(`${API_BASE_URL}/api/v1/admin/overseers`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify({ username, password }),
  });
  return handleResponse<User>(response);
}

/**
 * Creates a customer, and opens their account, at the signed-in institution. There is no
 * institution parameter: the backend takes it from the token.
 */
export async function createCustomer(username: string, password: string): Promise<Customer> {
  const response = await fetch(`${API_BASE_URL}/api/v1/institution/customers`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify({ username, password }),
  });
  return handleResponse<Customer>(response);
}

/** The signed-in institution's own customers, newest first. */
export async function getMyCustomers(page?: number, size?: number): Promise<Page<Customer>> {
  const response = await fetch(`${API_BASE_URL}/api/v1/institution/customers${pageQuery(page, size)}`, {
    headers: authHeaders(),
  });
  return handleResponse<Page<Customer>>(response);
}

/**
 * Unblocks a customer's card. The backend looks the customer up within the signed-in
 * institution only, so another bank's customer comes back as not found.
 */
export async function unblockCustomerCard(customerId: number): Promise<Customer> {
  const response = await fetch(`${API_BASE_URL}/api/v1/institution/customers/${customerId}/card/unblock`, {
    method: "POST",
    headers: authHeaders(),
  });
  return handleResponse<Customer>(response);
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
/** The structured review summary for a transfer — never throws on a failed integrity check. */
export async function previewTransfer(transferId: number): Promise<PaymentPreview> {
  const response = await fetch(`${API_BASE_URL}/api/v1/files/${transferId}/preview`, {
    headers: authHeaders(),
  });
  return handleResponse<PaymentPreview>(response);
}

/**
 * The document itself, as a blob rather than triggering a save-as — used to render it
 * inline in the review dialog. A plain `<iframe src="...">` can't carry the Authorization
 * header this endpoint requires, so the bytes are fetched here and turned into an object
 * URL the caller is responsible for revoking once the dialog closes.
 */
export async function fetchPreviewDocument(transferId: number): Promise<Blob> {
  const response = await fetch(`${API_BASE_URL}/api/v1/files/${transferId}/preview/document`, {
    headers: authHeaders(),
  });
  if (!response.ok) {
    let message = `Could not load the document (${response.status})`;
    try {
      const body: ApiErrorBody = await response.json();
      if (body.message) message = body.message;
    } catch {
      // fall back to generic message
    }
    throw new Error(message);
  }
  return response.blob();
}

/** Accepts a transfer, the decision that makes it downloadable. */
export async function approveTransfer(transferId: number): Promise<FileTransfer> {
  const response = await fetch(`${API_BASE_URL}/api/v1/files/${transferId}/approve`, {
    method: "POST",
    headers: authHeaders(),
  });
  return handleResponse<FileTransfer>(response);
}

/** Refuses a transfer. Terminal, and requires a reason the sender can act on. */
export async function rejectTransfer(transferId: number, reason: string): Promise<FileTransfer> {
  const response = await fetch(`${API_BASE_URL}/api/v1/files/${transferId}/reject`, {
    method: "POST",
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify({ reason }),
  });
  return handleResponse<FileTransfer>(response);
}

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
