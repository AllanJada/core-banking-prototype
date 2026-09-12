import { useCallback, useState } from "react";
import {
  AppBar,
  Box,
  Button,
  Chip,
  Container,
  Paper,
  Snackbar,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tabs,
  Toolbar,
  Typography,
} from "@mui/material";
import DescriptionIcon from "@mui/icons-material/DescriptionOutlined";
import LogoutIcon from "@mui/icons-material/LogoutOutlined";
import PersonAddIcon from "@mui/icons-material/PersonAddOutlined";
import { useNavigate } from "react-router-dom";
import {
  downloadFile,
  downloadPayload,
  getInbox,
  getMyCustomers,
  getOutbox,
  unblockCustomerCard,
} from "../api/client";
import { useAuth } from "../context/AuthContext";
import TransferTable from "../components/TransferTable";
import Pager from "../components/Pager";
import { usePagedResource } from "../hooks/usePagedResource";
import ProvisionCustomerDialog from "../components/ProvisionCustomerDialog";
import SlipComposerDialog from "../components/SlipComposerDialog";
import TransferReviewDialog from "../components/TransferReviewDialog";
import type { Customer, FileTransfer } from "../types";

type Section = "customers" | "inbox" | "outbox";

function money(amount: number, currency: string): string {
  return new Intl.NumberFormat(undefined, {
    style: "currency",
    currency,
    currencyDisplay: "code",
  }).format(amount);
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}

/**
 * An institution's console: its own customers, and signed document exchange with other
 * institutions.
 *
 * The customer list is only ever this institution's. The backend scopes it to the signed-in
 * institution's token, so there is no filter here that could be got wrong.
 */
export default function DashboardPage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [tab, setTab] = useState<Section>("customers");
  const [customerDialogOpen, setCustomerDialogOpen] = useState(false);
  const [slipDialogOpen, setSlipDialogOpen] = useState(false);
  const [reviewTransfer, setReviewTransfer] = useState<FileTransfer | null>(null);
  const [downloadingId, setDownloadingId] = useState<number | null>(null);
  const [unblockingId, setUnblockingId] = useState<number | null>(null);
  const [snackbar, setSnackbar] = useState<string | null>(null);

  // No userId argument: the backend scopes all three to whoever the token says we are, and
  // returns one page at a time rather than the whole list.
  const customers = usePagedResource<Customer>(getMyCustomers);
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

  async function handleUnblock(customer: Customer) {
    setUnblockingId(customer.userId);
    try {
      await unblockCustomerCard(customer.userId);
      setSnackbar(`Card unblocked for ${customer.username}`);
      customers.refresh();
    } catch (err) {
      setSnackbar(err instanceof Error ? err.message : "Could not unblock the card");
    } finally {
      setUnblockingId(null);
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
            Institution
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
        <Stack direction="row" spacing={1} sx={{ justifyContent: "flex-end", flexWrap: "wrap", gap: 1, mb: 2 }}>
          <Button
            variant="outlined"
            startIcon={<PersonAddIcon />}
            onClick={() => setCustomerDialogOpen(true)}
          >
            Provision customer
          </Button>
          <Button
            variant="contained"
            startIcon={<DescriptionIcon />}
            onClick={() => setSlipDialogOpen(true)}
          >
            Compose slip
          </Button>
        </Stack>

        <Paper variant="outlined">
          <Tabs
            value={tab}
            onChange={(_, value) => setTab(value)}
            variant="scrollable"
            scrollButtons="auto"
            allowScrollButtonsMobile
            sx={{ px: 2, borderBottom: 1, borderColor: "divider" }}
          >
            <Tab label={`Customers (${customers.totalElements})`} value="customers" />
            <Tab label={`Inbox (${inbox.totalElements})`} value="inbox" />
            <Tab label={`Outbox (${outbox.totalElements})`} value="outbox" />
          </Tabs>

          <Box sx={{ p: 2 }}>
            {tab === "customers" && (
              <>
                <TableContainer>
                  <Table size="small" sx={{ minWidth: 640 }}>
                    <TableHead>
                      <TableRow>
                        <TableCell>Customer</TableCell>
                        <TableCell>Account number</TableCell>
                        <TableCell align="right">Balance</TableCell>
                        <TableCell>Card</TableCell>
                        <TableCell>Opened</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {customers.items.length === 0 && !customers.loading && (
                        <TableRow>
                          <TableCell colSpan={5}>
                            <Typography variant="body2" color="text.secondary">
                              No customers yet. Provisioning one opens their account here.
                            </Typography>
                          </TableCell>
                        </TableRow>
                      )}
                      {customers.items.map((customer) => (
                        <TableRow key={customer.userId}>
                          <TableCell>{customer.username}</TableCell>
                          <TableCell sx={{ letterSpacing: 0.5 }}>{customer.accountNumber}</TableCell>
                          <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                            {money(customer.balance, customer.currency)}
                          </TableCell>
                          <TableCell>
                            {customer.cardStatus ? (
                              <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                                <Chip
                                  size="small"
                                  label={`${customer.cardNumber} · ${customer.cardStatus}`}
                                  color={customer.cardStatus === "ACTIVE" ? "success" : "default"}
                                  variant="outlined"
                                />
                                {customer.cardStatus === "BLOCKED" && (
                                  <Button
                                    size="small"
                                    onClick={() => handleUnblock(customer)}
                                    disabled={unblockingId === customer.userId}
                                  >
                                    {unblockingId === customer.userId ? "Unblocking…" : "Unblock"}
                                  </Button>
                                )}
                              </Stack>
                            ) : (
                              <Typography variant="body2" color="text.secondary">
                                none
                              </Typography>
                            )}
                          </TableCell>
                          <TableCell>{formatDate(customer.openedAt)}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
                <Pager {...customers} onPageChange={customers.setPage} />
              </>
            )}

            {tab === "inbox" && (
              <>
                <TransferTable
                  mode="inbox"
                  transfers={inbox.items}
                  onDownload={handleDownload}
                  onDownloadPayload={handleDownloadPayload}
                  onReview={setReviewTransfer}
                  downloadingId={downloadingId}
                />
                <Pager {...inbox} onPageChange={inbox.setPage} />
              </>
            )}

            {tab === "outbox" && (
              <>
                <TransferTable mode="outbox" transfers={outbox.items} />
                <Pager {...outbox} onPageChange={outbox.setPage} />
              </>
            )}
          </Box>
        </Paper>
      </Container>

      <ProvisionCustomerDialog
        open={customerDialogOpen}
        onClose={() => setCustomerDialogOpen(false)}
        onProvisioned={(customer) => {
          setSnackbar(`${customer.username} created — account ${customer.accountNumber}`);
          customers.refresh();
        }}
      />

      <TransferReviewDialog
        transfer={reviewTransfer}
        onClose={() => setReviewTransfer(null)}
        onDecided={(message) => {
          setSnackbar(message);
          setReviewTransfer(null);
          refresh();
        }}
      />

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
        autoHideDuration={4000}
        onClose={() => setSnackbar(null)}
        message={snackbar}
      />
    </Box>
  );
}
