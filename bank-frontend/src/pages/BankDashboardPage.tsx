import { useCallback, useEffect, useState } from "react";
import {
  Alert,
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
import DownloadIcon from "@mui/icons-material/DownloadOutlined";
import PersonAddIcon from "@mui/icons-material/PersonAddOutlined";
import DashboardRoundedIcon from "@mui/icons-material/DashboardRounded";
import AccountBalanceRoundedIcon from "@mui/icons-material/AccountBalanceRounded";
import BalanceRoundedIcon from "@mui/icons-material/BalanceRounded";
import SyncAltRoundedIcon from "@mui/icons-material/SyncAltRounded";
import { useNavigate } from "react-router-dom";
import {
  downloadAdminSettlementMessage,
  getAdminInstitutions,
  getAdminSettlement,
  getAdminSettlementRefusals,
  getAdminSummary,
  getAdminTransfers,
} from "../api/client";
import { useAuth } from "../context/AuthContext";
import ProvisionAccountDialog from "../components/ProvisionAccountDialog";
import Pager from "../components/Pager";
import { usePagedResource } from "../hooks/usePagedResource";
import SignatureChip from "../components/SignatureChip";
import DashboardLayout from "../components/layout/DashboardLayout";
import PageHeader from "../components/layout/PageHeader";
import StatCard from "../components/StatCard";
import SectionCard from "../components/SectionCard";
import { dataFontFamily } from "../theme";
import type {
  AdminSummary,
  FileTransfer,
  Institution,
  Settlement,
  SettlementMovement,
  SettlementRefusal,
} from "../types";

type Section = "overview" | "institutions" | "settlement" | "transfers";

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

const SECTION_COPY: Record<Section, { title: string; description: string }> = {
  overview: {
    title: "Overview",
    description: "Platform totals, every one of them summed from the ledger rather than stored.",
  },
  institutions: {
    title: "Institutions",
    description:
      "Every licensed bank and its aggregates. No customer of any of them is named here.",
  },
  settlement: {
    title: "Settlement",
    description:
      "Where each bank stands, the bank-to-bank movements behind it, and anything that could not be settled.",
  },
  transfers: {
    title: "Transfers",
    description: "Every signed document exchanged between institutions, across all participants.",
  },
};

/**
 * The Central Bank's console.
 *
 * This role supervises institutions rather than taking part in banking: everything here is
 * read-only except provisioning institutions and other overseers. There is deliberately no
 * way to create a customer, adjust a balance, reverse a payment or unblock a card from this
 * screen — customers belong to their institution.
 *
 * What it shows about banks is aggregated, and what it shows about money moving between them
 * is bank-to-bank. Neither the institutions section nor the settlement section names a customer.
 */
export default function BankDashboardPage() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const [section, setSection] = useState<Section>("overview");
  const [summary, setSummary] = useState<AdminSummary | null>(null);
  const [settlement, setSettlement] = useState<Settlement | null>(null);

  // The settlement view returns every position alongside each page of movements, so paging
  // the movements refreshes the positions with them rather than needing a second request.
  const fetchMovements = useCallback(async (page: number, size: number) => {
    const view = await getAdminSettlement(page, size);
    setSettlement(view);
    return view.movements;
  }, []);

  // Each of these grows without bound, so all of them are paged.
  const institutions = usePagedResource<Institution>(getAdminInstitutions);
  const movements = usePagedResource<SettlementMovement>(fetchMovements);
  const refusals = usePagedResource<SettlementRefusal>(getAdminSettlementRefusals);
  const transfers = usePagedResource<FileTransfer>(getAdminTransfers);
  const [provisionOpen, setProvisionOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [snackbar, setSnackbar] = useState<string | null>(null);

  const refresh = useCallback(() => {
    getAdminSummary()
      .then(setSummary)
      .catch((err) => setError(err instanceof Error ? err.message : "Failed to load summary"));
    institutions.refresh();
    movements.refresh();
    refusals.refresh();
    transfers.refresh();
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  async function handleDownloadMessage(messageId: number, uetr: string) {
    try {
      await downloadAdminSettlementMessage(messageId, uetr);
    } catch (err) {
      setSnackbar(err instanceof Error ? err.message : "Could not download the settlement message");
    }
  }

  function handleLogout() {
    logout();
    navigate("/login", { replace: true });
  }

  if (!user) return null;
  const currency = summary?.currency ?? settlement?.currency ?? "TZS";
  const copy = SECTION_COPY[section];

  return (
    <DashboardLayout<Section>
      brand="Central Bank"
      brandDetail="Settlement operator"
      username={user.username}
      roleLabel="Overseer"
      onLogout={handleLogout}
      section={section}
      onSectionChange={setSection}
      sections={[
        { value: "overview", label: "Overview", icon: <DashboardRoundedIcon /> },
        {
          value: "institutions",
          label: "Institutions",
          icon: <AccountBalanceRoundedIcon />,
          count: institutions.totalElements,
        },
        {
          value: "settlement",
          label: "Settlement",
          icon: <BalanceRoundedIcon />,
          count: movements.totalElements,
        },
        {
          value: "transfers",
          label: "Transfers",
          icon: <SyncAltRoundedIcon />,
          count: transfers.totalElements,
        },
      ]}
    >
      <PageHeader
        context="Central Bank"
        title={copy.title}
        description={copy.description}
        actions={
          <Button
            variant="contained"
            startIcon={<PersonAddIcon />}
            onClick={() => setProvisionOpen(true)}
          >
            Provision
          </Button>
        }
      />

      {error && <Alert severity="error">{error}</Alert>}

      {/* The one invariant worth shouting about: if the positions stop summing to zero,
          an inter-bank payment moved one side without the other. */}
      {summary && !summary.settlementBalanced && (
        <Alert severity="error">
          Settlement positions do not sum to zero ({money(summary.settlementPositionsSum, currency)}
          ). Investigate before relying on any figure on this page.
        </Alert>
      )}

      {section === "overview" && summary && (
        <Grid container spacing={2}>
          <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
            <StatCard
              label="Held on the platform"
              value={money(summary.totalHeld, summary.currency)}
              hint="summed from the ledger, not a stored figure"
            />
          </Grid>
          <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
            <StatCard label="Customer accounts" value={String(summary.accounts)} />
          </Grid>
          <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
            <StatCard
              label="Payments completed"
              value={String(summary.completedPayments)}
              hint={money(summary.completedPaymentVolume, summary.currency)}
            />
          </Grid>
          <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
            <StatCard label="Payments refused" value={String(summary.failedPayments)} />
          </Grid>

          <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
            <StatCard label="Customers" value={String(summary.customers)} />
          </Grid>
          <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
            <StatCard label="Institutions" value={String(summary.institutions)} />
          </Grid>
          <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
            <StatCard label="Central Bank overseers" value={String(summary.bankOperators)} />
          </Grid>
          <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
            <StatCard
              label="Settlement positions"
              value={money(summary.settlementPositionsSum, summary.currency)}
              tone={summary.settlementBalanced ? "success" : "error"}
              badge={
                <Chip
                  size="small"
                  color={summary.settlementBalanced ? "success" : "error"}
                  label={summary.settlementBalanced ? "Balanced (I2)" : "Not balanced"}
                />
              }
              hint={
                summary.settlementBalanced
                  ? "they sum to zero, as they must"
                  : "NOT balanced — investigate"
              }
            />
          </Grid>

          <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
            <StatCard
              label="File transfers"
              value={String(summary.fileTransfers)}
              hint={`${summary.transfersWithPayload} with ISO 20022 payload`}
            />
          </Grid>
        </Grid>
      )}

      {section === "institutions" && (
        <SectionCard disablePadding>
          <TableContainer>
            <Table size="small" sx={{ minWidth: 820 }}>
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
                {institutions.items.length === 0 && !institutions.loading && (
                  <TableRow>
                    <TableCell colSpan={7}>
                      <Typography variant="body2" color="text.secondary" sx={{ py: 3 }}>
                        No institutions licensed yet.
                      </Typography>
                    </TableCell>
                  </TableRow>
                )}
                {institutions.items.map((institution) => (
                  <TableRow key={institution.userId}>
                    <TableCell>
                      <Chip size="small" color="info" label={institution.institutionCode} />
                    </TableCell>
                    <TableCell sx={{ fontWeight: 500 }}>{institution.username}</TableCell>
                    {/* The digits every account and card number it issues begins with. */}
                    <TableCell sx={{ fontFamily: dataFontFamily }}>
                      {institution.institutionNumber}
                    </TableCell>
                    <TableCell sx={{ fontFamily: dataFontFamily, letterSpacing: 0.3 }}>
                      {institution.settlementAccountNumber}
                    </TableCell>
                    <TableCell align="right">{institution.customerCount}</TableCell>
                    <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                      {money(institution.customerFundsHeld, institution.currency)}
                    </TableCell>
                    <TableCell
                      align="right"
                      sx={{
                        whiteSpace: "nowrap",
                        fontWeight: 600,
                        color: institution.settlementPosition < 0 ? "error.main" : "text.primary",
                      }}
                    >
                      {money(institution.settlementPosition, institution.currency)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          <Pager {...institutions} onPageChange={institutions.setPage} />
        </SectionCard>
      )}

      {section === "settlement" && (
        <Stack spacing={2.5}>
          <SectionCard
            title="Positions"
            description="Every licensed bank's standing, and the cap it settles within."
            disablePadding
          >
            <TableContainer>
              <Table size="small" sx={{ minWidth: 720 }}>
                <TableHead>
                  <TableRow>
                    <TableCell>Institution</TableCell>
                    <TableCell>Settlement account</TableCell>
                    <TableCell align="right">Position</TableCell>
                    <TableCell align="right">Net debit cap</TableCell>
                    <TableCell align="right">Headroom</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {(settlement?.positions ?? []).map((position) => (
                    <TableRow key={position.institutionId}>
                      <TableCell>
                        <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
                          <Chip size="small" color="info" label={position.institutionCode} />
                          <span>{position.institutionName}</span>
                        </Stack>
                      </TableCell>
                      <TableCell sx={{ fontFamily: dataFontFamily, letterSpacing: 0.3 }}>
                        {position.settlementAccountNumber}
                      </TableCell>
                      <TableCell
                        align="right"
                        sx={{
                          whiteSpace: "nowrap",
                          fontWeight: 600,
                          color: position.position < 0 ? "error.main" : "text.primary",
                        }}
                      >
                        {money(position.position, settlement?.currency ?? currency)}
                      </TableCell>
                      <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                        {money(position.netDebitCap, settlement?.currency ?? currency)}
                      </TableCell>
                      <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                        {money(position.headroom, settlement?.currency ?? currency)}
                      </TableCell>
                    </TableRow>
                  ))}
                  {settlement && (
                    <TableRow sx={{ "& > .MuiTableCell-body": { bgcolor: "action.hover" } }}>
                      <TableCell colSpan={2} sx={{ fontWeight: 700 }}>
                        Sum of positions
                      </TableCell>
                      <TableCell align="right" sx={{ fontWeight: 700, whiteSpace: "nowrap" }}>
                        {money(settlement.positionsSum, settlement.currency)}
                      </TableCell>
                      <TableCell colSpan={2}>
                        <Chip
                          size="small"
                          color={settlement.balanced ? "success" : "error"}
                          label={settlement.balanced ? "Balanced (I2)" : "Not balanced"}
                        />
                      </TableCell>
                    </TableRow>
                  )}
                </TableBody>
              </Table>
            </TableContainer>
          </SectionCard>

          <SectionCard
            title="Movements"
            description="One row per payment that crossed banks, with the instruction behind it."
            disablePadding
          >
            <TableContainer>
              <Table size="small" sx={{ minWidth: 720 }}>
                <TableHead>
                  <TableRow>
                    <TableCell>When</TableCell>
                    <TableCell>From bank</TableCell>
                    <TableCell>To bank</TableCell>
                    <TableCell align="right">Amount</TableCell>
                    <TableCell>Reference</TableCell>
                    <TableCell>Instruction</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {movements.items.length === 0 && !movements.loading && (
                    <TableRow>
                      <TableCell colSpan={6}>
                        <Typography variant="body2" color="text.secondary" sx={{ py: 3 }}>
                          No inter-bank payments yet. Payments within one bank never touch
                          settlement.
                        </Typography>
                      </TableCell>
                    </TableRow>
                  )}
                  {movements.items.map((movement) => (
                    <TableRow key={movement.paymentId}>
                      <TableCell sx={{ whiteSpace: "nowrap" }}>
                        {formatDate(movement.occurredAt)}
                      </TableCell>
                      <TableCell>
                        <Chip size="small" color="default" label={movement.fromInstitutionCode} />
                      </TableCell>
                      <TableCell>
                        <Chip size="small" color="default" label={movement.toInstitutionCode} />
                      </TableCell>
                      <TableCell align="right" sx={{ whiteSpace: "nowrap", fontWeight: 500 }}>
                        {money(movement.amount, settlement?.currency ?? currency)}
                      </TableCell>
                      <TableCell sx={{ fontFamily: dataFontFamily, fontSize: 12 }}>
                        {movement.transactionRef?.slice(0, 8)}
                      </TableCell>
                      <TableCell>
                        {/* The ISO 20022 message the paying bank sent the receiving one.
                            Re-verified server-side before it is served. */}
                        {movement.messageId ? (
                          <Button
                            size="small"
                            startIcon={<DownloadIcon />}
                            onClick={() =>
                              handleDownloadMessage(movement.messageId!, movement.transactionRef)
                            }
                          >
                            pacs.008
                          </Button>
                        ) : (
                          <Typography variant="body2" color="text.secondary">
                            —
                          </Typography>
                        )}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
            <Pager {...movements} onPageChange={movements.setPage} />
          </SectionCard>

          {refusals.totalElements > 0 && (
            <SectionCard
              title="Could not be settled"
              description="The specific cause, which the paying customer was not given."
              disablePadding
            >
              <TableContainer>
                <Table size="small" sx={{ minWidth: 640 }}>
                  <TableHead>
                    <TableRow>
                      <TableCell>When</TableCell>
                      <TableCell>Institution</TableCell>
                      <TableCell align="right">Amount</TableCell>
                      <TableCell>Cause</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {refusals.items.map((refusal) => (
                      <TableRow key={refusal.paymentId}>
                        <TableCell sx={{ whiteSpace: "nowrap" }}>
                          {formatDate(refusal.refusedAt)}
                        </TableCell>
                        <TableCell>
                          <Chip size="small" color="default" label={refusal.institutionCode} />
                        </TableCell>
                        <TableCell align="right" sx={{ whiteSpace: "nowrap" }}>
                          {money(refusal.amount, settlement?.currency ?? currency)}
                        </TableCell>
                        <TableCell>{refusal.detail}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
              <Pager {...refusals} onPageChange={refusals.setPage} />
            </SectionCard>
          )}
        </Stack>
      )}

      {section === "transfers" && (
        <SectionCard disablePadding>
          <TableContainer>
            <Table size="small" sx={{ minWidth: 760 }}>
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
                {transfers.items.length === 0 && !transfers.loading && (
                  <TableRow>
                    <TableCell colSpan={6}>
                      <Typography variant="body2" color="text.secondary" sx={{ py: 3 }}>
                        No transfers between institutions yet.
                      </Typography>
                    </TableCell>
                  </TableRow>
                )}
                {transfers.items.map((transfer) => (
                  <TableRow key={transfer.transferId}>
                    <TableCell sx={{ whiteSpace: "nowrap" }}>
                      {formatDate(transfer.sentAt)}
                    </TableCell>
                    <TableCell>{transfer.senderUsername}</TableCell>
                    <TableCell>{transfer.receiverUsername}</TableCell>
                    <TableCell sx={{ fontFamily: dataFontFamily }}>
                      {transfer.originalFilename}
                    </TableCell>
                    <TableCell>
                      {transfer.hasPayload ? (
                        <Chip size="small" color="info" label="pain.001" />
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
        </SectionCard>
      )}

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
    </DashboardLayout>
  );
}
