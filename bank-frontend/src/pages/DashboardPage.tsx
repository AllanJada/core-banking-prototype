import { useCallback, useEffect, useState } from "react";
import {
  Box,
  Button,
  Chip,
  Grid,
  Snackbar,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from "@mui/material";
import DescriptionIcon from "@mui/icons-material/DescriptionOutlined";
import DownloadIcon from "@mui/icons-material/DownloadOutlined";
import PersonAddIcon from "@mui/icons-material/PersonAddOutlined";
import DashboardRoundedIcon from "@mui/icons-material/DashboardRounded";
import PeopleRoundedIcon from "@mui/icons-material/PeopleRounded";
import PaymentsRoundedIcon from "@mui/icons-material/PaymentsRounded";
import ForumRoundedIcon from "@mui/icons-material/ForumRounded";
import InboxRoundedIcon from "@mui/icons-material/InboxRounded";
import OutboxRoundedIcon from "@mui/icons-material/OutboxRounded";
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
import DashboardLayout from "../components/layout/DashboardLayout";
import PageHeader from "../components/layout/PageHeader";
import StatCard from "../components/StatCard";
import SectionCard from "../components/SectionCard";
import { dataFontFamily } from "../theme";
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

/** Titles and the standing explanation for each section, shown in the page header. */
const SECTION_COPY: Record<Section, { title: string; description: string }> = {
  overview: {
    title: "Overview",
    description: "This institution's own customers, funds and standing at the Central Bank.",
  },
  customers: {
    title: "Customers",
    description: "Accounts opened at this institution, and the counter operations available on them.",
  },
  payments: {
    title: "Payments",
    description:
      "Payments made by this institution's customers, including refusals and the cause behind them.",
  },
  messages: {
    title: "Interbank messages",
    description:
      "The ISO 20022 messages this institution is party to. Each is re-hashed and signature-checked before it is served.",
  },
  inbox: {
    title: "Inbox",
    description: "Signed documents sent to this institution, awaiting review or already decided.",
  },
  outbox: {
    title: "Outbox",
    description: "Signed documents this institution has sent to other institutions.",
  },
};

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

  const [section, setSection] = useState<Section>("overview");
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

  const currency = summary?.currency ?? "TZS";
  const copy = SECTION_COPY[section];

  return (
    <DashboardLayout<Section>
      brand="Institution"
      brandDetail={summary?.institutionCode}
      username={user.username}
      roleLabel="Institution"
      onLogout={handleLogout}
      section={section}
      onSectionChange={setSection}
      sections={[
        { value: "overview", label: "Overview", icon: <DashboardRoundedIcon /> },
        {
          value: "customers",
          label: "Customers",
          icon: <PeopleRoundedIcon />,
          count: customers.totalElements,
        },
        {
          value: "payments",
          label: "Payments",
          icon: <PaymentsRoundedIcon />,
          count: payments.totalElements,
        },
        {
          value: "messages",
          label: "Messages",
          icon: <ForumRoundedIcon />,
          count: messages.totalElements,
        },
        { value: "inbox", label: "Inbox", icon: <InboxRoundedIcon />, count: inbox.totalElements },
        {
          value: "outbox",
          label: "Outbox",
          icon: <OutboxRoundedIcon />,
          count: outbox.totalElements,
        },
      ]}
    >
      <PageHeader
        context="Institution"
        title={copy.title}
        description={copy.description}
        actions={
          <>
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
          </>
        }
      />

      {section === "overview" && summary && (
        <Stack spacing={2}>
          <Grid container spacing={2}>
            <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
              <StatCard label="Customers" value={String(summary.customerCount)} />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
              <StatCard
                label="Customer funds held"
                value={money(summary.customerFundsHeld, summary.currency)}
                hint="summed from the ledger"
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
              <StatCard
                label="Settlement position"
                value={money(summary.settlementPosition, summary.currency)}
                hint={`account ${summary.settlementAccountNumber}`}
                tone={summary.settlementPosition < 0 ? "error" : "default"}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
              <StatCard
                label="Headroom"
                value={money(summary.headroom, summary.currency)}
                hint={`net debit cap ${money(summary.netDebitCap, summary.currency)}`}
              />
            </Grid>
          </Grid>

          <SectionCard title="What these figures mean">
            <Typography variant="body2" color="text.secondary">
              A negative settlement position means this bank currently owes the rest of the system.
              When the headroom runs out, its customers' payments to other banks are refused — and
              they are told only that the payment could not be settled, so the reason to act on is
              here.
            </Typography>
          </SectionCard>
        </Stack>
      )}

      {section === "customers" && (
        <SectionCard disablePadding>
          <TableContainer>
            <Table size="small" sx={{ minWidth: 720 }}>
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
                      <Typography variant="body2" color="text.secondary" sx={{ py: 3 }}>
                        No customers yet. Provisioning one opens their account here.
                      </Typography>
                    </TableCell>
                  </TableRow>
                )}
                {customers.items.map((customer) => (
                  <TableRow key={customer.userId}>
                    <TableCell sx={{ fontWeight: 500 }}>{customer.username}</TableCell>
                    <TableCell sx={{ fontFamily: dataFontFamily, letterSpacing: 0.3 }}>
                      {customer.accountNumber}
                    </TableCell>
                    <TableCell align="right" sx={{ whiteSpace: "nowrap", fontWeight: 500 }}>
                      {money(customer.balance, customer.currency)}
                    </TableCell>
                    <TableCell>
                      {customer.cardStatus ? (
                        <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                          <Chip
                            size="small"
                            label={`${customer.cardNumber} · ${customer.cardStatus}`}
                            color={customer.cardStatus === "ACTIVE" ? "success" : "default"}
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
                    <TableCell sx={{ whiteSpace: "nowrap" }}>
                      {formatDate(customer.openedAt)}
                    </TableCell>
                    <TableCell align="right">
                      <Button size="small" variant="outlined" onClick={() => setDepositFor(customer)}>
                        Deposit
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          <Pager {...customers} onPageChange={customers.setPage} />
        </SectionCard>
      )}

      {section === "payments" && (
        <SectionCard disablePadding>
          <TableContainer>
            <Table size="small" sx={{ minWidth: 760 }}>
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
                      <Typography variant="body2" color="text.secondary" sx={{ py: 3 }}>
                        No payments by this institution's customers yet.
                      </Typography>
                    </TableCell>
                  </TableRow>
                )}
                {payments.items.map((payment) => (
                  <TableRow key={payment.paymentId}>
                    <TableCell sx={{ whiteSpace: "nowrap" }}>
                      {formatDate(payment.createdAt)}
                    </TableCell>
                    <TableCell sx={{ fontWeight: 500 }}>{payment.customerUsername}</TableCell>
                    <TableCell>
                      <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                        <Box component="span" sx={{ fontFamily: dataFontFamily }}>
                          {payment.toAccountNumber ?? "—"}
                        </Box>
                        {/* Flagged when the money had to settle between banks. */}
                        {payment.interBank && payment.toInstitutionCode && (
                          <Chip size="small" color="info" label={payment.toInstitutionCode} />
                        )}
                      </Stack>
                    </TableCell>
                    <TableCell align="right" sx={{ whiteSpace: "nowrap", fontWeight: 500 }}>
                      {money(payment.amount, currency)}
                    </TableCell>
                    <TableCell>
                      {payment.status === "COMPLETED" ? (
                        <Chip size="small" color="success" label="Completed" />
                      ) : (
                        <Stack spacing={0.5} sx={{ alignItems: "flex-start" }}>
                          <Chip size="small" color="error" label={payment.failureReason ?? "Failed"} />
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
        </SectionCard>
      )}

      {section === "messages" && (
        <SectionCard disablePadding>
          <TableContainer>
            <Table size="small" sx={{ minWidth: 760 }}>
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
                      <Typography variant="body2" color="text.secondary" sx={{ py: 3 }}>
                        No interbank messages yet. One is written for every payment that crosses
                        banks; payments within this bank need none.
                      </Typography>
                    </TableCell>
                  </TableRow>
                )}
                {messages.items.map((message) => (
                  <TableRow key={message.messageId}>
                    <TableCell sx={{ whiteSpace: "nowrap" }}>
                      {formatDate(message.createdAt)}
                    </TableCell>
                    <TableCell>
                      <Chip
                        size="small"
                        color={message.direction === "SENT" ? "warning" : "info"}
                        label={message.direction === "SENT" ? "Sent" : "Received"}
                      />
                    </TableCell>
                    <TableCell sx={{ fontWeight: 500 }}>
                      {message.direction === "SENT"
                        ? message.creditorAgentCode
                        : message.debtorAgentCode}
                    </TableCell>
                    <TableCell align="right" sx={{ whiteSpace: "nowrap", fontWeight: 500 }}>
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
        </SectionCard>
      )}

      {section === "inbox" && (
        <SectionCard disablePadding>
          <TransferTable
            mode="inbox"
            transfers={inbox.items}
            onDownload={handleDownload}
            onDownloadPayload={handleDownloadPayload}
            onReview={setReviewTransfer}
            downloadingId={downloadingId}
          />
          <Pager {...inbox} onPageChange={inbox.setPage} />
        </SectionCard>
      )}

      {section === "outbox" && (
        <SectionCard disablePadding>
          <TransferTable mode="outbox" transfers={outbox.items} />
          <Pager {...outbox} onPageChange={outbox.setPage} />
        </SectionCard>
      )}

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
    </DashboardLayout>
  );
}
