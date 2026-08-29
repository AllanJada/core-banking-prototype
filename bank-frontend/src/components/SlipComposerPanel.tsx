import { useEffect, useState } from "react";
import {
  Alert,
  Box,
  Button,
  Divider,
  IconButton,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import AddIcon from "@mui/icons-material/AddOutlined";
import DeleteIcon from "@mui/icons-material/DeleteOutlined";
import { listUsers, sendSlip } from "../api/client";
import type { SlipLineItem, User } from "../types";

interface SlipComposerPanelProps {
  currentUser: User;
  onSent: () => void;
}

function emptyLineItem(): SlipLineItem {
  return { label: "", amount: 0 };
}

function todayIsoDate(): string {
  return new Date().toISOString().slice(0, 10);
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

/** Same row editor as SlipComposerDialog — kept as a separate local copy rather
 * than a shared import, since the two composer surfaces (modal vs. inline panel)
 * are otherwise independent and this is the only piece they'd share. */
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
              placeholder="Amount"
              value={item.amount === 0 ? "" : item.amount}
              onChange={(e) => updateRow(index, { amount: Number(e.target.value) || 0 })}
              sx={{ flex: 1 }}
            />
            <IconButton size="small" onClick={() => removeRow(index)} aria-label="Remove row">
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

export default function SlipComposerPanel({ currentUser, onSent }: SlipComposerPanelProps) {
  const [recipients, setRecipients] = useState<User[]>([]);
  const [form, setForm] = useState(emptyForm());
  const [earnings, setEarnings] = useState<SlipLineItem[]>([emptyLineItem()]);
  const [deductions, setDeductions] = useState<SlipLineItem[]>([emptyLineItem()]);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    listUsers()
      .then((users) =>
        setRecipients(
          users.filter((u) => u.userId !== currentUser.userId && u.userType === currentUser.userType)
        )
      )
      .catch((err) => setError(err instanceof Error ? err.message : "Failed to load recipients"));
  }, [currentUser.userId, currentUser.userType]);

  function setField<K extends keyof ReturnType<typeof emptyForm>>(
    key: K,
    value: ReturnType<typeof emptyForm>[K]
  ) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  function resetForm() {
    setForm(emptyForm());
    setEarnings([emptyLineItem()]);
    setDeductions([emptyLineItem()]);
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
        senderId: currentUser.userId,
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
      resetForm();
      onSent();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to generate and send slip");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Box>
      <Typography variant="h6" sx={{ fontWeight: 700, mb: 0.5 }}>
        Compose a slip
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        This generates a PDF on the server from the fields below and sends it
        immediately — there's no separate file to upload or edit afterward.
      </Typography>

      <Stack spacing={2.5}>
        {error && <Alert severity="error">{error}</Alert>}

        <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
          <TextField
            select
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
        <Typography variant="subtitle2">Organization</Typography>
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
        <Typography variant="subtitle2">Employee</Typography>
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
        <Typography variant="subtitle2">Accounts</Typography>
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
        <Stack direction={{ xs: "column", sm: "row" }} spacing={3}>
          <LineItemEditor title="Earnings" items={earnings} onChange={setEarnings} />
          <LineItemEditor title="Deductions" items={deductions} onChange={setDeductions} />
        </Stack>

        <TextField
          label="Amount in words"
          placeholder="e.g. One thousand dollars only"
          value={form.amountInWords}
          onChange={(e) => setField("amountInWords", e.target.value)}
          fullWidth
        />

        <Box>
          <Button variant="contained" onClick={handleSend} disabled={submitting}>
            {submitting ? "Generating & sending…" : "Generate & Send"}
          </Button>
        </Box>
      </Stack>
    </Box>
  );
}
