import { Avatar, Box, Stack, Tooltip, Typography, IconButton } from "@mui/material";
import LogoutRoundedIcon from "@mui/icons-material/LogoutRounded";

interface SidebarFooterProps {
  username: string;
  /** What this account is, in words — "Institution", "Customer", "Overseer". */
  roleLabel: string;
  onLogout: () => void;
}

/**
 * Who is signed in, and the way out — the block that sits under the nav in both MUI
 * dashboard templates. This replaces the "Signed in as …" text and Sign out button that
 * used to live in the top app bar on every page.
 */
export default function SidebarFooter({ username, roleLabel, onLogout }: SidebarFooterProps) {
  return (
    <Stack
      direction="row"
      sx={{
        p: 1.5,
        gap: 1.25,
        alignItems: "center",
        borderTop: "1px solid",
        borderColor: "divider",
      }}
    >
      <Avatar
        sx={{
          width: 32,
          height: 32,
          fontSize: "0.85rem",
          fontWeight: 600,
          bgcolor: "primary.main",
        }}
      >
        {username.slice(0, 1).toUpperCase()}
      </Avatar>
      <Box sx={{ mr: "auto", minWidth: 0 }}>
        <Typography variant="body2" sx={{ fontWeight: 600, lineHeight: 1.3 }} noWrap>
          {username}
        </Typography>
        <Typography variant="caption" color="text.secondary" noWrap sx={{ display: "block" }}>
          {roleLabel}
        </Typography>
      </Box>
      <Tooltip title="Sign out">
        <IconButton size="small" onClick={onLogout} aria-label="Sign out">
          <LogoutRoundedIcon fontSize="small" />
        </IconButton>
      </Tooltip>
    </Stack>
  );
}
