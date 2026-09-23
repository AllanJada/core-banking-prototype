import { useCallback, useEffect, useState } from "react";
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
import DownloadIcon from "@mui/icons-material/DownloadOutlined";
import LogoutIcon from "@mui/icons-material/LogoutOutlined";
import PersonAddIcon from "@mui/icons-material/PersonAddOutlined";
import { useNavigate } from "react-router-dom";
import {
  downloadFile,
  downloadInstitutionSettlementMessage,
  downloadPayload,
  getInbox,
  getInstitutionPayments,
  getInstitutionSettlementMessages,
  getInstitutionSummary,
  getMyCustomers,
  getOutbox,
  unblockCustomerCard,
} from "../api/client";
import { useAuth } from "../context/AuthContext";
import TransferTable from "../components/TransferTable";
import Pager from "../components/Pager";
import TakeDepositDialog from "../components/TakeDepositDialog";
import { usePagedResource } from "../hooks/usePagedResource";
import ProvisionCustomerDialog from "../components/ProvisionCustomerDialog";
import SlipComposerDialog from "../components/SlipComposerDialog";
import TransferReviewDialog from "../components/TransferReviewDialog";
import type {
  Customer,
  FileTransfer,
  InstitutionPayment,
  InstitutionSummary,
  SettlementMessage,
} from "../types";

type Section = "overview" | "customers" | "payments" | "messages" | "inbox" | "outbox";

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

/** One figure on the overview. */
function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <Paper variant="outlined" sx={{ p: 2.5, flex: 1, minWidth: 190 }}>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="h5" sx={{ fontWeight: 700, mt: 0.5 }}>
        {value}
      </Typography>
      {hint && (
        <Typography variant="caption" color="text.secondary">
          {hint}
        </Typography>
      )}
    </Paper>
  );
}

/**
 * An institution's console: its own customers and their payments, its position at the Central
 * Bank, and signed document exchange with other institutions.
 *
 * Everything here is scoped to the signed-in institution by the backend, so there is no
 * filter in this page that could be got wrong. It is also the only screen that shows why a
 * payment could not be settled — the customer who attempted it is told only that it could
 * not be.
 */
