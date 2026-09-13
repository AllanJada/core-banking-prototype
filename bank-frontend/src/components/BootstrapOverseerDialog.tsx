import { useEffect, useState } from "react";
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { bootstrapOverseer } from "../api/client";

interface BootstrapOverseerDialogProps {
  open: boolean;
  onClose: () => void;
  onCreated: (username: string) => void;
}

/**
 * First-time setup: creates the Central Bank overseer on an empty system.
 *
 * Offered only while the backend reports setup as open — and the backend refuses it once any
 * overseer exists regardless, so this dialog is a convenience rather than the guard.
 */
export default function BootstrapOverseerDialog({
  open,
  onClose,
  onCreated,
}: BootstrapOverseerDialogProps) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    setUsername("");
    setPassword("");
    setError(null);
  }, [open]);

  async function handleSubmit() {
    if (!username.trim() || !password) {
      setError("A username and password are both required.");
      return;
    }

    setError(null);
    setSubmitting(true);
    try {
      const created = await bootstrapOverseer(username.trim(), password);
      onCreated(created.username);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not create the overseer");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Set up the Central Bank</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <Typography variant="body2" color="text.secondary">
            Creates the first Central Bank overseer. Once it exists this setup closes, and
            every other account is created from inside the system.
          </Typography>
          <TextField
            label="Username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoFocus
            fullWidth
            required
          />
          <TextField
            label="Password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            fullWidth
            required
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={submitting}>
          Cancel
        </Button>
        <Button variant="contained" onClick={handleSubmit} disabled={submitting}>
          {submitting ? "Creating…" : "Create overseer"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
