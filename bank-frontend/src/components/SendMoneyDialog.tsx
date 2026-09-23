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
} from "@mui/material";
import { sendPayment } from "../api/client";

interface SendMoneyDialogProps {
  open: boolean;
  onClose: () => void;
  onCompleted: (message: string) => void;
}

/**
 * Sending money out of the signed-in customer's account.
 *
 * This was once a two-mode dialog that also took deposits. Paying money in moved to the
 * counter — see TakeDepositDialog — because an account holder who can credit their own
 * account can create money, so the mode went with it.
 *
 * The dialog is also the review step: the recipient and amount are on screen when the
 * button is pressed. There is no separate confirmation screen, and none of the checks
 * here are the real ones — the backend re-validates the amount, the balance, and the
 * limits, and is what actually refuses a payment.
 */
export default function SendMoneyDialog({ open, onClose, onCompleted }: SendMoneyDialogProps) {
  const [toAccountNumber, setToAccountNumber] = useState("");
  const [amount, setAmount] = useState("");
  const [description, setDescription] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    setToAccountNumber("");
    setAmount("");
    setDescription("");
    setError(null);
  }, [open]);

  async function handleSubmit() {
    const parsedAmount = Number(amount);
    if (!Number.isFinite(parsedAmount) || parsedAmount <= 0) {
      setError("Enter an amount greater than zero.");
      return;
    }
    if (!toAccountNumber.trim()) {
      setError("Enter the recipient's account number.");
      return;
    }

    setError(null);
    setSubmitting(true);
    try {
      await sendPayment({
        toAccountNumber: toAccountNumber.trim(),
        amount: parsedAmount,
        description: description.trim(),
      });
      onCompleted("Payment sent");
      onClose();
    } catch (err) {
      // Carries the backend's own wording — "Insufficient funds", a limit, or an unknown
      // recipient — rather than a generic failure message.
      setError(err instanceof Error ? err.message : "Something went wrong");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Send money</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}

          <TextField
            label="Recipient account number"
            value={toAccountNumber}
            onChange={(e) => setToAccountNumber(e.target.value)}
            autoFocus
            fullWidth
            required
            slotProps={{ htmlInput: { inputMode: "numeric", maxLength: 16 } }}
          />

          <TextField
            label="Amount"
            type="number"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            fullWidth
            required
            slotProps={{ htmlInput: { min: 0, step: 0.01 } }}
          />

          <TextField
            label="Description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            fullWidth
          />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={submitting}>
          Cancel
        </Button>
        <Button variant="contained" onClick={handleSubmit} disabled={submitting}>
          {submitting ? "Working…" : "Send"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
