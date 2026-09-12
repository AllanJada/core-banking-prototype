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
import LogoutIcon from "@mui/icons-material/LogoutOutlined";
import PersonAddIcon from "@mui/icons-material/PersonAddOutlined";
import { useNavigate } from "react-router-dom";
import {
  getAdminInstitutions,
  getAdminPayments,
  getAdminSummary,
  getAdminTransfers,
} from "../api/client";
import { useAuth } from "../context/AuthContext";
import ProvisionAccountDialog from "../components/ProvisionAccountDialog";
import Pager from "../components/Pager";
import { usePagedResource } from "../hooks/usePagedResource";
import SignatureChip from "../components/SignatureChip";
import type { AdminSummary, FileTransfer, Institution, Payment } from "../types";

type Section = "overview" | "institutions" | "payments" | "transfers";

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
 * The Central Bank's console.
 *
 * This role supervises institutions rather than taking part in banking: everything here is
 * read-only except provisioning institutions and other overseers. There is deliberately no
 * way to create a customer, adjust a balance, reverse a payment or unblock a card from this
 * screen — customers belong to their institution.
 *
 * Institutions are shown as aggregates only. How much an institution's customers hold between
 * them is supervision; who they are stays with their own bank.
 */
export default function BankDashboardPage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [section, setSection] = useState<Section>("overview");
  const [summary, setSummary] = useState<AdminSummary | null>(null);
  // Each of these grows without bound, so all three are paged.
  const institutions = usePagedResource<Institution>(getAdminInstitutions);
  const payments = usePagedResource<Payment>(getAdminPayments);
  const transfers = usePagedResource<FileTransfer>(getAdminTransfers);
  const [provisionOpen, setProvisionOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [snackbar, setSnackbar] = useState<string | null>(null);

  const refresh = useCallback(() => {
    getAdminSummary()
      .then(setSummary)
      .catch((err) => setError(err instanceof Error ? err.message : "Failed to load summary"));
    institutions.refresh();
    payments.refresh();
    transfers.refresh();
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  function handleLogout() {
    logout();
    navigate("/login", { replace: true });
  }

  if (!user) return null;
  const currency = summary?.currency ?? "TZS";

  return (
    <Box sx={{ minHeight: "100vh", bgcolor: "background.default" }}>
      <AppBar position="static" elevation={0}>
        <Toolbar sx={{ gap: 2 }}>
          <Typography variant="subtitle1" sx={{ flexGrow: 1, fontWeight: 600 }}>
            Central Bank
          </Typography>
          <Typography variant="body2" sx={{ opacity: 0.85, display: { xs: "none", sm: "block" } }}>
            Signed in as {user.username}
          </Typography>
          <Button color="inherit" size="small" startIcon={<LogoutIcon />} onClick={handleLogout}>
            Sign out
          </Button>
        </Toolbar>
      </AppBar>

      <Container maxWidth="lg" sx={{ py: 4 }}>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}

        <Box sx={{ display: "flex", justifyContent: "flex-end", mb: 2 }}>
          <Button
            variant="contained"
            startIcon={<PersonAddIcon />}
            onClick={() => setProvisionOpen(true)}
          >
            Provision
          </Button>
        </Box>

        <Paper variant="outlined">
          <Tabs
            value={section}
            onChange={(_, value) => setSection(value)}
            variant="scrollable"
            scrollButtons="auto"
            allowScrollButtonsMobile
            sx={{ px: 2, borderBottom: 1, borderColor: "divider" }}
          >
            <Tab label="Overview" value="overview" />
            <Tab label={`Institutions (${institutions.totalElements})`} value="institutions" />
            <Tab label={`Payments (${payments.totalElements})`} value="payments" />
            <Tab label={`Transfers (${transfers.totalElements})`} value="transfers" />
          </Tabs>

          <Box sx={{ p: 2 }}>
            {section === "overview" && summary && (
              <Stack spacing={2}>
                <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap", gap: 2 }}>
                  <Stat
                    label="Held on the platform"
                    value={money(summary.totalHeld, summary.currency)}
                    hint="summed from the ledger, not a stored figure"
                  />
                  <Stat label="Customer accounts" value={String(summary.accounts)} />
                  <Stat
                    label="Payments completed"
                    value={String(summary.completedPayments)}
                    hint={money(summary.completedPaymentVolume, summary.currency)}
                  />
                  <Stat
                    label="Payments refused"
                    value={String(summary.failedPayments)}
                    hint="recorded with a reason"
                  />
                </Stack>
                <Stack direction="row" spacing={2} sx={{ flexWrap: "wrap", gap: 2 }}>
                  <Stat label="Customers" value={String(summary.customers)} />
                  <Stat label="Institutions" value={String(summary.institutions)} />
                  <Stat label="Central Bank overseers" value={String(summary.bankOperators)} />
                  <Stat
                    label="File transfers"
                    value={String(summary.fileTransfers)}
                    hint={`${summary.transfersWithPayload} with ISO 20022 payload`}
                  />
                </Stack>
              </Stack>
            )}

            {section === "institutions" && (
              <>
              <TableContainer>
                <Table size="small" sx={{ minWidth: 720 }}>
                  <TableHead>
                    <TableRow>
                      <TableCell>Code</TableCell>
                      <TableCell>Institution</TableCell>
                      <TableCell>Bank number</TableCell>
                      <TableCell>Settlement account</TableCell>
                      <TableCell align="right">Customers</TableCell>
                      <TableCell align="right">Customer funds</TableCell>
                      <TableCell align="right">Settlement position</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {institutions.items.map((institution) => (
                      <TableRow key={institution.userId}>
                        <TableCell>
                          <Chip size="small" variant="outlined" label={institution.institutionCode} />
                        </TableCell>
                        <TableCell>{institution.username}</TableCell>
                        {/* The digits every account and card number it issues begins with. */}
                        <TableCell sx={{ letterSpacing: 0.5 }}>{institution.institutionNumber}</TableCell>
                        <TableCell sx={{ letterSpacing: 0.5 }}>
                          {institution.settlementAccountNumber}
                        </TableCell>
                        <TableCell align="right">{institution.customerCount}</TableCell>
                        <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                          {money(institution.customerFundsHeld, institution.currency)}
                        </TableCell>
                        <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                          {money(institution.settlementPosition, institution.currency)}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
              <Pager {...institutions} onPageChange={institutions.setPage} />
              </>
            )}

            {section === "payments" && (
              <>
              <TableContainer>
                <Table size="small" sx={{ minWidth: 640 }}>
                  <TableHead>
                    <TableRow>
                      <TableCell>Date</TableCell>
                      <TableCell>From</TableCell>
                      <TableCell>To</TableCell>
                      <TableCell align="right">Amount</TableCell>
                      <TableCell>Outcome</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {payments.items.map((payment) => (
                      <TableRow key={payment.paymentId}>
                        <TableCell>{formatDate(payment.createdAt)}</TableCell>
                        <TableCell>{payment.fromAccountNumber}</TableCell>
                        <TableCell>{payment.toAccountNumber ?? "—"}</TableCell>
                        <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                          {money(payment.amount, currency)}
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
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
              <Pager {...payments} onPageChange={payments.setPage} />
              </>
            )}

            {section === "transfers" && (
              <>
              <TableContainer>
                <Table size="small" sx={{ minWidth: 640 }}>
                  <TableHead>
                    <TableRow>
                      <TableCell>Sent</TableCell>
                      <TableCell>From</TableCell>
                      <TableCell>To</TableCell>
                      <TableCell>Document</TableCell>
                      <TableCell>Payload</TableCell>
                      <TableCell>Signature</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {transfers.items.map((transfer) => (
                      <TableRow key={transfer.transferId}>
                        <TableCell>{formatDate(transfer.sentAt)}</TableCell>
                        <TableCell>{transfer.senderUsername}</TableCell>
                        <TableCell>{transfer.receiverUsername}</TableCell>
                        <TableCell>{transfer.originalFilename}</TableCell>
                        <TableCell>
                          {transfer.hasPayload ? (
                            <Chip size="small" variant="outlined" label="pain.001" />
                          ) : (
                            <Typography variant="body2" color="text.secondary">
                              —
                            </Typography>
                          )}
                        </TableCell>
                        <TableCell>
                          <SignatureChip transfer={transfer} />
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
              <Pager {...transfers} onPageChange={transfers.setPage} />
              </>
            )}
          </Box>
        </Paper>
      </Container>

      <ProvisionAccountDialog
        open={provisionOpen}
        onClose={() => setProvisionOpen(false)}
        onProvisioned={(message) => {
          setSnackbar(message);
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
