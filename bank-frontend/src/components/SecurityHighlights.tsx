import { Stack, Typography, Box } from "@mui/material";
import type { SvgIconComponent } from "@mui/icons-material";
import LockOutlinedIcon from "@mui/icons-material/LockOutlined";
import SwapHorizOutlinedIcon from "@mui/icons-material/SwapHorizOutlined";
import VisibilityOutlinedIcon from "@mui/icons-material/VisibilityOutlined";

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
              background: "rgba(255,255,255,0.12)",
            }}
          >
            <Icon sx={{ color: "#fff", fontSize: 18 }} />
          </Box>
          <Typography variant="body2" sx={{ color: "rgba(255,255,255,0.85)" }}>
            {text}
          </Typography>
        </Stack>
      ))}
    </Stack>
  );
}
