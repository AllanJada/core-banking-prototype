import {
  Box,
  Button,
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
import type { FileTransfer } from "../types";
import StatusChip from "./StatusChip";
import SignatureChip from "./SignatureChip";
import { dataFontFamily } from "../theme";

interface TransferTableProps {
  mode: "inbox" | "outbox";
  transfers: FileTransfer[];
  onDownload?: (transfer: FileTransfer) => void;
  onDownloadPayload?: (transfer: FileTransfer) => void;
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
                <StatusChip status={transfer.status} />
              </TableCell>
              <TableCell>
                <SignatureChip transfer={transfer} />
              </TableCell>
              {mode === "inbox" && (
                <TableCell align="right">
                  <Stack direction="row" spacing={1} sx={{ justifyContent: "flex-end" }}>
                    <Button
                      size="small"
                      variant="outlined"
                      startIcon={<DownloadIcon />}
                      disabled={downloadingId === transfer.transferId}
                      onClick={() => onDownload?.(transfer)}
                    >
                      {downloadingId === transfer.transferId ? "Downloading…" : "Download"}
                    </Button>
                    {/* Only for transfers that carry one — a plain file has no payment
                        instruction to express, so there is nothing to offer. */}
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
