export type Role = "NORMAL_USER" | "INSTITUTION" | "BANK";

export interface User {
  userId: number;
  username: string;
  role: Role;
}

/** A signed-in session: who you are, plus the bearer token proving it to the API. */
export interface AuthSession {
  token: string;
  user: User;
}

export interface Account {
  accountNumber: string;
  currency: string;
  /** Derived from the account's postings on each request, never a stored counter. */
  balance: number;
  openedAt: string;
  /** The institution holding the account — the customer banks with exactly one. */
  institutionName: string;
  institutionCode: string;
}

export type PostingDirection = "CREDIT" | "DEBIT";

export interface Posting {
  postingId: number;
  direction: PostingDirection;
  /** Always positive — direction decides whether it raised or lowered the balance. */
  amount: number;
  description: string | null;
  transactionRef: string;
  postedAt: string;
}

/**
 * One page of results, matching the backend's PageResponse.
 *
 * Every list endpoint returns this shape, so a caller never receives an unbounded array
 * even when it asks for no particular page.
 */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

/**
 * An institution as the Central Bank sees it: aggregates only, never the identities or
 * individual balances of its customers.
 */
export interface Institution {
  userId: number;
  username: string;
  institutionCode: string;
  /** The three digits prefixing every account and card number this institution issues. */
  institutionNumber: string;
  settlementAccountNumber: string;
  currency: string;
  customerCount: number;
  /** Sum of this institution's customer balances, derived from the ledger. */
  customerFundsHeld: number;
  /** The settlement account's balance. Negative means a net debtor to the system. */
  settlementPosition: number;
}

export interface InstitutionRequest {
  username: string;
  password: string;
  /** 3–8 letters or digits; stored uppercase and unique. */
  institutionCode: string;
  bic?: string;
}

/** A customer as their own institution sees them — with no secrets. */
export interface Customer {
  userId: number;
  username: string;
  accountNumber: string;
  currency: string;
  balance: number;
  openedAt: string;
  cardStatus: CardStatus | null;
  /** Masked to the last four digits, or null when no card is issued. */
  cardNumber: string | null;
}

/** One institution's standing at the Central Bank. */
export interface SettlementPosition {
  institutionId: number;
  institutionName: string;
  institutionCode: string;
  institutionNumber: string;
  settlementAccountNumber: string;
  /** Negative means a net debtor to the rest of the system. */
  position: number;
  netDebitCap: number;
  /** How much further this institution can settle outwards before refusals start. */
  headroom: number;
}

/**
 * An ISO 20022 pacs.008: the instruction one bank sent another to settle a payment.
 *
 * The XML is fetched separately, so that serving it can re-check its hash and signature
 * first — a list cannot promise what it has not verified.
 */
export interface SettlementMessage {
  messageId: number;
  paymentId: number;
  /** Also the ledger's transaction reference for the payment's four postings. */
  uetr: string;
  messageType: string;
  debtorAgentName: string;
  debtorAgentCode: string;
  creditorAgentName: string;
  creditorAgentCode: string;
  amount: number;
  currency: string;
  xmlHash: string;
  createdAt: string;
  /** SENT or RECEIVED for an institution; null for the Central Bank. */
  direction: "SENT" | "RECEIVED" | null;
}

/** One bank-to-bank movement. Carries no customer identity on either side. */
export interface SettlementMovement {
  paymentId: number;
  /** The pacs.008 instructing it, to fetch it by. */
  messageId: number | null;
  transactionRef: string;
  fromInstitutionName: string;
  fromInstitutionCode: string;
  toInstitutionName: string;
  toInstitutionCode: string;
  amount: number;
  occurredAt: string;
}

export interface Settlement {
  positions: SettlementPosition[];
  /** Invariant I2: always zero, since every movement debits one position and credits another. */
  positionsSum: number;
  balanced: boolean;
  currency: string;
  movements: Page<SettlementMovement>;
}

/** A payment a bank could not settle, with the cause its customer was not given. */
export interface SettlementRefusal {
  paymentId: number;
  institutionName: string;
  institutionCode: string;
  amount: number;
  reason: string;
  detail: string;
  refusedAt: string;
}

/** An institution's own overview, including where it stands at the Central Bank. */
export interface InstitutionSummary {
  institutionId: number;
  institutionName: string;
  institutionCode: string;
  institutionNumber: string;
  currency: string;
  customerCount: number;
  customerFundsHeld: number;
  settlementAccountNumber: string;
  settlementPosition: number;
  netDebitCap: number;
  headroom: number;
}

/** A payment by one of the institution's own customers. */
export interface InstitutionPayment {
  paymentId: number;
  customerUsername: string;
  fromAccountNumber: string;
  toAccountNumber: string | null;
  toInstitutionCode: string | null;
  /** Whether the money had to cross banks — four postings rather than two. */
  interBank: boolean;
  amount: number;
  description: string | null;
  status: PaymentStatus;
  /** The reason as the customer was told it. */
  failureReason: string | null;
  /** The specific cause behind a settlement refusal; never shown to the customer. */
  failureDetail: string | null;
  transactionRef: string | null;
  createdAt: string;
}

