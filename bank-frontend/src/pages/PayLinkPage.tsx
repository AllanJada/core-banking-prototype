import { useEffect, useState } from "react";
import {
  Alert,
  AppBar,
  Box,
  Button,
  CircularProgress,
  Container,
  Divider,
  Paper,
  Stack,
  Toolbar,
  Typography,
} from "@mui/material";
import { useNavigate, useParams } from "react-router-dom";
import { getPaymentLink, payPaymentLink } from "../api/client";
import { useAuth } from "../context/AuthContext";
import type { PaymentLink } from "../types";

function formatMoney(amount: number): string {
  return new Intl.NumberFormat(undefined, { minimumFractionDigits: 2 }).format(amount);
}

/** Why a link cannot be paid, or null when it can. */
function blockedReason(link: PaymentLink): string | null {
  switch (link.status) {
    case "PAID":
      return "This payment request has already been paid.";
    case "CANCELLED":
      return "This payment request was cancelled.";
    case "EXPIRED":
      return "This payment request has expired.";
    default:
      return null;
  }
}

/**
 * What someone sees when they open a shared payment link.
 *
 * The amount and recipient are shown before anything is paid — this screen is the review
 * step, and no money moves until the button is pressed. The status shown comes from the
 * backend, which derives expiry from the clock, so a link that lapsed while this page sat
 * open still refuses to pay: the check that matters happens on the server at payment time,
 * not here.
 */
export default function PayLinkPage() {
  const { linkId } = useParams<{ linkId: string }>();
  const { user } = useAuth();
  const navigate = useNavigate();

  const [link, setLink] = useState<PaymentLink | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [paid, setPaid] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!linkId) return;
    getPaymentLink(linkId)
      .then(setLink)
      .catch((err) =>
        setError(err instanceof Error ? err.message : "Could not load this payment request")
      )
      .finally(() => setLoading(false));
  }, [linkId]);

  async function handlePay() {
    if (!linkId) return;
    setError(null);
    setSubmitting(true);
    try {
      await payPaymentLink(linkId);
      setPaid(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Payment failed");
      // Re-read it: the refusal may have been because someone else paid it first, in
      // which case the status on screen is now out of date.
      getPaymentLink(linkId).then(setLink).catch(() => undefined);
    } finally {
      setSubmitting(false);
    }
  }

  const blocked = link ? blockedReason(link) : null;

  return (
    <Box sx={{ minHeight: "100vh", bgcolor: "background.default" }}>
      <AppBar position="static" elevation={0}>
        <Toolbar sx={{ gap: 2 }}>
          <Typography variant="subtitle1" sx={{ flexGrow: 1, fontWeight: 600 }}>
            Payment request
          </Typography>
          {user && (
            <Typography variant="body2" sx={{ opacity: 0.85, display: { xs: "none", sm: "block" } }}>
              Signed in as {user.username}
            </Typography>
          )}
        </Toolbar>
      </AppBar>

      <Container maxWidth="sm" sx={{ py: 4 }}>
        <Paper variant="outlined" sx={{ p: 4 }}>
          {loading ? (
            <Stack sx={{ py: 4, alignItems: "center" }}>
              <CircularProgress />
            </Stack>
          ) : (
            <Stack spacing={2}>
              {paid ? (
                <Alert severity="success">Payment sent.</Alert>
              ) : (
                <>
                  {error && <Alert severity="error">{error}</Alert>}
                  {blocked && !error && <Alert severity="warning">{blocked}</Alert>}
                </>
              )}

              {link && (
                <>
                  <Box>
                    <Typography variant="body2" color="text.secondary">
                      You are paying
                    </Typography>
                    <Typography variant="h4" sx={{ fontWeight: 700 }}>
                      {formatMoney(link.amount)}
                    </Typography>
                  </Box>

                  <Divider />

                  <Box>
                    <Typography variant="body2" color="text.secondary">
                      To account
                    </Typography>
                    <Typography variant="body1" sx={{ fontWeight: 600, letterSpacing: 1 }}>
                      {link.requesterAccountNumber}
                    </Typography>
                  </Box>

                  {link.description && (
                    <Box>
                      <Typography variant="body2" color="text.secondary">
                        For
                      </Typography>
                      <Typography variant="body1">{link.description}</Typography>
                    </Box>
                  )}
                </>
              )}

              <Stack direction="row" spacing={1.5} sx={{ pt: 1 }}>
                {!paid && link && !blocked && (
                  <Button variant="contained" onClick={handlePay} disabled={submitting}>
                    {submitting ? "Paying…" : `Pay ${formatMoney(link.amount)}`}
                  </Button>
                )}
                <Button onClick={() => navigate("/account", { replace: true })}>
                  {paid ? "Back to my account" : "Cancel"}
                </Button>
              </Stack>
            </Stack>
          )}
        </Paper>
      </Container>
    </Box>
  );
}
