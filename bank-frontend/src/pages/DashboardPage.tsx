import { useCallback, useState } from "react";
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
import DescriptionIcon from "@mui/icons-material/DescriptionOutlined";
import LogoutIcon from "@mui/icons-material/LogoutOutlined";
import { useNavigate } from "react-router-dom";
import { downloadFile, downloadPayload, getInbox, getOutbox } from "../api/client";
import { useAuth } from "../context/AuthContext";
import TransferTable from "../components/TransferTable";
import Pager from "../components/Pager";
import { usePagedResource } from "../hooks/usePagedResource";
import SlipComposerDialog from "../components/SlipComposerDialog";
import type { FileTransfer } from "../types";

export default function DashboardPage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [tab, setTab] = useState<"inbox" | "outbox">("inbox");
  const [slipDialogOpen, setSlipDialogOpen] = useState(false);
  const [downloadingId, setDownloadingId] = useState<number | null>(null);
  const [snackbar, setSnackbar] = useState<string | null>(null);

  // No userId argument: the backend scopes both to whoever the token says we are, and
  // returns one page at a time rather than the whole mailbox.
  const inbox = usePagedResource<FileTransfer>(getInbox);
  const outbox = usePagedResource<FileTransfer>(getOutbox);

  const refresh = useCallback(() => {
    inbox.refresh();
    outbox.refresh();
    // Both refresh functions are recreated on every render, so they are deliberately not
    // dependencies here — including them would re-run this on each render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!user) {
    // Guarded by the router, but keeps this component safe to render standalone too.
    navigate("/login", { replace: true });
    return null;
  }

  async function handleDownload(transfer: FileTransfer) {
    if (!user) return;
    setDownloadingId(transfer.transferId);
    try {
      await downloadFile(transfer.transferId, transfer.originalFilename);
      refresh();
    } catch (err) {
      setSnackbar(err instanceof Error ? err.message : "Download failed");
    } finally {
      setDownloadingId(null);
    }
  }

  async function handleDownloadPayload(transfer: FileTransfer) {
    try {
      await downloadPayload(transfer.transferId, transfer.originalFilename);
    } catch (err) {
      setSnackbar(err instanceof Error ? err.message : "Payload download failed");
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
          <Typography variant="body2" sx={{ opacity: 0.85, display: { xs: "none", sm: "block" } }}>
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

      <Container maxWidth="md" sx={{ py: 4 }}>
        <Box sx={{ display: "flex", justifyContent: "flex-end", mb: 2 }}>
          <Button
            variant="contained"
            startIcon={<DescriptionIcon />}
            onClick={() => setSlipDialogOpen(true)}
          >
            Compose slip
          </Button>
        </Box>

        <Paper variant="outlined">
          <Tabs
            value={tab}
            onChange={(_, value) => setTab(value)}
            variant="scrollable"
            scrollButtons="auto"
            allowScrollButtonsMobile
            sx={{ px: 2, borderBottom: 1, borderColor: "divider" }}
          >
            <Tab label={`Inbox (${inbox.totalElements})`} value="inbox" />
            <Tab label={`Outbox (${outbox.totalElements})`} value="outbox" />
          </Tabs>

          <Box sx={{ p: 2 }}>
            {tab === "inbox" ? (
              <>
                <TransferTable
                  mode="inbox"
                  transfers={inbox.items}
                  onDownload={handleDownload}
                  onDownloadPayload={handleDownloadPayload}
                  downloadingId={downloadingId}
                />
                <Pager {...inbox} onPageChange={inbox.setPage} />
              </>
            ) : (
              <>
                <TransferTable mode="outbox" transfers={outbox.items} />
                <Pager {...outbox} onPageChange={outbox.setPage} />
              </>
            )}
          </Box>
        </Paper>
      </Container>

      <SlipComposerDialog
        open={slipDialogOpen}
        onClose={() => setSlipDialogOpen(false)}
        onSent={() => {
          setSnackbar("Slip generated and sent");
          refresh();
        }}
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
