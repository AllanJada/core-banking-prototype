import { Stack, Typography, Box } from "@mui/material";
import type { SvgIconComponent } from "@mui/icons-material";
import LockOutlinedIcon from "@mui/icons-material/LockOutlined";
import SwapHorizOutlinedIcon from "@mui/icons-material/SwapHorizOutlined";
import VisibilityOutlinedIcon from "@mui/icons-material/VisibilityOutlined";
import { glassTokens } from "../theme";

interface Highlight {
  icon: SvgIconComponent;
  text: string;
}

// Phrased to describe what the system actually does today, not aspirational
// compliance claims it hasn't earned yet.
const HIGHLIGHTS: Highlight[] = [
  { icon: SwapHorizOutlinedIcon, text: "Direct file exchange between institutions" },
  { icon: LockOutlinedIcon, text: "Passwords hashed, never stored in plain text" },
  { icon: VisibilityOutlinedIcon, text: "Full visibility into every transfer's status" },
];

// Same readability approach as LoginPage: a secondary shadow touch behind
// the text, on top of (not instead of) the panel's own contrast-guaranteeing
// scrim — see glassTokens in theme.ts for where the actual guarantee comes
// from.
const textShadowSx = {
  textShadow: "0 1px 3px rgba(0,0,0,0.5)",
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
              // panel's frost setting. Sits on top of the panel's own scrim,
              // so it only ever gets darker/safer from here, never lighter.
              background: "rgba(0,0,0,0.35)",
              boxShadow: "0 1px 4px rgba(0,0,0,0.4)",
            }}
          >
            <Icon sx={{ color: glassTokens.textStrong, fontSize: 18 }} />
          </Box>
          <Typography variant="body2" sx={{ color: glassTokens.textStrong, ...textShadowSx }}>
            {text}
          </Typography>
        </Stack>
      ))}
    </Stack>
  );
}
