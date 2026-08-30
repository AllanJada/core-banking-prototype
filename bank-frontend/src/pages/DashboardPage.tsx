import { useCallback, useEffect, useState } from "react";
import {
  AppBar,
  Box,
  Button,
  Container,
  Paper,
  Snackbar,
  Tab,
  Tabs,
  Toolbar,
  Typography,
} from "@mui/material";
import SendIcon from "@mui/icons-material/SendOutlined";
import DescriptionIcon from "@mui/icons-material/DescriptionOutlined";
import LogoutIcon from "@mui/icons-material/LogoutOutlined";
import { useNavigate } from "react-router-dom";
import { downloadFile, getInbox, getOutbox } from "../api/client";
import { useAuth } from "../context/AuthContext";
import TransferTable from "../components/TransferTable";
import SendFileDialog from "../components/SendFileDialog";
import SlipComposerDialog from "../components/SlipComposerDialog";
import type { FileTransfer } from "../types";

export default function DashboardPage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [tab, setTab] = useState<"inbox" | "outbox">("inbox");
  const [inbox, setInbox] = useState<FileTransfer[]>([]);
  const [outbox, setOutbox] = useState<FileTransfer[]>([]);
  const [sendDialogOpen, setSendDialogOpen] = useState(false);
  const [slipDialogOpen, setSlipDialogOpen] = useState(false);
  const [downloadingId, setDownloadingId] = useState<number | null>(null);
  const [snackbar, setSnackbar] = useState<string | null>(null);

  const refresh = useCallback(() => {
    if (!user) return;
    getInbox(user.userId).then(setInbox).catch(() => setSnackbar("Failed to load inbox"));
    getOutbox(user.userId).then(setOutbox).catch(() => setSnackbar("Failed to load outbox"));
  }, [user]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  if (!user) {
    // Guarded by the router, but keeps this component safe to render standalone too.
    navigate("/login", { replace: true });
    return null;
  }

  async function handleDownload(transfer: FileTransfer) {
    if (!user) return;
    setDownloadingId(transfer.transferId);
    try {
      await downloadFile(transfer.transferId, user.userId, transfer.originalFilename);
      refresh();
    } catch (err) {
      setSnackbar(err instanceof Error ? err.message : "Download failed");
    } finally {
      setDownloadingId(null);
    }
  }

  function handleLogout() {
    logout();
    navigate("/login", { replace: true });
  }

  return (
    <Box sx={{ minHeight: "100vh", bgcolor: "background.default" }}>
      <AppBar position="static" elevation={0}>
        <Toolbar sx={{ gap: 2 }}>
          <Typography variant="subtitle1" sx={{ flexGrow: 1, fontWeight: 600 }}>
            Secure File Transfer
          </Typography>
          <Typography variant="body2" sx={{ opacity: 0.85 }}>
            Signed in as {user.username}
          </Typography>
          <Button
            color="inherit"
            size="small"
            startIcon={<LogoutIcon />}
            onClick={handleLogout}
          >
            Sign out
          </Button>
        </Toolbar>
      </AppBar>

      {/* lg, not md: at md (900px) the transfer table's six columns force
          the TableContainer into horizontal scroll on an ordinary desktop
          viewport even though there's plenty of unused width either side —
          confirmed by rendering it with real data, not just from reading
          the JSX. A dense data table should use available width rather
          than scroll sideways for no reason (component-patterns.md's
          dashboard guidance). */}
      <Container maxWidth="lg" sx={{ py: 4 }}>
        <Box sx={{ display: "flex", justifyContent: "flex-end", gap: 1.5, mb: 2 }}>
          <Button
            variant="outlined"
            startIcon={<DescriptionIcon />}
            onClick={() => setSlipDialogOpen(true)}
          >
            Compose slip
          </Button>
          {/* Outlined, matching "Compose slip": these are two equally-valid
              ways to start the same underlying task (get a document to a
              recipient), not a primary/secondary pair — a single contained
              button here would claim one is "more correct" than the other,
              which isn't true. See 07-buttons.md's "genuinely equal
              importance" guidance (its own example: "Report" vs. "Don't
              report," both secondary weight). */}
          <Button
            variant="outlined"
            startIcon={<SendIcon />}
            onClick={() => setSendDialogOpen(true)}
          >
            Send file
          </Button>
        </Box>

        <Paper variant="outlined">
          <Tabs
            value={tab}
            onChange={(_, value) => setTab(value)}
            sx={{ px: 2, borderBottom: 1, borderColor: "divider" }}
          >
            <Tab label={`Inbox (${inbox.length})`} value="inbox" />
            <Tab label={`Outbox (${outbox.length})`} value="outbox" />
          </Tabs>

          <Box sx={{ p: 2 }}>
            {tab === "inbox" ? (
              <TransferTable
                mode="inbox"
                transfers={inbox}
                onDownload={handleDownload}
                downloadingId={downloadingId}
              />
            ) : (
              <TransferTable
                mode="outbox"
                transfers={outbox}
                onComposeSlip={() => setSlipDialogOpen(true)}
                onSendFile={() => setSendDialogOpen(true)}
              />
            )}
          </Box>
        </Paper>
      </Container>

      <SendFileDialog
        open={sendDialogOpen}
        onClose={() => setSendDialogOpen(false)}
        onSent={() => {
          setSnackbar("File sent");
          refresh();
        }}
        currentUser={user}
      />

      <SlipComposerDialog
        open={slipDialogOpen}
        onClose={() => setSlipDialogOpen(false)}
        onSent={() => {
          setSnackbar("Slip generated and sent");
          refresh();
        }}
        currentUser={user}
      />

      <Snackbar
        open={snackbar !== null}
        autoHideDuration={3000}
        onClose={() => setSnackbar(null)}
        message={snackbar}
      />
    </Box>
  );
}
