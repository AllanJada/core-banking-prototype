import { useEffect, useMemo, useState } from "react";
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  IconButton,
  InputAdornment,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import AddIcon from "@mui/icons-material/AddOutlined";
import DeleteIcon from "@mui/icons-material/DeleteOutlined";
import { listUsers, sendSlip } from "../api/client";
import type { SlipLineItem, User } from "../types";

interface SlipComposerDialogProps {
  open: boolean;
  onClose: () => void;
  onSent: () => void;
  currentUser: User;
}

function emptyLineItem(): SlipLineItem {
  return { label: "", amount: 0 };
}

function todayIsoDate(): string {
  return new Date().toISOString().slice(0, 10);
}

function sumItems(items: SlipLineItem[]): number {
  return items.reduce((total, item) => total + (Number.isFinite(item.amount) ? item.amount : 0), 0);
}

function formatAmount(value: number): string {
  return value.toLocaleString(undefined, { minimumFractionDigits: 0, maximumFractionDigits: 2 });
}

function emptyForm() {
  return {
    receiverId: "" as number | "",
    title: "Payslip",
    organizationName: "",
    organizationAddress: "",
    date: todayIsoDate(),
    employeeName: "",
    payPeriod: "",
    designation: "",
    workedDays: "" as number | "",
    department: "",
    payerAccount: "",
    payeeAccount: "",
    beneficiaryBank: "",
    amountInWords: "",
  };
}

/** Small inline editor for a list of {label, amount} rows — used for both
 * earnings and deductions, since they're identical in shape. */
function LineItemEditor({
  title,
  items,
  onChange,
}: {
  title: string;
  items: SlipLineItem[];
  onChange: (items: SlipLineItem[]) => void;
}) {
  function updateRow(index: number, patch: Partial<SlipLineItem>) {
    onChange(items.map((item, i) => (i === index ? { ...item, ...patch } : item)));
  }

  function removeRow(index: number) {
    onChange(items.filter((_, i) => i !== index));
  }

  function addRow() {
    onChange([...items, emptyLineItem()]);
  }

  return (
    <Box sx={{ flex: 1 }}>
      <Typography variant="subtitle2" sx={{ mb: 1 }}>
        {title}
      </Typography>
      <Stack spacing={1}>
        {items.map((item, index) => (
          <Stack key={index} direction="row" spacing={1} sx={{ alignItems: "center" }}>
            <TextField
              size="small"
              placeholder="Label"
              value={item.label}
              onChange={(e) => updateRow(index, { label: e.target.value })}
              sx={{ flex: 2 }}
            />
            <TextField
              size="small"
              type="number"
              placeholder="0"
              value={item.amount === 0 ? "" : item.amount}
              onChange={(e) => updateRow(index, { amount: Number(e.target.value) || 0 })}
              slotProps={{
                input: {
                  startAdornment: <InputAdornment position="start">TSh</InputAdornment>,
                  inputProps: { min: 0, inputMode: "decimal" },
                },
              }}
              sx={{ flex: 1.3 }}
            />
            <IconButton size="small" onClick={() => removeRow(index)} aria-label={`Remove ${title.toLowerCase()} row`}>
              <DeleteIcon fontSize="small" />
            </IconButton>
          </Stack>
        ))}
        <Button size="small" startIcon={<AddIcon />} onClick={addRow} sx={{ alignSelf: "flex-start" }}>
          Add row
        </Button>
      </Stack>
    </Box>
  );
}

