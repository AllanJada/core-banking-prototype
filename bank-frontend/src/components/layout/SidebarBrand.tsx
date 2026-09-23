import { Box, Stack, Typography } from "@mui/material";
import AccountBalanceRoundedIcon from "@mui/icons-material/AccountBalanceRounded";

interface SidebarBrandProps {
  /** Which console this is — "Institution", "Central Bank", "Personal banking". */
  brand: string;
  /** The tenant behind it, when there is one: an institution code, a bank name. */
  detail?: string;
}

/**
 * The mark at the top of the sidebar, standing in for the template's product switcher.
 *
 * It is a label rather than a control on purpose: which institution you are acting as is
 * decided by the token you signed in with, so there is nothing here to switch between.
 */
export default function SidebarBrand({ brand, detail }: SidebarBrandProps) {
  return (
    <Stack direction="row" sx={{ gap: 1.25, alignItems: "center", p: 1.5, minWidth: 0 }}>
      <Box
        sx={{
          width: 34,
          height: 34,
          borderRadius: 1.5,
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          flexShrink: 0,
          color: "primary.contrastText",
          backgroundImage: (theme) =>
            `linear-gradient(135deg, ${theme.palette.primary.light} 0%, ${theme.palette.primary.main} 100%)`,
        }}
      >
        <AccountBalanceRoundedIcon sx={{ fontSize: "1.15rem" }} />
      </Box>
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="subtitle2" sx={{ fontWeight: 600, lineHeight: 1.3 }} noWrap>
          {brand}
        </Typography>
        {detail && (
          <Typography variant="caption" color="text.secondary" noWrap sx={{ display: "block" }}>
            {detail}
          </Typography>
        )}
      </Box>
    </Stack>
  );
}
