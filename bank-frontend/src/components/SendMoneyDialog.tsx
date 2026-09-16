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
import { deposit, sendPayment } from "../api/client";

export type MoneyMovementMode = "deposit" | "payment";

interface MoneyMovementDialogProps {
  open: boolean;
  mode: MoneyMovementMode;
  onClose: () => void;
  onCompleted: (message: string) => void;
}

/**
 * Paying money in, and sending money out.
 *
 * The two share a dialog because the parts that are easy to get wrong — parsing an
 * amount, and surfacing the backend's reason for a refusal — are identical. Only the
 * recipient field differs, since a deposit has no counterparty.
 *
 * The dialog is also the review step: the recipient and amount are on screen when the
 * button is pressed. There is no separate confirmation screen, and none of the checks
 * here are the real ones — the backend re-validates the amount, the balance, and the
 * limits, and is what actually refuses a payment.
 */
export default function MoneyMovementDialog({
  open,
  mode,
  onClose,
  onCompleted,
}: MoneyMovementDialogProps) {
  const [toAccountNumber, setToAccountNumber] = useState("");
  const [amount, setAmount] = useState("");
  const [description, setDescription] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const isPayment = mode === "payment";

  useEffect(() => {
    if (!open) return;
    setToAccountNumber("");
    setAmount("");
    setDescription("");
    setError(null);
  }, [open, mode]);

  async function handleSubmit() {
    const parsedAmount = Number(amount);
    if (!Number.isFinite(parsedAmount) || parsedAmount <= 0) {
      setError("Enter an amount greater than zero.");
      return;
    }
    if (isPayment && !toAccountNumber.trim()) {
      setError("Enter the recipient's account number.");
      return;
    }

    setError(null);
    setSubmitting(true);
    try {
      if (isPayment) {
        await sendPayment({
          toAccountNumber: toAccountNumber.trim(),
          amount: parsedAmount,
          description: description.trim(),
        });
        onCompleted("Payment sent");
      } else {
        await deposit({ amount: parsedAmount, description: description.trim() });
        onCompleted("Deposit received");
      }
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
      <DialogTitle>{isPayment ? "Send money" : "Deposit"}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}

          {isPayment && (
            <TextField
              label="Recipient account number"
              value={toAccountNumber}
              onChange={(e) => setToAccountNumber(e.target.value)}
              autoFocus
              fullWidth
              required
              slotProps={{ htmlInput: { inputMode: "numeric", maxLength: 16 } }}
            />
          )}

          <TextField
            label="Amount"
            type="number"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            autoFocus={!isPayment}
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
          {submitting ? "Working…" : isPayment ? "Send" : "Deposit"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
