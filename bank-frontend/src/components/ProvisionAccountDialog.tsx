import { useEffect, useState } from "react";
import {
  Alert,
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
import { createUser } from "../api/client";
import type { Role } from "../types";

interface ProvisionAccountDialogProps {
  open: boolean;
  onClose: () => void;
  onProvisioned: (username: string) => void;
}

const ROLE_OPTIONS: { value: Role; label: string; hint: string }[] = [
  { value: "NORMAL_USER", label: "Customer", hint: "Gets a banking account and can hold a card" },
  { value: "INSTITUTION", label: "Institution", hint: "Exchanges signed documents with other institutions" },
  { value: "BANK", label: "Bank operator", hint: "Oversight only — can provision accounts" },
];

/**
 * Creates an account, the one thing the oversight role can change.
 *
 * The role picker spells out what each choice grants rather than showing the bare enum
 * name, because the difference between them is what the new account will be able to do —
 * and a customer account also causes a banking account to be opened alongside it.
 */
export default function ProvisionAccountDialog({
  open,
  onClose,
  onProvisioned,
}: ProvisionAccountDialogProps) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [role, setRole] = useState<Role>("NORMAL_USER");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    setUsername("");
    setPassword("");
    setRole("NORMAL_USER");
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
      const created = await createUser(username.trim(), password, role);
      onProvisioned(created.username);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not create the account");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Provision account</DialogTitle>
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
          <TextField
            label="Role"
            select
            value={role}
            onChange={(e) => setRole(e.target.value as Role)}
            fullWidth
          >
            {ROLE_OPTIONS.map((option) => (
              <MenuItem key={option.value} value={option.value}>
                {option.label}
              </MenuItem>
            ))}
          </TextField>
          <Typography variant="caption" color="text.secondary">
            {ROLE_OPTIONS.find((option) => option.value === role)?.hint}
          </Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={submitting}>
          Cancel
        </Button>
        <Button variant="contained" onClick={handleSubmit} disabled={submitting}>
          {submitting ? "Creating…" : "Create account"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
