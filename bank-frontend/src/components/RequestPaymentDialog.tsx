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
import { createPaymentLink } from "../api/client";
import type { PaymentLink } from "../types";

interface RequestPaymentDialogProps {
  open: boolean;
  onClose: () => void;
  onCreated: () => void;
}

/** The URL to share for a link, built from wherever this app is being served. */
function shareUrlFor(link: PaymentLink): string {
  return `${window.location.origin}/pay/${link.linkId}`;
}

/**
 * Creates a payment request and then shows the link to share.
 *
 * Two steps in one dialog on purpose: a created link is useless until it has been copied
 * somewhere, so closing straight after creating it would be the one moment the customer
 * most needs the URL.
 */
export default function RequestPaymentDialog({
  open,
  onClose,
  onCreated,
}: RequestPaymentDialogProps) {
  const [amount, setAmount] = useState("");
  const [description, setDescription] = useState("");
  const [expiresInHours, setExpiresInHours] = useState("24");
  const [created, setCreated] = useState<PaymentLink | null>(null);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    setAmount("");
    setDescription("");
    setExpiresInHours("24");
    setCreated(null);
    setCopied(false);
    setError(null);
  }, [open]);

  async function handleCreate() {
    const parsedAmount = Number(amount);
    if (!Number.isFinite(parsedAmount) || parsedAmount <= 0) {
      setError("Enter an amount greater than zero.");
      return;
    }
    const parsedHours = Number(expiresInHours);
    if (!Number.isInteger(parsedHours) || parsedHours <= 0) {
      setError("Enter how many hours the request stays open, as a whole number.");
      return;
    }

    setError(null);
    setSubmitting(true);
    try {
      const link = await createPaymentLink({
        amount: parsedAmount,
        description: description.trim(),
        expiresInHours: parsedHours,
      });
      setCreated(link);
      onCreated();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not create the request");
    } finally {
      setSubmitting(false);
    }
  }

  async function handleCopy() {
    if (!created) return;
    try {
      await navigator.clipboard.writeText(shareUrlFor(created));
      setCopied(true);
    } catch {
      // Clipboard access can be refused (no permission, or a non-secure origin). The URL
      // is on screen and selectable either way, so this is not worth an error banner.
      setCopied(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>{created ? "Share this request" : "Request payment"}</DialogTitle>
      <DialogContent>
        {created ? (
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Alert severity="success">Payment request created.</Alert>
            <Typography variant="body2" color="text.secondary">
              Anyone signed in who opens this link can pay it, once.
            </Typography>
            <TextField
              value={shareUrlFor(created)}
              fullWidth
              slotProps={{ htmlInput: { readOnly: true } }}
              onFocus={(e) => e.target.select()}
            />
          </Stack>
        ) : (
          <Stack spacing={2} sx={{ mt: 1 }}>
            {error && <Alert severity="error">{error}</Alert>}
            <TextField
              label="Amount"
              type="number"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              autoFocus
              fullWidth
              required
              slotProps={{ htmlInput: { min: 0, step: 0.01 } }}
            />
            <TextField
              label="What is it for"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              fullWidth
            />
            <TextField
              label="Stays open for (hours)"
              type="number"
              value={expiresInHours}
              onChange={(e) => setExpiresInHours(e.target.value)}
              fullWidth
              slotProps={{ htmlInput: { min: 1, step: 1 } }}
            />
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        {created ? (
          <>
            <Button onClick={handleCopy}>{copied ? "Copied" : "Copy link"}</Button>
            <Button variant="contained" onClick={onClose}>
              Done
            </Button>
          </>
        ) : (
          <>
            <Button onClick={onClose} disabled={submitting}>
              Cancel
            </Button>
            <Button variant="contained" onClick={handleCreate} disabled={submitting}>
              {submitting ? "Creating…" : "Create request"}
            </Button>
          </>
        )}
      </DialogActions>
    </Dialog>
  );
}
