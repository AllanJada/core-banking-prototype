import { useCallback, useEffect, useState } from "react";
import type { ReactNode } from "react";
import {
  AppBar,
  Box,
  Button,
  Drawer,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Snackbar,
  Toolbar,
  Typography,
} from "@mui/material";
import InboxOutlinedIcon from "@mui/icons-material/InboxOutlined";
import OutboxOutlinedIcon from "@mui/icons-material/OutboxOutlined";
import DescriptionOutlinedIcon from "@mui/icons-material/DescriptionOutlined";
import LogoutIcon from "@mui/icons-material/LogoutOutlined";
import { useNavigate } from "react-router-dom";
import { downloadFile, getInbox, getOutbox } from "../api/client";
import { useAuth } from "../context/AuthContext";
import TransferTable from "../components/TransferTable";
import SlipComposerPanel from "../components/SlipComposerPanel";
import type { FileTransfer } from "../types";
const DRAWER_WIDTH = 220;
type Section = "inbox" | "outbox" | "compose";
const NAV_ITEMS: { section: Section; label: string; icon: ReactNode }[] = [
  { section: "inbox", label: "Inbox", icon: <InboxOutlinedIcon /> },
  { section: "outbox", label: "Outbox", icon: <OutboxOutlinedIcon /> },
  { section: "compose", label: "Compose", icon: <DescriptionOutlinedIcon /> },
];
export default function BankDashboardPage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [section, setSection] = useState<Section>("inbox");
  const [inbox, setInbox] = useState<FileTransfer[]>([]);
  const [outbox, setOutbox] = useState<FileTransfer[]>([]);
  const [downloadingId, setDownloadingId] = useState<number | null>(null);
  const [snackbar, setSnackbar] = useState<string | null>(null);
  const refresh = useCallback(() => {
    if (!user) return;
    getInbox().then(setInbox).catch(() => setSnackbar("Failed to load inbox"));
    getOutbox().then(setOutbox).catch(() => setSnackbar("Failed to load outbox"));
  }, [user]);
  useEffect(() => {
    refresh();
  }, [refresh]);
  if (!user) {
    navigate("/bank-login", { replace: true });
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
  function handleLogout() {
    logout();
    navigate("/bank-login", { replace: true });
  }
  return (
    <Box sx={{ display: "flex", minHeight: "100vh", bgcolor: "background.default" }}>
      <AppBar
        position="fixed"
        elevation={0}
        sx={{ zIndex: (theme) => theme.zIndex.drawer + 1 }}
      >
        <Toolbar sx={{ gap: 2 }}>
          <Typography variant="subtitle1" sx={{ flexGrow: 1, fontWeight: 600 }}>
            Secure File Transfer — Bank Portal
          </Typography>
          <Typography variant="body2" sx={{ opacity: 0.85 }}>
            Signed in as {user.username}
          </Typography>
          <Button color="inherit" size="small" startIcon={<LogoutIcon />} onClick={handleLogout}>
            Sign out
          </Button>
        </Toolbar>
      </AppBar>
      <Drawer
        variant="permanent"
        sx={{
          width: DRAWER_WIDTH,
          flexShrink: 0,
          "& .MuiDrawer-paper": { width: DRAWER_WIDTH, boxSizing: "border-box" },
        }}
      >
        <Toolbar />
        <List sx={{ pt: 1 }}>
          {NAV_ITEMS.map((item) => (
            <ListItemButton
              key={item.section}
              selected={section === item.section}
              onClick={() => setSection(item.section)}
            >
              <ListItemIcon>{item.icon}</ListItemIcon>
              <ListItemText
                primary={
                  item.section === "inbox"
                    ? `Inbox (${inbox.length})`
                    : item.section === "outbox"
                      ? `Outbox (${outbox.length})`
                      : "Compose"
                }
              />
            </ListItemButton>
          ))}
        </List>
      </Drawer>
      <Box component="main" sx={{ flexGrow: 1, p: 3 }}>
        <Toolbar />
        {section === "inbox" && (
          <TransferTable
            mode="inbox"
            transfers={inbox}
            onDownload={handleDownload}
            downloadingId={downloadingId}
          />
        )}
        {section === "outbox" && <TransferTable mode="outbox" transfers={outbox} />}
        {section === "compose" && (
          <SlipComposerPanel
            currentUser={user}
            onSent={() => {
              setSnackbar("Slip generated and sent");
              refresh();
              setSection("outbox");
            }}
          />
        )}
      </Box>
      <Snackbar
        open={snackbar !== null}
        autoHideDuration={3000}
        onClose={() => setSnackbar(null)}
        message={snackbar}
      />
    </Box>
  );
}
