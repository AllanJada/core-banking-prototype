import { Stack, Typography, Box } from "@mui/material";
import type { SvgIconComponent } from "@mui/icons-material";
import AccountBalanceOutlinedIcon from "@mui/icons-material/AccountBalanceOutlined";
import VerifiedUserOutlinedIcon from "@mui/icons-material/VerifiedUserOutlined";
import LockOutlinedIcon from "@mui/icons-material/LockOutlined";
import RateReviewOutlinedIcon from "@mui/icons-material/RateReviewOutlined";

interface Highlight {
  icon: SvgIconComponent;
  text: string;
}

// Phrased to describe what the system actually does today, not aspirational
// compliance claims it hasn't earned yet.
const HIGHLIGHTS: Highlight[] = [
  { icon: AccountBalanceOutlinedIcon, text: "Balances derived from an immutable, double-entry ledger" },
  { icon: VerifiedUserOutlinedIcon, text: "Every document and payment signed with ML-DSA-65 (post-quantum)" },
  { icon: LockOutlinedIcon, text: "Files encrypted at rest with AES-256-GCM" },
  { icon: RateReviewOutlinedIcon, text: "Incoming transfers reviewed and approved before pickup" },
];

// Same readability workaround as LoginPage: a dark halo behind text/icons,
// independent of whatever frost level the parent panel is set to.
const textShadowSx = {
  textShadow: "0 1px 3px rgba(0,0,0,0.65), 0 1px 8px rgba(0,0,0,0.4)",
};

export default function SecurityHighlights() {
  return (
    <Stack spacing={2}>
      {HIGHLIGHTS.map(({ icon: Icon, text }) => (
        <Stack key={text} direction="row" spacing={1.5} sx={{ alignItems: "center" }}>
          <Box
            sx={{
              width: 32,
              height: 32,
              flexShrink: 0,
              borderRadius: "50%",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              // Solid dark backing (not glass — just a plain translucent-black
              // circle) so the icon reads clearly regardless of the parent
              // panel's frost setting.
              background: "rgba(0,0,0,0.35)",
              boxShadow: "0 1px 4px rgba(0,0,0,0.4)",
            }}
          >
            <Icon sx={{ color: "#fff", fontSize: 18 }} />
          </Box>
          <Typography variant="body2" sx={{ color: "rgba(255,255,255,0.9)", ...textShadowSx }}>
            {text}
          </Typography>
        </Stack>
      ))}
    </Stack>
  );
}
