import { useCallback, useEffect, useState } from "react";
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { blockCard, changeCardPin, getMyCard, issueCard } from "../api/client";
import SectionCard from "./SectionCard";
import { dataFontFamily } from "../theme";
import type { DebitCard } from "../types";

const STATUS_LABELS: Record<DebitCard["status"], string> = {
  ACTIVE: "Active",
  BLOCKED: "Blocked",
  EXPIRED: "Expired",
};

/** A four-digit PIN field. The value is held only as long as the dialog is open. */
function PinField({
  label,
  value,
  onChange,
  autoFocus,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  autoFocus?: boolean;
}) {
  return (
    <TextField
      label={label}
      type="password"
      value={value}
      // Strip anything that is not a digit as it is typed, so the four-digit rule is
      // obvious at the keyboard rather than only when the backend rejects it.
      onChange={(e) => onChange(e.target.value.replace(/\D/g, "").slice(0, 4))}
      autoFocus={autoFocus}
      fullWidth
      slotProps={{ htmlInput: { inputMode: "numeric", maxLength: 4 } }}
    />
  );
}

/**
 * The customer's debit card: issuing one, changing its PIN, and blocking it.
 *
 * The full card number is shown only in the moment it is issued, because that is the only
 * time the backend returns it — every later read is masked. Blocking is presented as
 * irreversible, which it is: unblocking is a bank operation, not a customer one.
 */
export default function CardPanel({ onNotify }: { onNotify: (message: string) => void }) {
  const [card, setCard] = useState<DebitCard | null>(null);
  const [issuedNumber, setIssuedNumber] = useState<string | null>(null);
  const [issueOpen, setIssueOpen] = useState(false);
  const [changePinOpen, setChangePinOpen] = useState(false);
  const [pin, setPin] = useState("");
  const [currentPin, setCurrentPin] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const refresh = useCallback(() => {
    getMyCard()
      .then(setCard)
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  function openIssue() {
    setPin("");
    setError(null);
    setIssueOpen(true);
  }

  function openChangePin() {
    setPin("");
    setCurrentPin("");
    setError(null);
    setChangePinOpen(true);
  }

  async function handleIssue() {
    if (pin.length !== 4) {
      setError("Choose a 4-digit PIN.");
      return;
    }
    setSubmitting(true);
    try {
      const issued = await issueCard(pin);
      setCard(issued);
      setIssuedNumber(issued.cardNumber);
      setIssueOpen(false);
      onNotify("Card issued");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not issue a card");
    } finally {
      setSubmitting(false);
      setPin("");
    }
  }

  async function handleChangePin() {
    if (pin.length !== 4 || currentPin.length !== 4) {
      setError("Both PINs must be 4 digits.");
      return;
    }
    setSubmitting(true);
    try {
      await changeCardPin(currentPin, pin);
      setChangePinOpen(false);
      onNotify("PIN changed");
      refresh();
    } catch (err) {
      // Carries the backend's wording, including the warning when the card has just been
      // blocked by too many wrong attempts.
      setError(err instanceof Error ? err.message : "Could not change the PIN");
    } finally {
      setSubmitting(false);
      setPin("");
      setCurrentPin("");
    }
  }

  async function handleBlock() {
    try {
      const blocked = await blockCard();
      setCard(blocked);
      onNotify("Card blocked");
    } catch (err) {
      onNotify(err instanceof Error ? err.message : "Could not block the card");
    }
  }

  return (
    <>
      {/* Wrapped in the same surface every other panel uses, and with no page-flow margin
          of its own: it now sits in a grid cell beside the balance rather than in a
          single scrolling column. */}
      <SectionCard title="Debit card">
        {card ? (
          <Stack spacing={1.5}>
            {issuedNumber && (
              <Alert severity="info" onClose={() => setIssuedNumber(null)}>
                Your card number is <strong>{issuedNumber}</strong>. Note it down — it will
                not be shown in full again.
              </Alert>
            )}
            <Stack
              direction={{ xs: "column", sm: "row" }}
              spacing={2}
              sx={{ alignItems: { sm: "center" } }}
            >
              <Typography
                variant="h6"
                sx={{ fontFamily: dataFontFamily, fontWeight: 500, letterSpacing: 1 }}
              >
                {card.cardNumber}
              </Typography>
              <Chip
                size="small"
                label={STATUS_LABELS[card.status]}
                color={card.status === "ACTIVE" ? "success" : "error"}
                variant={card.status === "ACTIVE" ? "filled" : "outlined"}
              />
              <Typography variant="body2" color="text.secondary">
                Expires {card.expiresOn}
              </Typography>
            </Stack>
            {card.status === "ACTIVE" && (
              <Stack direction="row" spacing={1.5}>
                <Button size="small" variant="outlined" onClick={openChangePin}>
                  Change PIN
                </Button>
                <Button size="small" color="error" onClick={handleBlock}>
                  Block card
                </Button>
              </Stack>
            )}
          </Stack>
        ) : (
          <Stack spacing={1.5} sx={{ alignItems: "flex-start" }}>
            <Typography variant="body2" color="text.secondary">
              You do not have a card on this account yet.
            </Typography>
            <Button variant="outlined" onClick={openIssue}>
              Issue a card
            </Button>
          </Stack>
        )}
      </SectionCard>

      <Dialog open={issueOpen} onClose={() => setIssueOpen(false)} fullWidth maxWidth="xs">
        <DialogTitle>Issue a card</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {error && <Alert severity="error">{error}</Alert>}
            <Typography variant="body2" color="text.secondary">
              Choose a 4-digit PIN. It is stored only as a hash, so it cannot be recovered —
              if you forget it you will need a new card.
            </Typography>
            <PinField label="PIN" value={pin} onChange={setPin} autoFocus />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setIssueOpen(false)} disabled={submitting}>
            Cancel
          </Button>
          <Button variant="contained" onClick={handleIssue} disabled={submitting}>
            {submitting ? "Issuing…" : "Issue card"}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={changePinOpen} onClose={() => setChangePinOpen(false)} fullWidth maxWidth="xs">
        <DialogTitle>Change PIN</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {error && <Alert severity="error">{error}</Alert>}
            <Box>
              <Typography variant="body2" color="text.secondary">
                Three wrong attempts will block the card.
              </Typography>
            </Box>
            <PinField label="Current PIN" value={currentPin} onChange={setCurrentPin} autoFocus />
            <PinField label="New PIN" value={pin} onChange={setPin} />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setChangePinOpen(false)} disabled={submitting}>
            Cancel
          </Button>
          <Button variant="contained" onClick={handleChangePin} disabled={submitting}>
            {submitting ? "Saving…" : "Change PIN"}
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
