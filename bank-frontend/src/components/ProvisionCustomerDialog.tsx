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
import { createCustomer } from "../api/client";
import type { Customer } from "../types";

interface ProvisionCustomerDialogProps {
  open: boolean;
  onClose: () => void;
  onProvisioned: (customer: Customer) => void;
}

/**
 * Opens a customer at the signed-in institution.
 *
 * There is no institution to choose: the backend takes it from the session's token, so an
 * institution can only ever create customers of its own.
 */
export default function ProvisionCustomerDialog({
  open,
  onClose,
  onProvisioned,
}: ProvisionCustomerDialogProps) {
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
      onProvisioned(await createCustomer(username.trim(), password));
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not create the customer");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Provision customer</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}

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
          <Typography variant="caption" color="text.secondary">
            The customer's account is opened at your institution at the same time.
          </Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={submitting}>
          Cancel
        </Button>
        <Button variant="contained" onClick={handleSubmit} disabled={submitting}>
          {submitting ? "Creating…" : "Create customer"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
