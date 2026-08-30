import {
  Box,
  Button,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import DownloadIcon from "@mui/icons-material/DownloadOutlined";
import SendIcon from "@mui/icons-material/SendOutlined";
import DescriptionIcon from "@mui/icons-material/DescriptionOutlined";
import type { FileTransfer } from "../types";
import StatusChip from "./StatusChip";
import SignatureChip from "./SignatureChip";
import { dataFontFamily } from "../theme";

interface TransferTableProps {
  mode: "inbox" | "outbox";
  transfers: FileTransfer[];
  onDownload?: (transfer: FileTransfer) => void;
  downloadingId?: number | null;
  /** Outbox empty state only — same icon/label/weight as the dashboard
   * toolbar's buttons, so the empty state reads as an echo of them, not a
   * new affordance. Omitted for inbox: nothing the user does here produces
   * inbound transfers, so a button would promise an action that doesn't
   * actually resolve the empty state (component-patterns.md's empty-state
   * rule is about giving a *real* next action, not a button for its own sake). */
  onComposeSlip?: () => void;
  onSendFile?: () => void;
}

function formatTimestamp(value: string | null): string {
  if (!value) return "—";
  return new Date(value).toLocaleString();
}

export default function TransferTable({
  mode,
  transfers,
  onDownload,
  downloadingId,
  onComposeSlip,
  onSendFile,
}: TransferTableProps) {
  if (transfers.length === 0) {
    const showActions = mode === "outbox" && (onComposeSlip || onSendFile);
    return (
      <Box sx={{ py: 6, textAlign: "center" }}>
        <Typography variant="body2" color="text.secondary" sx={{ mb: showActions ? 2.5 : 0 }}>
          {mode === "inbox"
            ? "Files sent to you by other institutions will appear here."
            : "You haven't sent any files yet."}
        </Typography>
        {showActions && (
          <Stack direction="row" spacing={1.5} sx={{ justifyContent: "center" }}>
            {onComposeSlip && (
              <Button variant="outlined" startIcon={<DescriptionIcon />} onClick={onComposeSlip}>
                Compose slip
              </Button>
            )}
            {onSendFile && (
              <Button variant="outlined" startIcon={<SendIcon />} onClick={onSendFile}>
                Send file
              </Button>
            )}
          </Stack>
        )}
      </Box>
    );
  }

  return (
    <TableContainer>
      <Table size="small">
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
                <StatusChip status={transfer.status} />
              </TableCell>
              <TableCell>
                <SignatureChip transfer={transfer} />
              </TableCell>
              {mode === "inbox" && (
                <TableCell align="right">
                  <Button
                    size="small"
                    variant="outlined"
                    startIcon={<DownloadIcon />}
                    disabled={downloadingId === transfer.transferId}
                    onClick={() => onDownload?.(transfer)}
                  >
                    {downloadingId === transfer.transferId ? "Downloading…" : "Download"}
                  </Button>
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
