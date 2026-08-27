import { Chip, Tooltip } from "@mui/material";
import VerifiedOutlinedIcon from "@mui/icons-material/VerifiedOutlined";
import ErrorOutlinedIcon from "@mui/icons-material/ErrorOutlined";
import PendingOutlinedIcon from "@mui/icons-material/PendingOutlined";
import LockOpenOutlinedIcon from "@mui/icons-material/LockOpenOutlined";
import type { FileTransfer } from "../types";

/**
 * Deliberately distinguishes "Signed" from "Verified" rather than showing one generic
 * "secure" badge: the backend only actually re-checks a signature against the sender's
 * public key when the recipient downloads the file (FileTransferService.downloadFile).
 * Before that happens, all we know is that a signature was attached at send time — not
 * that anyone has confirmed it's valid. Claiming "Verified" earlier than that would be
 * a claim this system hasn't actually earned yet.
 */
export default function SignatureChip({ transfer }: { transfer: FileTransfer }) {
  const hasSignature = Boolean(transfer.signature && transfer.fileHash);

  if (!hasSignature) {
    return (
      <Tooltip title="No signature on record for this transfer">
        <Chip
          icon={<LockOpenOutlinedIcon />}
          label="Unsigned"
          size="small"
          variant="outlined"
        />
      </Tooltip>
    );
  }

  if (transfer.status === "SENT") {
    return (
      <Tooltip title="Signed at send time. Verification happens when the recipient downloads it.">
        <Chip
          icon={<PendingOutlinedIcon />}
          label="Signed"
          size="small"
          color="info"
          variant="outlined"
        />
      </Tooltip>
    );
  }

  if (transfer.signatureValid === false) {
    return (
      <Tooltip title="The file or its signature failed verification on download — it may have been altered or corrupted.">
        <Chip
          icon={<ErrorOutlinedIcon />}
          label="Invalid signature"
          size="small"
          color="error"
          variant="filled"
        />
      </Tooltip>
    );
  }

  return (
    <Tooltip title="Verified against the sender's public key when downloaded.">
      <Chip
        icon={<VerifiedOutlinedIcon />}
        label="Verified"
        size="small"
        color="success"
        variant="filled"
      />
    </Tooltip>
  );
}
