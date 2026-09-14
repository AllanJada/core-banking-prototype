import {
  Box,
  Button,
  Chip,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Stack,
  Typography,
} from "@mui/material";
import DownloadIcon from "@mui/icons-material/DownloadOutlined";
import CodeIcon from "@mui/icons-material/CodeOutlined";
import RateReviewOutlinedIcon from "@mui/icons-material/RateReviewOutlined";
import type { FileTransfer } from "../types";
import StatusChip from "./StatusChip";
import SignatureChip from "./SignatureChip";
import { dataFontFamily } from "../theme";

interface TransferTableProps {
  mode: "inbox" | "outbox";
  transfers: FileTransfer[];
  onDownload?: (transfer: FileTransfer) => void;
  onDownloadPayload?: (transfer: FileTransfer) => void;
  onReview?: (transfer: FileTransfer) => void;
  downloadingId?: number | null;
}

function formatTimestamp(value: string | null): string {
  if (!value) return "—";
  return new Date(value).toLocaleString();
}

export default function TransferTable({
  mode,
  transfers,
  onDownload,
  onDownloadPayload,
  onReview,
  downloadingId,
}: TransferTableProps) {
  if (transfers.length === 0) {
    return (
      <Box sx={{ py: 6, textAlign: "center" }}>
        <Typography variant="body2" color="text.secondary">
          {mode === "inbox"
            ? "Nothing in your inbox yet."
            : "You haven't sent any files yet."}
        </Typography>
      </Box>
    );
  }

  return (
    <TableContainer>
      <Table size="small" sx={{ minWidth: 640 }}>
        <TableHead>
          <TableRow>
            <TableCell>{mode === "inbox" ? "From" : "To"}</TableCell>
            <TableCell>File</TableCell>
            <TableCell>Sent</TableCell>
            <TableCell>Status</TableCell>
            <TableCell>Signature</TableCell>
            {mode === "inbox" && <TableCell align="right">Action</TableCell>}
            {mode === "outbox" && <TableCell>Received</TableCell>}
          </TableRow>
        </TableHead>
        <TableBody>
          {transfers.map((transfer) => (
            <TableRow key={transfer.transferId} hover>
              <TableCell>
                {mode === "inbox" ? transfer.senderUsername : transfer.receiverUsername}
              </TableCell>
              <TableCell sx={{ fontFamily: dataFontFamily, fontSize: "0.8rem" }}>
                {transfer.originalFilename}
              </TableCell>
              <TableCell sx={{ fontFamily: dataFontFamily, fontSize: "0.8rem" }}>
                {formatTimestamp(transfer.sentAt)}
              </TableCell>
              <TableCell>
                <Stack direction="row" spacing={0.5} sx={{ alignItems: "center" }}>
                  <StatusChip status={transfer.status} />
                  {/* Approving a slip moves money. This says whether it did. */}
                  {transfer.paymentId != null && (
                    <Chip size="small" color="success" variant="outlined" label="Disbursed" />
                  )}
                </Stack>
              </TableCell>
              <TableCell>
                <SignatureChip transfer={transfer} />
              </TableCell>
              {mode === "inbox" && (
                <TableCell align="right">
                  <Stack direction="row" spacing={1} sx={{ justifyContent: "flex-end" }}>
                    {/* Review is always available — it's the doorway to approve/reject, and
                        also how a decided transfer's outcome is re-inspected afterwards. */}
                    <Button
                      size="small"
                      variant={transfer.status === "SENT" ? "contained" : "outlined"}
                      startIcon={<RateReviewOutlinedIcon />}
                      onClick={() => onReview?.(transfer)}
                    >
                      Review
                    </Button>
                    {/* Download and the payload are gated by the backend on approval, so
                        they are only offered once that gate would actually let them
                        through — offering a button that always 400s would be worse than
                        not offering one. */}
                    {(transfer.status === "APPROVED" || transfer.status === "DOWNLOADED") && (
                      <>
                        <Button
                          size="small"
                          variant="outlined"
                          startIcon={<DownloadIcon />}
                          disabled={downloadingId === transfer.transferId}
                          onClick={() => onDownload?.(transfer)}
                        >
                          {downloadingId === transfer.transferId ? "Downloading…" : "Download"}
                        </Button>
                        {transfer.hasPayload && (
                          <Button
                            size="small"
                            startIcon={<CodeIcon />}
                            onClick={() => onDownloadPayload?.(transfer)}
                            title="ISO 20022 pain.001 payment instruction"
                          >
                            XML
                          </Button>
                        )}
                      </>
                    )}
                  </Stack>
                </TableCell>
              )}
              {mode === "outbox" && (
                <TableCell sx={{ fontFamily: dataFontFamily, fontSize: "0.8rem" }}>
                  {formatTimestamp(transfer.downloadedAt)}
                </TableCell>
              )}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}
