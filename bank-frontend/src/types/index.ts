// INSTITUTION accounts are the original file-transfer/slip-composing accounts;
// BANK is the newer account type added by the bank_dashboard feature.
export type UserType = "INSTITUTION" | "BANK";

export interface User {
  userId: number;
  username: string;
  userType: UserType;
}

// What POST /api/v1/auth/login returns: the User fields plus the bearer token the frontend
// must now attach (Authorization: Bearer <token>) to every other request — see api/client.ts's
// setAuthToken and AuthContext.tsx. Mirrors the backend's AuthResponse DTO.
export interface AuthResult extends User {
  token: string;
}

export type TransferStatus = "SENT" | "DOWNLOADED";

export interface FileTransfer {
  transferId: number;
  senderUsername: string;
  receiverUsername: string;
  originalFilename: string;
  status: TransferStatus;
  sentAt: string;
  downloadedAt: string | null;
  // Present now that the backend signs every transfer, but not yet shown anywhere
  // in the UI — typed here for accuracy, not currently rendered.
  fileHash?: string;
  signature?: string;
  signatureValid?: boolean;
}

export interface SlipLineItem {
  label: string;
  amount: number;
}

export interface SlipRequest {
  // senderId was removed — the backend now derives the sender from the caller's bearer
  // token (see SlipController), the same fix applied to sendFile/getInbox/getOutbox/
  // downloadFile. receiverId stays: who to send *to* is still the client's choice.
  receiverId: number;
  title: string;
  organizationName: string;
  organizationAddress: string;
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
