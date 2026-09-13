import { useEffect, useState } from "react";
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import CheckCircleOutlinedIcon from "@mui/icons-material/CheckCircleOutlined";
import CancelOutlinedIcon from "@mui/icons-material/CancelOutlined";
import { approveTransfer, fetchPreviewDocument, previewTransfer, rejectTransfer } from "../api/client";
import type { FileTransfer, PaymentPreview } from "../types";

interface TransferReviewDialogProps {
  transfer: FileTransfer | null;
  onClose: () => void;
  onDecided: (message: string) => void;
}

function money(amount: number, currency: string): string {
  return new Intl.NumberFormat(undefined, { style: "currency", currency, currencyDisplay: "code" }).format(
    amount
  );
}

/**
 * The review step: shows the document and, when present, the parsed ISO 20022 payload
 * before the recipient commits to approving or rejecting a transfer.
 *
 * Opening this dialog is what "picking up" a transfer actually means in this system —
 * the backend refuses to serve the document for download until it has been approved here.
 * A transfer that has already been decided (APPROVED, REJECTED, DOWNLOADED) still opens
 * this dialog to show what was decided and why, but the decision buttons are hidden.
 */
export default function TransferReviewDialog({ transfer, onClose, onDecided }: TransferReviewDialogProps) {
  const [preview, setPreview] = useState<PaymentPreview | null>(null);
  const [documentUrl, setDocumentUrl] = useState<string | null>(null);
  const [documentError, setDocumentError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [rejecting, setRejecting] = useState(false);
  const [rejectReason, setRejectReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const open = transfer !== null;

  useEffect(() => {
    if (!transfer) return;
    let cancelled = false;
    let objectUrl: string | null = null;

    setPreview(null);
    setDocumentUrl(null);
    setDocumentError(null);
    setRejecting(false);
    setRejectReason("");
    setError(null);
    setLoading(true);

    previewTransfer(transfer.transferId)
      .then((result) => {
        if (!cancelled) setPreview(result);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : "Could not load this transfer");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    // Fetched separately from the structured preview: the document can fail to load (a
    // tampered file fails to decrypt outright) independently of whether the summary above
    // loaded fine, and the two failures mean different things to a reviewer.
    fetchPreviewDocument(transfer.transferId)
      .then((blob) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setDocumentUrl(objectUrl);
      })
      .catch((err) => {
        if (!cancelled) setDocumentError(err instanceof Error ? err.message : "Could not load the document");
      });

    return () => {
      cancelled = true;
      // Object URLs are not garbage collected on their own — leaving this out would leak
      // one blob URL per transfer opened over the life of the page.
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [transfer]);

  async function handleApprove() {
    if (!transfer) return;
    setSubmitting(true);
    setError(null);
    try {
      await approveTransfer(transfer.transferId);
      onDecided("Transfer approved — it can now be downloaded");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not approve this transfer");
    } finally {
      setSubmitting(false);
    }
  }

  async function handleReject() {
    if (!transfer) return;
    if (!rejectReason.trim()) {
      setError("A reason is required to reject a transfer.");
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await rejectTransfer(transfer.transferId, rejectReason.trim());
      onDecided("Transfer rejected");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not reject this transfer");
    } finally {
      setSubmitting(false);
    }
  }

  const canDecide = preview?.status === "SENT";

  return (
    <Dialog open={open} onClose={submitting ? undefined : onClose} fullWidth maxWidth="md">
      <DialogTitle>Review transfer</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          {error && <Alert severity="error">{error}</Alert>}

          {preview && !preview.integrityValid && (
            <Alert severity="error">
              This transfer failed its integrity check: {preview.integrityWarning}. The document and payload
              shown below (if any) may not reflect what was actually signed — reject this transfer rather
              than approve it.
            </Alert>
          )}

          {preview?.status === "REJECTED" && transfer?.rejectionReason && (
            <Alert severity="warning">Rejected: {transfer.rejectionReason}</Alert>
          )}

          {preview?.payload && (
            <Box>
              <Typography variant="subtitle2" sx={{ mb: 1 }}>
                Payment instruction
              </Typography>
              <Stack direction={{ xs: "column", sm: "row" }} spacing={3}>
                <Box sx={{ flex: 1 }}>
                  <Typography variant="caption" color="text.secondary">
                    From
                  </Typography>
                  <Typography variant="body2">{preview.payload.debtorName}</Typography>
                  <Typography variant="body2" sx={{ letterSpacing: 0.5 }} color="text.secondary">
                    {preview.payload.debtorAccountNumber}
                  </Typography>
                </Box>
                <Box sx={{ flex: 1 }}>
                  <Typography variant="caption" color="text.secondary">
                    To
                  </Typography>
                  <Typography variant="body2">{preview.payload.creditorName}</Typography>
                  <Typography variant="body2" sx={{ letterSpacing: 0.5 }} color="text.secondary">
                    {preview.payload.creditorAccountNumber}
                  </Typography>
                </Box>
                <Box sx={{ flex: 1 }}>
                  <Typography variant="caption" color="text.secondary">
                    Amount
                  </Typography>
                  <Typography variant="h6" sx={{ fontWeight: 700 }}>
                    {money(preview.payload.amount, preview.payload.currency)}
                  </Typography>
                </Box>
              </Stack>
              {preview.payload.remittanceInformation && (
                <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                  {preview.payload.remittanceInformation}
                </Typography>
              )}
              <Divider sx={{ my: 2 }} />
            </Box>
          )}

          <Box>
            <Typography variant="subtitle2" sx={{ mb: 1 }}>
              Document
            </Typography>
            {documentError && <Alert severity="error">{documentError}</Alert>}
            {documentUrl && (
              <Box
                component="iframe"
                src={documentUrl}
                title="Transfer document preview"
                sx={{ width: "100%", height: 420, border: "1px solid", borderColor: "divider", borderRadius: 1 }}
              />
            )}
            {!documentUrl && !documentError && (
              <Typography variant="body2" color="text.secondary">
                Loading document…
              </Typography>
            )}
          </Box>

          {canDecide && rejecting && (
            <TextField
              label="Reason for rejecting"
              value={rejectReason}
              onChange={(e) => setRejectReason(e.target.value)}
              fullWidth
              multiline
              minRows={2}
              autoFocus
              required
            />
          )}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={submitting}>
          Close
        </Button>
        {canDecide && (
          <>
            {rejecting ? (
              <Button
                color="error"
                variant="contained"
                startIcon={<CancelOutlinedIcon />}
                onClick={handleReject}
                disabled={submitting || loading}
              >
                {submitting ? "Rejecting…" : "Confirm rejection"}
              </Button>
            ) : (
              <Button color="error" onClick={() => setRejecting(true)} disabled={submitting || loading}>
                Reject
              </Button>
            )}
            <Button
              variant="contained"
              startIcon={<CheckCircleOutlinedIcon />}
              onClick={handleApprove}
              // The backend would refuse this anyway — approving re-verifies and throws on
              // failure — but disabling it here means the reviewer doesn't have to find that
              // out by clicking; the alert above already told them why.
              disabled={submitting || loading || rejecting || preview?.integrityValid === false}
            >
              {submitting ? "Approving…" : "Approve"}
            </Button>
          </>
        )}
      </DialogActions>
    </Dialog>
  );
}
