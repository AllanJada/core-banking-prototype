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
}
