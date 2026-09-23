import { useCallback, useEffect, useState } from "react";
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
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
  TextField,
  Typography,
} from "@mui/material";
import SendIcon from "@mui/icons-material/SendOutlined";
import LinkIcon from "@mui/icons-material/LinkOutlined";
import DownloadIcon from "@mui/icons-material/DownloadOutlined";
import DashboardRoundedIcon from "@mui/icons-material/DashboardRounded";
import ReceiptLongRoundedIcon from "@mui/icons-material/ReceiptLongRounded";
import PaymentsRoundedIcon from "@mui/icons-material/PaymentsRounded";
import LinkRoundedIcon from "@mui/icons-material/LinkRounded";
import { useNavigate } from "react-router-dom";
import {
  cancelPaymentLink,
  downloadStatement,
  getMyAccount,
  getMyPaymentLinks,
  getMyPayments,
  getMyPostings,
} from "../api/client";
import { useAuth } from "../context/AuthContext";
import SendMoneyDialog from "../components/SendMoneyDialog";
import Pager from "../components/Pager";
import { usePagedResource } from "../hooks/usePagedResource";
import RequestPaymentDialog from "../components/RequestPaymentDialog";
import CardPanel from "../components/CardPanel";
import DashboardLayout from "../components/layout/DashboardLayout";
import PageHeader from "../components/layout/PageHeader";
import SectionCard from "../components/SectionCard";
import { dataFontFamily } from "../theme";
import type { Account, Payment, PaymentLink, Posting } from "../types";

type Section = "overview" | "transactions" | "payments" | "requests";

function formatMoney(amount: number, currency: string): string {
  return new Intl.NumberFormat(undefined, {
    style: "currency",
    currency,
    currencyDisplay: "code",
  }).format(amount);
}

const LINK_STATUS_LABELS: Record<PaymentLink["status"], string> = {
  PENDING: "Open",
  PAID: "Paid",
  CANCELLED: "Cancelled",
  EXPIRED: "Expired",
};

/**
 * A date as yyyy-MM-dd, the format a date input and the statement endpoint both use.
 *
 * Built from local components rather than via toISOString(), which converts to UTC first
 * and so reports the previous day for any local time before the UTC offset — the first of
 * the month would come out as the last day of the month before.
 */
function isoDay(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

const now = new Date();
const today = isoDay(now);
const firstOfThisMonth = isoDay(new Date(now.getFullYear(), now.getMonth(), 1));

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}

const SECTION_COPY: Record<Section, { title: string; description: string }> = {
  overview: {
    title: "Overview",
    description: "Your account, your card, and a statement for any period you choose.",
  },
  transactions: {
    title: "Transaction history",
    description:
      "Every line the ledger holds for this account. The balance above is the sum of exactly these.",
  },
  payments: {
    title: "Payments sent",
    description:
      "Including payments that were refused — those moved no money, so they appear here and not in the history.",
  },
  requests: {
    title: "Payment requests",
    description: "Links you have created asking someone else to pay you.",
  },
};

/**
 * A customer's account view: the number, the balance, and the history it was derived from.
 *
 * The balance shown is whatever the ledger sums to right now, not a figure stored on the
 * account — which is why it and the posting list can never disagree.
 *
 * Two histories are shown rather than one: the transaction history is the ledger, and a
 * refused payment never reaches it, so payments are listed separately to make failures
 * and their reasons visible.
 */
