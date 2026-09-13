import { useEffect, useState } from "react";
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import UploadIcon from "@mui/icons-material/UploadFileOutlined";
import VisibilityIcon from "@mui/icons-material/VisibilityOutlined";
import { listCounterparties, sendFile } from "../api/client";
import type { User } from "../types";

interface SendFileDialogProps {
  open: boolean;
  onClose: () => void;
  onSent: () => void;
}

function isPdf(file: File): boolean {
  return file.type === "application/pdf" || file.name.toLowerCase().endsWith(".pdf");
}

export default function SendFileDialog({
  open,
  onClose,
  onSent,
}: SendFileDialogProps) {
  const [recipients, setRecipients] = useState<User[]>([]);
  const [receiverId, setReceiverId] = useState<number | "">("");
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [showPreview, setShowPreview] = useState(false);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    // Reset form state each time the dialog opens
    setReceiverId("");
    setFile(null);
    setError(null);
    setShowPreview(false);

    listCounterparties()
      .then(setRecipients)
      .catch((err) => setError(err instanceof Error ? err.message : "Failed to load recipients"));
  }, [open]);

  // The selected file never leaves the browser at this point — the preview is
  // rendered straight from the local File object via a blob URL, no upload
  // or backend round-trip needed.
  useEffect(() => {
    if (!file || !isPdf(file)) {
      setPreviewUrl(null);
      setShowPreview(false);
      return;
    }
    const url = URL.createObjectURL(file);
    setPreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);

  async function handleSend() {
    if (receiverId === "" || !file) {
      setError("Choose a recipient and a file first.");
      return;
    }
    setError(null);
    setSubmitting(true);
    try {
      await sendFile(receiverId, file);
      onSent();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to send file");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth={showPreview ? "sm" : "xs"}>
      <DialogTitle>Send a file</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}

          <TextField
            select
            label="Recipient"
            value={receiverId}
            onChange={(e) => setReceiverId(Number(e.target.value))}
            fullWidth
          >
            {recipients.map((user) => (
              <MenuItem key={user.userId} value={user.userId}>
                {user.username}
              </MenuItem>
            ))}
          </TextField>

          <Stack direction="row" spacing={1}>
            <Button
              component="label"
              variant="outlined"
              startIcon={<UploadIcon />}
              sx={{ justifyContent: "flex-start", flex: 1 }}
            >
              {file ? file.name : "Choose file"}
              <input
                type="file"
                hidden
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
            </Button>
            {previewUrl && (
              <Button
                variant="outlined"
                startIcon={<VisibilityIcon />}
                onClick={() => setShowPreview((v) => !v)}
              >
                {showPreview ? "Hide" : "Preview"}
              </Button>
            )}
          </Stack>

          {file && (
            <Typography variant="caption" color="text.secondary">
              {(file.size / 1024).toFixed(1)} KB
            </Typography>
          )}

          {showPreview && previewUrl && (
            <Box
              component="iframe"
              src={previewUrl}
              title="PDF preview"
              sx={{
                width: "100%",
                height: 420,
                border: "1px solid",
                borderColor: "divider",
                borderRadius: 1,
              }}
            />
          )}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={submitting}>
          Cancel
        </Button>
        <Box sx={{ flex: 1 }} />
        <Button variant="contained" onClick={handleSend} disabled={submitting}>
          {submitting ? "Sending…" : "Send"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
