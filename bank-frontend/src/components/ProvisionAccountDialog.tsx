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
import { createInstitution, createOverseer } from "../api/client";

type Kind = "INSTITUTION" | "BANK";

interface ProvisionAccountDialogProps {
  open: boolean;
  onClose: () => void;
  /** Called with a confirmation to show once the account exists. */
  onProvisioned: (message: string) => void;
}

const KIND_OPTIONS: { value: Kind; label: string; hint: string }[] = [
  {
    value: "INSTITUTION",
    label: "Institution",
    hint: "A commercial bank. Opens and runs its own customers' accounts; its settlement account is opened with it.",
  },
  {
    value: "BANK",
    label: "Central Bank overseer",
    hint: "Supervises institutions — can license institutions and add overseers.",
  },
];

/**
 * The Central Bank's provisioning: institutions, and other overseers.
 *
 * Customers are deliberately not offered. A customer is opened by their own institution, so
 * the Central Bank has no path to create one — neither here nor in the API.
 */
export default function ProvisionAccountDialog({
  open,
  onClose,
  onProvisioned,
}: ProvisionAccountDialogProps) {
  const [kind, setKind] = useState<Kind>("INSTITUTION");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [institutionCode, setInstitutionCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    setKind("INSTITUTION");
    setUsername("");
    setPassword("");
    setInstitutionCode("");
    setError(null);
  }, [open]);

  async function handleSubmit() {
    if (!username.trim() || !password) {
      setError("A username and password are both required.");
      return;
    }
    if (kind === "INSTITUTION" && !/^[A-Z0-9]{3,8}$/.test(institutionCode.trim())) {
      setError("The institution code must be 3 to 8 letters or digits.");
      return;
    }

    setError(null);
    setSubmitting(true);
    try {
      if (kind === "INSTITUTION") {
        const created = await createInstitution({
          username: username.trim(),
          password,
          institutionCode: institutionCode.trim(),
        });
        onProvisioned(
          `${created.institutionCode} licensed — settlement account ${created.settlementAccountNumber}`
        );
      } else {
        const created = await createOverseer(username.trim(), password);
        onProvisioned(`Overseer ${created.username} created`);
      }
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not create the account");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Provision</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}

          <TextField
            label="Account type"
            select
            value={kind}
            onChange={(e) => setKind(e.target.value as Kind)}
            fullWidth
          >
            {KIND_OPTIONS.map((option) => (
              <MenuItem key={option.value} value={option.value}>
                {option.label}
              </MenuItem>
            ))}
          </TextField>
          <Typography variant="caption" color="text.secondary">
            {KIND_OPTIONS.find((option) => option.value === kind)?.hint}
          </Typography>

          {kind === "INSTITUTION" && (
            <TextField
              label="Institution code"
              value={institutionCode}
              onChange={(e) => setInstitutionCode(e.target.value.toUpperCase())}
              helperText="3–8 letters or digits, e.g. ALPHA"
              slotProps={{ htmlInput: { maxLength: 8 } }}
              fullWidth
              required
            />
          )}
          <TextField
            label="Username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
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
          {submitting ? "Creating…" : kind === "INSTITUTION" ? "License institution" : "Create overseer"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