export default function AccountPage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [section, setSection] = useState<Section>("overview");
  const [account, setAccount] = useState<Account | null>(null);
  // Each history is paged: postings and payments only ever accumulate for an account.
  const postings = usePagedResource<Posting>(getMyPostings);
  const payments = usePagedResource<Payment>(getMyPayments);
  const links = usePagedResource<PaymentLink>(getMyPaymentLinks);
  const [error, setError] = useState<string | null>(null);
  const [paymentDialogOpen, setPaymentDialogOpen] = useState(false);
  const [requestDialogOpen, setRequestDialogOpen] = useState(false);
  const [snackbar, setSnackbar] = useState<string | null>(null);
  // Defaults to the current month so far, the range most people want.
  const [statementFrom, setStatementFrom] = useState(firstOfThisMonth);
  const [statementTo, setStatementTo] = useState(today);
  const [downloadingStatement, setDownloadingStatement] = useState(false);

  // Re-read all three together after money moves: the balance is derived from the
  // postings, so refreshing one without the others would show them disagreeing.
  const refresh = useCallback(() => {
    getMyAccount()
      .then(setAccount)
      .catch((err) => setError(err instanceof Error ? err.message : "Failed to load account"));
    postings.refresh();
    payments.refresh();
    links.refresh();
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  async function handleDownloadStatement() {
    setDownloadingStatement(true);
    try {
      await downloadStatement(statementFrom, statementTo);
    } catch (err) {
      setSnackbar(err instanceof Error ? err.message : "Could not generate the statement");
    } finally {
      setDownloadingStatement(false);
    }
  }

  async function handleCopyLink(link: PaymentLink) {
    try {
      await navigator.clipboard.writeText(`${window.location.origin}/pay/${link.linkId}`);
      setSnackbar("Link copied");
    } catch {
      // Clipboard access can be refused on a non-secure origin; show the URL instead of
      // failing silently, so it can still be copied by hand.
      setSnackbar(`${window.location.origin}/pay/${link.linkId}`);
    }
  }

  async function handleCancelLink(link: PaymentLink) {
    try {
      await cancelPaymentLink(link.linkId);
      setSnackbar("Payment request cancelled");
      refresh();
    } catch (err) {
      setSnackbar(err instanceof Error ? err.message : "Could not cancel the request");
    }
  }

  function handleLogout() {
    logout();
    navigate("/login", { replace: true });
  }

  if (!user) return null;
  const copy = SECTION_COPY[section];

  return (
    <DashboardLayout<Section>
      brand="Personal banking"
      brandDetail={account?.institutionName}
      username={user.username}
      roleLabel="Customer"
      onLogout={handleLogout}
      section={section}
      onSectionChange={setSection}
      sections={[
        { value: "overview", label: "Overview", icon: <DashboardRoundedIcon /> },
        {
          value: "transactions",
          label: "Transactions",
          icon: <ReceiptLongRoundedIcon />,
          count: postings.totalElements,
        },
        {
          value: "payments",
          label: "Payments",
          icon: <PaymentsRoundedIcon />,
          count: payments.totalElements,
        },
        {
          value: "requests",
          label: "Requests",
          icon: <LinkRoundedIcon />,
          count: links.totalElements,
        },
      ]}
    >
      <PageHeader
        context="Personal banking"
        title={copy.title}
        description={copy.description}
        actions={
          <>
            {/* There is deliberately no Deposit button here — paying money in is done at
                the counter by the bank, because an account holder who can credit their
                own account can create money. */}
            <Button
              variant="contained"
              startIcon={<SendIcon />}
              onClick={() => setPaymentDialogOpen(true)}
            >
              Send money
            </Button>
            <Button
              variant="outlined"
              startIcon={<LinkIcon />}
              onClick={() => setRequestDialogOpen(true)}
            >
              Request payment
            </Button>
          </>
        }
      />

      {error && <Alert severity="error">{error}</Alert>}

      {section === "overview" && (
        <Grid container spacing={2.5}>
          <Grid size={{ xs: 12, md: 7 }}>
            {/* The balance is the headline figure of this whole screen, so it gets the
                one filled surface in the app rather than another outlined card. */}
            <Card
              variant="outlined"
              sx={{
                height: "100%",
                color: "primary.contrastText",
                borderColor: "transparent",
                backgroundImage: (theme) =>
                  `linear-gradient(135deg, ${theme.palette.primary.main} 0%, ${theme.palette.primary.dark} 100%)`,
              }}
            >
              <CardContent>
                <Stack spacing={0.5}>
                  <Typography variant="caption" sx={{ opacity: 0.75 }}>
                    Your bank
                  </Typography>
                  <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
                    {account ? `${account.institutionName} (${account.institutionCode})` : "—"}
                  </Typography>

                  <Typography variant="caption" sx={{ opacity: 0.75, pt: 1.5 }}>
                    Account number
                  </Typography>
                  <Typography
                    variant="h6"
                    sx={{ fontFamily: dataFontFamily, letterSpacing: 1, fontWeight: 500 }}
                  >
                    {account?.accountNumber ?? "—"}
                  </Typography>

                  <Typography variant="caption" sx={{ opacity: 0.75, pt: 1.5 }}>
                    Available balance
                  </Typography>
                  <Typography variant="h3" sx={{ fontWeight: 600 }}>
                    {account ? formatMoney(account.balance, account.currency) : "—"}
                  </Typography>
                  <Typography variant="caption" sx={{ opacity: 0.75, pt: 0.5 }}>
                    Summed from this account's postings every time this page loads — never a
                    stored figure.
                  </Typography>
                </Stack>
              </CardContent>
            </Card>
          </Grid>

          <Grid size={{ xs: 12, md: 5 }}>
            <CardPanel onNotify={setSnackbar} />
          </Grid>

          <Grid size={{ xs: 12 }}>
            <SectionCard
              title="Download a statement"
              description="A signed PDF for any period, verifiable against your bank's public key."
            >
              <Stack
                direction={{ xs: "column", sm: "row" }}
                spacing={1.5}
                sx={{ alignItems: "flex-start" }}
              >
                <TextField
                  label="From"
                  type="date"
                  size="small"
                  value={statementFrom}
                  onChange={(e) => setStatementFrom(e.target.value)}
                  slotProps={{ inputLabel: { shrink: true } }}
                />
                <TextField
                  label="To"
                  type="date"
                  size="small"
                  value={statementTo}
                  onChange={(e) => setStatementTo(e.target.value)}
                  slotProps={{ inputLabel: { shrink: true } }}
                />
                <Button
                  variant="outlined"
                  startIcon={<DownloadIcon />}
                  onClick={handleDownloadStatement}
                  disabled={downloadingStatement}
                >
                  {downloadingStatement ? "Preparing…" : "Download PDF"}
                </Button>
              </Stack>
            </SectionCard>
          </Grid>
        </Grid>
      )}

      {section === "transactions" && (
        <SectionCard disablePadding>
          <TableContainer>
            <Table size="small" sx={{ minWidth: 640 }}>
              <TableHead>
                <TableRow>
                  <TableCell>Date</TableCell>
                  <TableCell>Description</TableCell>
                  <TableCell align="right">Amount</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {postings.items.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={3}>
                      <Typography variant="body2" color="text.secondary" sx={{ py: 3 }}>
                        No transactions yet.
                      </Typography>
                    </TableCell>
                  </TableRow>
                ) : (
                  postings.items.map((posting) => {
                    const isCredit = posting.direction === "CREDIT";
                    return (
                      <TableRow key={posting.postingId}>
                        <TableCell sx={{ whiteSpace: "nowrap" }}>
                          {formatDate(posting.postedAt)}
                        </TableCell>
                        <TableCell>{posting.description ?? "—"}</TableCell>
                        <TableCell
                          align="right"
                          sx={{
                            fontWeight: 600,
                            color: isCredit ? "success.dark" : "text.primary",
                            whiteSpace: "nowrap",
                          }}
                        >
                          {isCredit ? "+" : "−"}
                          {account ? formatMoney(posting.amount, account.currency) : posting.amount}
                        </TableCell>
                      </TableRow>
                    );
                  })
                )}
              </TableBody>
            </Table>
          </TableContainer>
          <Pager {...postings} onPageChange={postings.setPage} />
        </SectionCard>
      )}

      {section === "payments" && (
        <SectionCard disablePadding>
          <TableContainer>
            <Table size="small" sx={{ minWidth: 640 }}>
              <TableHead>
                <TableRow>
                  <TableCell>Date</TableCell>
                  <TableCell>To</TableCell>
                  <TableCell align="right">Amount</TableCell>
                  <TableCell>Outcome</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {payments.items.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={4}>
                      <Typography variant="body2" color="text.secondary" sx={{ py: 3 }}>
                        No payments sent yet.
                      </Typography>
                    </TableCell>
                  </TableRow>
                ) : (
                  payments.items.map((payment) => (
                    <TableRow key={payment.paymentId}>
                      <TableCell sx={{ whiteSpace: "nowrap" }}>
                        {formatDate(payment.createdAt)}
                      </TableCell>
                      <TableCell>
                        <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                          <Box component="span" sx={{ fontFamily: dataFontFamily }}>
                            {payment.toAccountNumber ?? "—"}
                          </Box>
                          {/* Named only when the money left this customer's own bank, which
                              is the case that settles between institutions. */}
                          {payment.toInstitutionCode &&
                            account &&
                            payment.toInstitutionCode !== account.institutionCode && (
                              <Chip size="small" color="info" label={payment.toInstitutionCode} />
                            )}
                        </Stack>
                      </TableCell>
                      <TableCell align="right" sx={{ whiteSpace: "nowrap", fontWeight: 500 }}>
                        {account ? formatMoney(payment.amount, account.currency) : payment.amount}
                      </TableCell>
                      <TableCell>
                        {payment.status === "COMPLETED" ? (
                          <Chip size="small" color="success" label="Completed" />
                        ) : (
                          <Chip size="small" color="error" label={payment.failureReason ?? "Failed"} />
                        )}
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </TableContainer>
          <Pager {...payments} onPageChange={payments.setPage} />
        </SectionCard>
      )}

      {section === "requests" && (
        <SectionCard disablePadding>
          <TableContainer>
            <Table size="small" sx={{ minWidth: 680 }}>
              <TableHead>
                <TableRow>
                  <TableCell>Created</TableCell>
                  <TableCell>For</TableCell>
                  <TableCell align="right">Amount</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell align="right">Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {links.items.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={5}>
                      <Typography variant="body2" color="text.secondary" sx={{ py: 3 }}>
                        No payment requests yet.
                      </Typography>
                    </TableCell>
                  </TableRow>
                ) : (
                  links.items.map((link) => (
                    <TableRow key={link.linkId}>
                      <TableCell sx={{ whiteSpace: "nowrap" }}>
                        {formatDate(link.createdAt)}
                      </TableCell>
                      <TableCell>{link.description || "—"}</TableCell>
                      <TableCell align="right" sx={{ whiteSpace: "nowrap", fontWeight: 500 }}>
                        {account ? formatMoney(link.amount, account.currency) : link.amount}
                      </TableCell>
                      <TableCell>
                        <Chip
                          size="small"
                          label={LINK_STATUS_LABELS[link.status]}
                          color={
                            link.status === "PAID"
                              ? "success"
                              : link.status === "PENDING"
                                ? "warning"
                                : "default"
                          }
                        />
                      </TableCell>
                      <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                        <Button size="small" onClick={() => handleCopyLink(link)}>
                          Copy
                        </Button>
                        {/* Only an open request can be withdrawn — a paid one cannot be
                            undone, and the others are already closed. */}
                        {link.status === "PENDING" && (
                          <Button size="small" color="error" onClick={() => handleCancelLink(link)}>
                            Cancel
                          </Button>
                        )}
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </TableContainer>
          <Pager {...links} onPageChange={links.setPage} />
        </SectionCard>
      )}

      <SendMoneyDialog
        open={paymentDialogOpen}
        onClose={() => setPaymentDialogOpen(false)}
        onCompleted={(message) => {
          setSnackbar(message);
          refresh();
        }}
      />

      <RequestPaymentDialog
        open={requestDialogOpen}
        onClose={() => setRequestDialogOpen(false)}
        onCreated={refresh}
      />

      <Snackbar
        open={snackbar !== null}
        autoHideDuration={3000}
        onClose={() => setSnackbar(null)}
        message={snackbar}
      />
    </DashboardLayout>
  );
}
