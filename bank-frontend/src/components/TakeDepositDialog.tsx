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
import { depositForCustomer } from "../api/client";
import type { Customer } from "../types";

interface TakeDepositDialogProps {
  open: boolean;
  customer: Customer | null;
  onClose: () => void;
  onCompleted: (message: string) => void;
}

/**
 * The counter operation: money paid in by a customer, taken by their bank.
 *
 * Deliberately the institution's screen rather than the customer's. A deposit is the only
 * way money enters the ledger, so the account holder cannot be the one who records it —
 * they would be able to credit themselves any amount, and every balance in the system
 * would rest on that.
 *
 * The customer is fixed by whichever row the teller opened this from, so there is no
 * account field to mistype. The backend resolves that customer within the signed-in
 * institution regardless, and refuses one held elsewhere.
 */
export default function TakeDepositDialog({
  open,
  customer,
  onClose,
  onCompleted,
}: TakeDepositDialogProps) {
  const [amount, setAmount] = useState("");
  const [description, setDescription] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    setAmount("");
    setDescription("");
    setError(null);
  }, [open, customer]);

  async function handleSubmit() {
    if (!customer) return;

    const parsedAmount = Number(amount);
    if (!Number.isFinite(parsedAmount) || parsedAmount <= 0) {
      setError("Enter an amount greater than zero.");
      return;
    }

    setError(null);
    setSubmitting(true);
    try {
      const result = await depositForCustomer(customer.userId, {
        amount: parsedAmount,
        description: description.trim(),
      });
      // Reads the new balance back, which is what a teller confirms to the person at the
      // counter — and it comes from the ledger, not from adding to what was on screen.
      onCompleted(`Deposit taken — balance is now ${result.balanceAfter.toLocaleString()}`);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Something went wrong");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Take a deposit</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}

          {customer && (
            <Stack spacing={0.25}>
              <Typography variant="body2" color="text.secondary">
                Paying into
              </Typography>
              <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
                {customer.username}
              </Typography>
              <Typography variant="body2" sx={{ letterSpacing: 1 }}>
                {customer.accountNumber}
              </Typography>
            </Stack>
          )}

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
            label="Description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            fullWidth
            placeholder="Cash in at counter"
          />

          <Typography variant="caption" color="text.secondary">
            This is funded from your institution's cash account, so it moves money rather
            than creating it.
          </Typography>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={submitting}>
          Cancel
        </Button>
        <Button variant="contained" onClick={handleSubmit} disabled={submitting}>
          {submitting ? "Working…" : "Take deposit"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
