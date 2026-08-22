import { Chip } from "@mui/material";
import type { TransferStatus } from "../types";

const STATUS_CONFIG: Record<TransferStatus, { label: string; color: "warning" | "success" }> = {
  SENT: { label: "Awaiting pickup", color: "warning" },
  DOWNLOADED: { label: "Received", color: "success" },
};

export default function StatusChip({ status }: { status: TransferStatus }) {
  const config = STATUS_CONFIG[status];
  return <Chip label={config.label} color={config.color} size="small" variant="filled" />;
}
