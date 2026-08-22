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
import { listUsers, sendFile } from "../api/client";
import type { User } from "../types";

interface SendFileDialogProps {
  open: boolean;
  onClose: () => void;
  onSent: () => void;
  currentUser: User;
}

export default function SendFileDialog({
  open,
  onClose,
  onSent,
  currentUser,
}: SendFileDialogProps) {
  const [recipients, setRecipients] = useState<User[]>([]);
  const [receiverId, setReceiverId] = useState<number | "">("");
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    // Reset form state each time the dialog opens
    setReceiverId("");
    setFile(null);
    setError(null);

    listUsers()
      .then((users) => setRecipients(users.filter((u) => u.userId !== currentUser.userId)))
      .catch((err) => setError(err instanceof Error ? err.message : "Failed to load recipients"));
  }, [open, currentUser.userId]);

  async function handleSend() {
    if (receiverId === "" || !file) {
      setError("Choose a recipient and a file first.");
      return;
    }
    setError(null);
    setSubmitting(true);
    try {
      await sendFile(currentUser.userId, receiverId, file);
      onSent();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to send file");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs">
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

          <Button
            component="label"
            variant="outlined"
            startIcon={<UploadIcon />}
            sx={{ justifyContent: "flex-start" }}
          >
            {file ? file.name : "Choose file"}
            <input
              type="file"
              hidden
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
          </Button>
          {file && (
            <Typography variant="caption" color="text.secondary">
              {(file.size / 1024).toFixed(1)} KB
            </Typography>
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
