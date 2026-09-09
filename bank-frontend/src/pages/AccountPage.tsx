import { useCallback, useEffect, useState } from "react";
import {
  Alert,
  AppBar,
  Box,
  Button,
  Chip,
  Container,
  Paper,
  Snackbar,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Toolbar,
  Typography,
} from "@mui/material";
import LogoutIcon from "@mui/icons-material/LogoutOutlined";
import AddIcon from "@mui/icons-material/AddOutlined";
import SendIcon from "@mui/icons-material/SendOutlined";
import LinkIcon from "@mui/icons-material/LinkOutlined";
import DownloadIcon from "@mui/icons-material/DownloadOutlined";
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
import MoneyMovementDialog, { type MoneyMovementMode } from "../components/MoneyMovementDialog";
import Pager from "../components/Pager";
import { usePagedResource } from "../hooks/usePagedResource";
import RequestPaymentDialog from "../components/RequestPaymentDialog";
import CardPanel from "../components/CardPanel";
import type { Account, Payment, PaymentLink, Posting } from "../types";

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

/**
 * A customer's account view: the number, the balance, and the history it was derived from.
 *
 * The balance shown is whatever the ledger sums to right now, not a figure stored on the
 * account — which is why it and the posting list can never disagree.
 *
 * Two histories are shown rather than one: the transaction history is the ledger, and a
 * refused payment never reaches it, so payments are listed separately to make failures
 * and their reasons visible. Pay-by-link is not here yet.
 */
export default function AccountPage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [account, setAccount] = useState<Account | null>(null);
  // Each history is paged: postings and payments only ever accumulate for an account.
  const postings = usePagedResource<Posting>(getMyPostings);
  const payments = usePagedResource<Payment>(getMyPayments);
  const links = usePagedResource<PaymentLink>(getMyPaymentLinks);
  const [error, setError] = useState<string | null>(null);
  const [dialogMode, setDialogMode] = useState<MoneyMovementMode | null>(null);
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

  return (
    <Box sx={{ minHeight: "100vh", bgcolor: "background.default" }}>
      <AppBar position="static" elevation={0}>
        <Toolbar sx={{ gap: 2 }}>
          <Typography variant="subtitle1" sx={{ flexGrow: 1, fontWeight: 600 }}>
            Personal Banking
          </Typography>
          <Typography variant="body2" sx={{ opacity: 0.85, display: { xs: "none", sm: "block" } }}>
            Signed in as {user.username}
          </Typography>
          <Button color="inherit" size="small" startIcon={<LogoutIcon />} onClick={handleLogout}>
            Sign out
          </Button>
        </Toolbar>
      </AppBar>

      <Container maxWidth="md" sx={{ py: 4 }}>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        <Paper variant="outlined" sx={{ p: 3, mb: 3 }}>
          <Stack spacing={0.5}>
            <Typography variant="body2" color="text.secondary">
              Account number
            </Typography>
            <Typography variant="h6" sx={{ fontWeight: 600, letterSpacing: 1 }}>
              {account?.accountNumber ?? "—"}
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ pt: 1.5 }}>
              Available balance
            </Typography>
            <Typography variant="h4" sx={{ fontWeight: 700 }}>
              {account ? formatMoney(account.balance, account.currency) : "—"}
            </Typography>
            {/* Wraps rather than overflowing: three actions do not fit one phone-width row. */}
            <Stack direction="row" sx={{ pt: 2, flexWrap: "wrap", gap: 1.5 }}>
              <Button
                variant="contained"
                startIcon={<AddIcon />}
                onClick={() => setDialogMode("deposit")}
              >
                Deposit
              </Button>
              <Button
                variant="outlined"
                startIcon={<SendIcon />}
                onClick={() => setDialogMode("payment")}
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
            </Stack>
          </Stack>
        </Paper>

        <CardPanel onNotify={setSnackbar} />

        <Paper variant="outlined" sx={{ p: 2.5, mb: 3 }}>
          <Typography variant="subtitle1" sx={{ fontWeight: 600, mb: 1.5 }}>
            Download a statement
          </Typography>
          <Stack direction={{ xs: "column", sm: "row" }} spacing={1.5} sx={{ alignItems: "flex-start" }}>
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
        </Paper>

        <Typography variant="h6" sx={{ fontWeight: 600, mb: 2 }}>
          Transaction history
        </Typography>

        <Paper variant="outlined">
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
                      <Typography variant="body2" color="text.secondary" sx={{ py: 2 }}>
                        No transactions yet.
                      </Typography>
                    </TableCell>
                  </TableRow>
                ) : (
                  postings.items.map((posting) => {
                    const isCredit = posting.direction === "CREDIT";
                    return (
                      <TableRow key={posting.postingId}>
                        <TableCell>{formatDate(posting.postedAt)}</TableCell>
                        <TableCell>{posting.description ?? "—"}</TableCell>
                        <TableCell
                          align="right"
                          sx={{
                            fontWeight: 600,
                            color: isCredit ? "success.main" : "text.primary",
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
        </Paper>

        {/* Kept separate from the history above because a refused payment moved no money
            and so has no posting — this is the only place it is visible. */}
        <Typography variant="h6" sx={{ fontWeight: 600, mt: 4, mb: 2 }}>
          Payments sent
        </Typography>

        <Paper variant="outlined">
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
                      <Typography variant="body2" color="text.secondary" sx={{ py: 2 }}>
                        No payments sent yet.
                      </Typography>
                    </TableCell>
                  </TableRow>
                ) : (
                  payments.items.map((payment) => (
                    <TableRow key={payment.paymentId}>
                      <TableCell>{formatDate(payment.createdAt)}</TableCell>
                      <TableCell>{payment.toAccountNumber ?? "—"}</TableCell>
                      <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                        {account
                          ? formatMoney(payment.amount, account.currency)
                          : payment.amount}
                      </TableCell>
                      <TableCell>
                        {payment.status === "COMPLETED" ? (
                          <Chip size="small" color="success" label="Completed" />
                        ) : (
                          <Chip
                            size="small"
                            color="error"
                            variant="outlined"
                            label={payment.failureReason ?? "Failed"}
                          />
                        )}
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </TableContainer>
          <Pager {...payments} onPageChange={payments.setPage} />
        </Paper>
        <Typography variant="h6" sx={{ fontWeight: 600, mt: 4, mb: 2 }}>
          Payment requests
        </Typography>

        <Paper variant="outlined">
          <TableContainer>
            <Table size="small" sx={{ minWidth: 640 }}>
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
                      <Typography variant="body2" color="text.secondary" sx={{ py: 2 }}>
                        No payment requests yet.
                      </Typography>
                    </TableCell>
                  </TableRow>
                ) : (
                  links.items.map((link) => (
                    <TableRow key={link.linkId}>
                      <TableCell>{formatDate(link.createdAt)}</TableCell>
                      <TableCell>{link.description || "—"}</TableCell>
                      <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                        {account ? formatMoney(link.amount, account.currency) : link.amount}
                      </TableCell>
                      <TableCell>
                        <Chip
                          size="small"
                          label={LINK_STATUS_LABELS[link.status]}
                          color={link.status === "PAID" ? "success" : "default"}
                          variant={link.status === "PENDING" ? "filled" : "outlined"}
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
        </Paper>
      </Container>

      <MoneyMovementDialog
        open={dialogMode !== null}
        mode={dialogMode ?? "deposit"}
        onClose={() => setDialogMode(null)}
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
    </Box>
  );
}