export interface AdminSummary {
  customers: number;
  institutions: number;
  bankOperators: number;
  /** Customer accounts only — settlement accounts are institutions' positions. */
  accounts: number;
  /** Sum of every account balance — what the ledger says the platform holds. */
  totalHeld: number;
  currency: string;
  completedPayments: number;
  failedPayments: number;
  completedPaymentVolume: number;
  /** Invariant I2, checked on every read: the positions must sum to zero. */
  settlementPositionsSum: number;
  settlementBalanced: boolean;
  fileTransfers: number;
  transfersWithPayload: number;
}

export type PaymentStatus = "COMPLETED" | "FAILED";

export interface Payment {
  paymentId: number;
  fromAccountNumber: string;
  /** Null when the payment was refused because no such recipient existed. */
  toAccountNumber: string | null;
  /** The receiving bank's code, so a customer can see when money left their own bank. */
  toInstitutionCode: string | null;
  amount: number;
  description: string | null;
  status: PaymentStatus;
  /** Set only on a refused payment, explaining why. */
  failureReason: string | null;
  transactionRef: string | null;
  createdAt: string;
}

export type CardStatus = "ACTIVE" | "BLOCKED" | "EXPIRED";

export interface DebitCard {
  /** Masked everywhere except in the response to issuing the card. */
  cardNumber: string;
  accountNumber: string;
  expiresOn: string;
  /** Derived server-side: a card past its date reports EXPIRED. */
  status: CardStatus;
}

export type PaymentLinkStatus = "PENDING" | "PAID" | "CANCELLED" | "EXPIRED";

export interface PaymentLink {
  linkId: string;
  /** The account that gets paid — shown to whoever is deciding whether to pay. */
  requesterAccountNumber: string;
  amount: number;
  description: string | null;
  /** Derived server-side: a link past its deadline reports EXPIRED, not PENDING. */
  status: PaymentLinkStatus;
  expiresAt: string;
  paidAt: string | null;
  createdAt: string;
}

export interface PaymentLinkRequest {
  amount: number;
  description: string;
  expiresInHours?: number;
}

export interface DepositRequest {
  amount: number;
  description: string;
}

export interface PaymentRequest {
  toAccountNumber: string;
  amount: number;
  description: string;
}

/**
 * A transfer's lifecycle from the recipient's side.
 *
 * SENT is the only status a transfer can be picked up from indirectly — the recipient
 * must move it to APPROVED first. Previewing the document and its payload is allowed at
 * any status; only the final download is gated on having approved it.
 */
export type TransferStatus = "SENT" | "APPROVED" | "REJECTED" | "DOWNLOADED";

export interface FileTransfer {
  transferId: number;
  senderUsername: string;
  receiverUsername: string;
  originalFilename: string;
  status: TransferStatus;
  sentAt: string;
  downloadedAt: string | null;
  fileHash?: string;
  signature?: string;
  signatureValid?: boolean;
  /** End-to-end reference; absent on transfers predating it. */
  uetr?: string;
  /** Whether an ISO 20022 pain.001 payload accompanies the document. */
  hasPayload?: boolean;
  /** When the recipient approved or rejected this transfer; null while still SENT. */
  reviewedAt?: string | null;
  /** Why the recipient rejected this transfer; null unless status is REJECTED. */
  rejectionReason?: string | null;
}

/**
 * What a recipient sees when reviewing a transfer before deciding to approve or reject it.
 *
 * Mirrors the backend's PaymentPreviewResponse exactly, including its resilience: a failed
 * integrity check comes back here as `integrityValid: false` with a reason, not as a
 * rejected request — the review screen has to be able to render the bad case too.
 */
export interface PaymentPreview {
  transferId: number;
  senderUsername: string;
  receiverUsername: string;
  originalFilename: string;
  status: TransferStatus;
  sentAt: string;
  uetr: string | null;
  hasPayload: boolean;
  integrityValid: boolean;
  integrityWarning: string | null;
  payload: PaymentPreviewPayload | null;
}

/** The payment instruction's key fields, read out of the ISO 20022 payload. */
export interface PaymentPreviewPayload {
  debtorName: string;
  debtorAccountNumber: string;
  creditorName: string;
  creditorAccountNumber: string;
  amount: number;
  currency: string;
  executionDate: string;
  remittanceInformation: string | null;
}

export interface SlipLineItem {
  label: string;
  amount: number;
}

/**
 * A postal address in discrete parts, mirroring ISO 20022's PstlAdr.
 *
 * Structured rather than one free-text line because the standard requires the components
 * separately, and splitting a typed-in line back apart afterwards is guesswork. Town and
 * country are the minimum the payment schemes accept.
 */
export interface PostalAddress {
  streetName: string;
  buildingNumber: string;
  postCode: string;
  townName: string;
  /** Two-letter ISO 3166-1 alpha-2 code, not a country name. */
  country: string;
}

export interface SlipRequest {
  // No senderId — the backend takes the sender from the request's token, so a client
  // cannot compose a slip on another institution's behalf.
  receiverId: number;
  title: string;
  organizationName: string;
  organizationAddress: PostalAddress;
  date: string; // yyyy-MM-dd, matches a plain <input type="date"> value directly
  employeeName: string;
  payPeriod: string;
  designation: string;
  workedDays: number;
  department: string;
  payerAccount: string;
  payeeAccount: string;
  beneficiaryBank: string;
  earnings: SlipLineItem[];
  deductions: SlipLineItem[];
  amountInWords: string;
}
