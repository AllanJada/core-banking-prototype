import type { FileTransfer, User } from "../types";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

interface ApiErrorBody {
  timestamp: string;
  status: number;
  message: string;
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

export async function login(username: string, password: string): Promise<User> {
  const response = await fetch(`${API_BASE_URL}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  return handleResponse<User>(response);
}

export async function listUsers(): Promise<User[]> {
  const response = await fetch(`${API_BASE_URL}/api/v1/users`);
  return handleResponse<User[]>(response);
}

export async function getInbox(userId: number): Promise<FileTransfer[]> {
  const response = await fetch(`${API_BASE_URL}/api/v1/files/inbox?userId=${userId}`);
  return handleResponse<FileTransfer[]>(response);
}

export async function getOutbox(userId: number): Promise<FileTransfer[]> {
  const response = await fetch(`${API_BASE_URL}/api/v1/files/outbox?userId=${userId}`);
  return handleResponse<FileTransfer[]>(response);
}

export async function sendFile(
  senderId: number,
  receiverId: number,
  file: File
): Promise<FileTransfer> {
  const formData = new FormData();
  formData.append("file", file);
  formData.append("senderId", String(senderId));
  formData.append("receiverId", String(receiverId));

  const response = await fetch(`${API_BASE_URL}/api/v1/files/send`, {
    method: "POST",
    body: formData,
  });
  return handleResponse<FileTransfer>(response);
}

/**
 * Downloads flow through fetch (not a plain <a href>) so we can attach ?userId
 * and reliably trigger a save-as with the original filename.
 */
export async function downloadFile(
  transferId: number,
  userId: number,
  originalFilename: string
): Promise<void> {
  const response = await fetch(
    `${API_BASE_URL}/api/v1/files/${transferId}/download?userId=${userId}`
  );

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