export default function DashboardPage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [tab, setTab] = useState<Section>("overview");
  const [summary, setSummary] = useState<InstitutionSummary | null>(null);
  const [customerDialogOpen, setCustomerDialogOpen] = useState(false);
  const [slipDialogOpen, setSlipDialogOpen] = useState(false);
  const [reviewTransfer, setReviewTransfer] = useState<FileTransfer | null>(null);
  const [downloadingId, setDownloadingId] = useState<number | null>(null);
  const [unblockingId, setUnblockingId] = useState<number | null>(null);
  // The customer a deposit is being taken for; null when the dialog is closed.
  const [depositFor, setDepositFor] = useState<Customer | null>(null);
  const [snackbar, setSnackbar] = useState<string | null>(null);

  // No userId argument anywhere: the backend scopes every one of these to whoever the token
  // says we are, and returns one page at a time rather than the whole list.
  const customers = usePagedResource<Customer>(getMyCustomers);
  const payments = usePagedResource<InstitutionPayment>(getInstitutionPayments);
  const messages = usePagedResource<SettlementMessage>(getInstitutionSettlementMessages);
  const inbox = usePagedResource<FileTransfer>(getInbox);
  const outbox = usePagedResource<FileTransfer>(getOutbox);

  const loadSummary = useCallback(() => {
    getInstitutionSummary()
      .then(setSummary)
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    loadSummary();
  }, [loadSummary]);

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

  async function handleDownloadMessage(message: SettlementMessage) {
    try {
      await downloadInstitutionSettlementMessage(message.messageId, message.uetr);
    } catch (err) {
      setSnackbar(err instanceof Error ? err.message : "Could not download the settlement message");
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
            Institution{summary ? ` · ${summary.institutionCode}` : ""}
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

      <Container maxWidth="lg" sx={{ py: 4 }}>
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
            <Tab label="Overview" value="overview" />
            <Tab label={`Customers (${customers.totalElements})`} value="customers" />
            <Tab label={`Payments (${payments.totalElements})`} value="payments" />
            <Tab label={`Messages (${messages.totalElements})`} value="messages" />
            <Tab label={`Inbox (${inbox.totalElements})`} value="inbox" />
            <Tab label={`Outbox (${outbox.totalElements})`} value="outbox" />
          </Tabs>

          <Box sx={{ p: 2 }}>
            {tab === "overview" && summary && (
              <Stack spacing={2}>
                <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap", gap: 2 }}>
                  <Stat label="Customers" value={String(summary.customerCount)} />
                  <Stat
                    label="Customer funds held"
                    value={money(summary.customerFundsHeld, summary.currency)}
                    hint="summed from the ledger"
                  />
                  <Stat
                    label="Settlement position"
                    value={money(summary.settlementPosition, summary.currency)}
                    hint={`account ${summary.settlementAccountNumber}`}
                  />
                  <Stat
                    label="Headroom"
                    value={money(summary.headroom, summary.currency)}
                    hint={`net debit cap ${money(summary.netDebitCap, summary.currency)}`}
                  />
                </Stack>
                <Typography variant="body2" color="text.secondary">
                  A negative settlement position means this bank currently owes the rest of the
                  system. When the headroom runs out, its customers' payments to other banks are
                  refused — and they are told only that the payment could not be settled, so the
                  reason to act on is here.
                </Typography>
              </Stack>
            )}

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
                        <TableCell align="right">At the counter</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {customers.items.length === 0 && !customers.loading && (
                        <TableRow>
                          <TableCell colSpan={6}>
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
                          <TableCell align="right">
                            <Button size="small" onClick={() => setDepositFor(customer)}>
                              Deposit
                            </Button>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
                <Pager {...customers} onPageChange={customers.setPage} />
              </>
            )}

            {tab === "payments" && (
              <>
                <TableContainer>
                  <Table size="small" sx={{ minWidth: 720 }}>
                    <TableHead>
                      <TableRow>
                        <TableCell>Date</TableCell>
                        <TableCell>Customer</TableCell>
                        <TableCell>To</TableCell>
                        <TableCell align="right">Amount</TableCell>
                        <TableCell>Outcome</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {payments.items.length === 0 && !payments.loading && (
                        <TableRow>
                          <TableCell colSpan={5}>
                            <Typography variant="body2" color="text.secondary">
                              No payments by this institution's customers yet.
                            </Typography>
                          </TableCell>
                        </TableRow>
                      )}
                      {payments.items.map((payment) => (
                        <TableRow key={payment.paymentId}>
                          <TableCell>{formatDate(payment.createdAt)}</TableCell>
                          <TableCell>{payment.customerUsername}</TableCell>
                          <TableCell>
                            <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                              <span>{payment.toAccountNumber ?? "—"}</span>
                              {/* Flagged when the money had to settle between banks. */}
                              {payment.interBank && payment.toInstitutionCode && (
                                <Chip
                                  size="small"
                                  variant="outlined"
                                  color="info"
                                  label={payment.toInstitutionCode}
                                />
                              )}
                            </Stack>
                          </TableCell>
                          <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                            {money(payment.amount, summary?.currency ?? "TZS")}
                          </TableCell>
                          <TableCell>
                            {payment.status === "COMPLETED" ? (
                              <Chip size="small" color="success" label="Completed" />
                            ) : (
                              <Stack spacing={0.5}>
                                <Chip
                                  size="small"
                                  color="error"
                                  variant="outlined"
                                  label={payment.failureReason ?? "Failed"}
                                />
                                {/* What the customer was not told, and this bank needs. */}
                                {payment.failureDetail && (
                                  <Typography variant="caption" color="text.secondary">
                                    {payment.failureDetail}
                                  </Typography>
                                )}
                              </Stack>
                            )}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
                <Pager {...payments} onPageChange={payments.setPage} />
              </>
            )}

            {tab === "messages" && (
              <>
                <TableContainer>
                  <Table size="small" sx={{ minWidth: 720 }}>
                    <TableHead>
                      <TableRow>
                        <TableCell>Date</TableCell>
                        <TableCell>Direction</TableCell>
                        <TableCell>Counterparty bank</TableCell>
                        <TableCell align="right">Amount</TableCell>
                        <TableCell>Message</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {messages.items.length === 0 && !messages.loading && (
                        <TableRow>
                          <TableCell colSpan={5}>
                            <Typography variant="body2" color="text.secondary">
                              No interbank messages yet. One is written for every payment that
                              crosses banks; payments within this bank need none.
                            </Typography>
                          </TableCell>
                        </TableRow>
                      )}
                      {messages.items.map((message) => (
                        <TableRow key={message.messageId}>
                          <TableCell>{formatDate(message.createdAt)}</TableCell>
                          <TableCell>
                            <Chip
                              size="small"
                              variant="outlined"
                              color={message.direction === "SENT" ? "warning" : "info"}
                              label={message.direction === "SENT" ? "Sent" : "Received"}
                            />
                          </TableCell>
                          <TableCell>
                            {message.direction === "SENT"
                              ? message.creditorAgentCode
                              : message.debtorAgentCode}
                          </TableCell>
                          <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                            {money(message.amount, message.currency)}
                          </TableCell>
                          <TableCell>
                            {/* Re-hashed and signature-checked server-side before it is served. */}
                            <Button
                              size="small"
                              startIcon={<DownloadIcon />}
                              onClick={() => handleDownloadMessage(message)}
                            >
                              {message.messageType}
                            </Button>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
                <Pager {...messages} onPageChange={messages.setPage} />
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

      <TakeDepositDialog
        open={depositFor !== null}
        customer={depositFor}
        onClose={() => setDepositFor(null)}
        onCompleted={(message) => {
          setSnackbar(message);
          // Both: the customer's balance changed, and so did this institution's own cash
          // position, which the overview reads.
          customers.refresh();
          loadSummary();
        }}
      />

      <ProvisionCustomerDialog
        open={customerDialogOpen}
        onClose={() => setCustomerDialogOpen(false)}
        onProvisioned={(customer) => {
          setSnackbar(`${customer.username} created — account ${customer.accountNumber}`);
          customers.refresh();
          loadSummary();
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
