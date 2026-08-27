export interface User {
  userId: number;
  username: string;
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
  senderId: number;
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
