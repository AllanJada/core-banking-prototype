import Paper, { type PaperProps } from "@mui/material/Paper";
import { alpha, useTheme } from "@mui/material/styles";

export type GlassFrost = "none" | "light" | "heavy";

// Per the four pillars of glassmorphism (transparency, blur, a defined border,
// layered hierarchy): opacity stays within the 20-40% white-fill range that
// keeps text readable against a busy background, and blur strength scales
// with how much that panel needs to stand out. "none" is a plain bordered
// shell with no fill/blur at all, for wrapping other frosted panels.
const FROST_CONFIG: Record<GlassFrost, { alpha: number; blur: number }> = {
  none: { alpha: 0, blur: 0 },
  light: { alpha: 0.16, blur: 32 },
  heavy: { alpha: 0.26, blur: 64 },
};

interface GlassCardProps extends PaperProps {
  /** How strongly frosted this panel is. Defaults to "heavy" (the original
   * single-panel look). Use "none" for a plain bordered shell, "light" for a
   * panel that should stay closer to see-through. */
  frost?: GlassFrost;
}

/**
 * Reusable frosted-glass surface. Scoped for use on dark, visually rich
 * backgrounds (see LoginBackground) — not intended for the app's light-themed
 * dashboard screens.
 */
export default function GlassCard({ sx, frost = "heavy", ...props }: GlassCardProps) {
  const theme = useTheme();
  const { alpha: frostAlpha, blur } = FROST_CONFIG[frost];
  const isFrosted = blur > 0;

  return (
    <Paper
      {...props}
      sx={{
        background: frostAlpha > 0 ? alpha("#FFFFFF", frostAlpha) : "transparent",
        backdropFilter: isFrosted ? `blur(${blur}px)` : "none",
        WebkitBackdropFilter: isFrosted ? `blur(${blur}px)` : "none",
        // The defined border: not decorative, it's what keeps the panel's
        // edges readable for low-vision users once the blur softens them.
        border: `1px solid ${alpha("#FFFFFF", 0.18)}`,
        borderRadius: Number(theme.shape.borderRadius),
        boxShadow: frost === "none" ? "none" : "0 8px 32px 0 rgba(0, 0, 0, 0.35)",

        // Fallback for browsers without backdrop-filter support
        ...(isFrosted && {
          "@supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px)))": {
            background: "rgba(13, 31, 51, 0.92)",
          },
          // Respect users who've asked for reduced transparency
          "@media (prefers-reduced-transparency: reduce)": {
            background: "rgba(13, 31, 51, 0.95)",
            backdropFilter: "none",
            WebkitBackdropFilter: "none",
          },
        }),

        ...sx,
      }}
    />
  );
}
