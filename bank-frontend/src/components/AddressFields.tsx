import { Stack, TextField, Typography } from "@mui/material";

export interface AddressFieldValues {
  streetName: string;
  buildingNumber: string;
  postCode: string;
  townName: string;
  country: string;
}

interface AddressFieldsProps {
  values: AddressFieldValues;
  onChange: (field: keyof AddressFieldValues, value: string) => void;
}

/**
 * The five parts of a structured postal address.
 *
 * Shared between the two slip composers rather than copied into each, unlike the line-item
 * editor next to it: five coupled inputs with a country-code rule between them is enough
 * substance that two copies would drift apart.
 *
 * Town and country are marked required because they are the minimum the payment schemes
 * accept — an address missing either cannot go into a payload at all, so saying so at the
 * keyboard is better than a rejection later.
 */
export default function AddressFields({ values, onChange }: AddressFieldsProps) {
  return (
    <Stack spacing={2}>
      <Typography variant="caption" color="text.secondary">
        Address is captured in parts, as the ISO 20022 payment standard requires.
      </Typography>

      <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
        <TextField
          label="Building number"
          value={values.buildingNumber}
          onChange={(e) => onChange("buildingNumber", e.target.value)}
          sx={{ width: { sm: 180 } }}
        />
        <TextField
          label="Street name"
          value={values.streetName}
          onChange={(e) => onChange("streetName", e.target.value)}
          fullWidth
        />
      </Stack>

      <Stack direction={{ xs: "column", sm: "row" }} spacing={2}>
        <TextField
          label="Post code"
          value={values.postCode}
          onChange={(e) => onChange("postCode", e.target.value)}
          sx={{ width: { sm: 180 } }}
        />
        <TextField
          label="Town"
          value={values.townName}
          onChange={(e) => onChange("townName", e.target.value)}
          required
          fullWidth
        />
        <TextField
          label="Country"
          value={values.country}
          // Forced to the two-letter uppercase shape ISO 3166-1 alpha-2 requires, so
          // "Tanzania" or "tz" cannot reach the payload as a country code.
          onChange={(e) =>
            onChange("country", e.target.value.replace(/[^A-Za-z]/g, "").slice(0, 2).toUpperCase())
          }
          required
          placeholder="TZ"
          sx={{ width: { sm: 120 } }}
          slotProps={{ htmlInput: { maxLength: 2 } }}
        />
      </Stack>
    </Stack>
  );
}