export default function SlipComposerDialog({
  open,
  onClose,
  onSent,
  currentUser,
}: SlipComposerDialogProps) {
  const [recipients, setRecipients] = useState<User[]>([]);
  const [form, setForm] = useState(emptyForm());
  const [earnings, setEarnings] = useState<SlipLineItem[]>([emptyLineItem()]);
  const [deductions, setDeductions] = useState<SlipLineItem[]>([emptyLineItem()]);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    setForm(emptyForm());
    setEarnings([emptyLineItem()]);
    setDeductions([emptyLineItem()]);
    setError(null);

    listUsers()
      .then((users) =>
        setRecipients(
          users.filter((u) => u.userId !== currentUser.userId && u.userType === currentUser.userType)
        )
      )
      .catch((err) => setError(err instanceof Error ? err.message : "Failed to load recipients"));
  }, [open, currentUser.userId, currentUser.userType]);

  // Computed, not typed in: the whole point is that nobody has to add these
  // up by hand to sanity-check the slip before sending it (Tesler's Law —
  // this is arithmetic the interface should absorb, not hand to the person
  // filling out the form).
  const totalEarnings = useMemo(() => sumItems(earnings), [earnings]);
  const totalDeductions = useMemo(() => sumItems(deductions), [deductions]);
  const netPay = totalEarnings - totalDeductions;

  function setField<K extends keyof ReturnType<typeof emptyForm>>(
    key: K,
    value: ReturnType<typeof emptyForm>[K]
  ) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  async function handleSend() {
    if (form.receiverId === "") {
      setError("Choose a recipient first.");
      return;
    }
    if (!form.employeeName.trim()) {
      setError("Employee name is required.");
      return;
    }

    setError(null);
    setSubmitting(true);
    try {
      await sendSlip({
        receiverId: form.receiverId,
        title: form.title,
        organizationName: form.organizationName,
        organizationAddress: form.organizationAddress,
        date: form.date,
        employeeName: form.employeeName,
        payPeriod: form.payPeriod,
        designation: form.designation,
        workedDays: form.workedDays === "" ? 0 : form.workedDays,
        department: form.department,
        payerAccount: form.payerAccount,
        payeeAccount: form.payeeAccount,
        beneficiaryBank: form.beneficiaryBank,
        earnings: earnings.filter((item) => item.label.trim() !== ""),
        deductions: deductions.filter((item) => item.label.trim() !== ""),
        amountInWords: form.amountInWords,
      });
      onSent();
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to generate and send slip");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>Compose a slip</DialogTitle>
      <DialogContent>
        <Stack spacing={2.5} sx={{ mt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}

          <Typography variant="caption" color="text.secondary">
            This generates a PDF on the server from the fields below and sends it
            immediately — there's no separate file to upload or edit afterward.
          </Typography>

          <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
            <TextField
              select
              required
              label="Recipient"
              value={form.receiverId}
              onChange={(e) => setField("receiverId", Number(e.target.value))}
              fullWidth
            >
              {recipients.map((user) => (
                <MenuItem key={user.userId} value={user.userId}>
                  {user.username}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              label="Document title"
              value={form.title}
              onChange={(e) => setField("title", e.target.value)}
              fullWidth
            />
          </Stack>

          <Divider />
          <Typography variant="subtitle2" color="text.secondary">
            Organization
          </Typography>
          <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
            <TextField
              label="Organization name"
              value={form.organizationName}
              onChange={(e) => setField("organizationName", e.target.value)}
              fullWidth
            />
            <TextField
              label="Organization address"
              value={form.organizationAddress}
              onChange={(e) => setField("organizationAddress", e.target.value)}
              fullWidth
            />
          </Stack>

          <Divider />
          <Typography variant="subtitle2" color="text.secondary">
            Employee
          </Typography>
          <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
            <TextField
              label="Date"
              type="date"
              value={form.date}
              onChange={(e) => setField("date", e.target.value)}
              fullWidth
              slotProps={{ inputLabel: { shrink: true } }}
            />
            <TextField
              required
              label="Employee name"
              value={form.employeeName}
              onChange={(e) => setField("employeeName", e.target.value)}
              fullWidth
            />
          </Stack>
          <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
            <TextField
              label="Pay period"
              placeholder="e.g. August 2026"
              value={form.payPeriod}
              onChange={(e) => setField("payPeriod", e.target.value)}
              fullWidth
            />
            <TextField
              label="Designation"
              value={form.designation}
              onChange={(e) => setField("designation", e.target.value)}
              fullWidth
            />
          </Stack>
          <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
            <TextField
              label="Worked days"
              type="number"
              value={form.workedDays}
              onChange={(e) => setField("workedDays", e.target.value === "" ? "" : Number(e.target.value))}
              slotProps={{ htmlInput: { min: 0, max: 31 } }}
              fullWidth
            />
            <TextField
              label="Department"
              value={form.department}
              onChange={(e) => setField("department", e.target.value)}
              fullWidth
            />
          </Stack>

          <Divider />
          <Typography variant="subtitle2" color="text.secondary">
            Accounts
          </Typography>
          <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
            <TextField
              label="Payer account"
              value={form.payerAccount}
              onChange={(e) => setField("payerAccount", e.target.value)}
              fullWidth
            />
            <TextField
              label="Payee account"
              value={form.payeeAccount}
              onChange={(e) => setField("payeeAccount", e.target.value)}
              fullWidth
            />
            <TextField
              label="Beneficiary bank"
              value={form.beneficiaryBank}
              onChange={(e) => setField("beneficiaryBank", e.target.value)}
              fullWidth
            />
          </Stack>

          <Divider />
          <Typography variant="subtitle2" color="text.secondary">
            Earnings &amp; deductions
          </Typography>
          <Stack direction={{ xs: "column", sm: "row" }} spacing={3}>
            <LineItemEditor title="Earnings" items={earnings} onChange={setEarnings} />
            <LineItemEditor title="Deductions" items={deductions} onChange={setDeductions} />
          </Stack>

          {/* Computed summary, not another input — reflects the rows above
              back at whoever's filling this in so a typo (an extra zero, a
              deduction in the earnings column) is obvious before sending
              rather than discovered on the printed slip. */}
          <Box
            sx={{
              px: 2,
              py: 1.5,
              borderRadius: 1,
              bgcolor: "background.default",
              border: "1px solid",
              borderColor: "divider",
            }}
          >
            <Stack spacing={0.5}>
              <Stack direction="row" sx={{ justifyContent: "space-between" }}>
                <Typography variant="body2" color="text.secondary">
                  Total earnings
                </Typography>
                <Typography variant="body2" sx={{ fontFamily: "IBM Plex Mono, monospace" }}>
                  TSh {formatAmount(totalEarnings)}
                </Typography>
              </Stack>
              <Stack direction="row" sx={{ justifyContent: "space-between" }}>
                <Typography variant="body2" color="text.secondary">
                  Total deductions
                </Typography>
                <Typography variant="body2" sx={{ fontFamily: "IBM Plex Mono, monospace" }}>
                  TSh {formatAmount(totalDeductions)}
                </Typography>
              </Stack>
              <Divider sx={{ my: 0.5 }} />
              <Stack direction="row" sx={{ justifyContent: "space-between" }}>
                <Typography variant="subtitle2">Net pay</Typography>
                <Typography
                  variant="subtitle2"
                  sx={{ fontFamily: "IBM Plex Mono, monospace", color: netPay < 0 ? "error.main" : "text.primary" }}
                >
                  TSh {formatAmount(netPay)}
                </Typography>
              </Stack>
              {netPay < 0 && (
                <Typography variant="caption" color="error.main">
                  Deductions exceed earnings — double-check the rows above before sending.
                </Typography>
              )}
            </Stack>
          </Box>

          <TextField
            label="Amount in words"
            placeholder="e.g. One thousand dollars only"
            value={form.amountInWords}
            onChange={(e) => setField("amountInWords", e.target.value)}
            fullWidth
          />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose} disabled={submitting}>
          Cancel
        </Button>
        <Box sx={{ flex: 1 }} />
        <Button variant="contained" onClick={handleSend} disabled={submitting}>
          {submitting ? "Generating & sending…" : "Generate & Send"}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
