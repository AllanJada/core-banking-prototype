import Paper, { type PaperProps } from "@mui/material/Paper";
import { alpha, useTheme } from "@mui/material/styles";
import { glassTokens } from "../theme";

export type GlassFrost = "none" | "light" | "heavy";

// "none" is a genuinely plain, transparent bordered shell — for elements
// that hold no text of their own (e.g. the outer wrapper in LoginPage,
// which exists only to draw one shared border/shadow around two inner
// panels). Anything that *does* carry text needs "light" or "heavy": both
// alpha values come from theme.ts's glassTokens, computed to keep text
// readable even where the photo behind them is at its brightest — see the
// comment there for the actual contrast numbers. Blur increases alongside
// alpha (8px / 14px) so the strongest panel is also the calmest backdrop
// for reading, not just the darkest.
const FROST_CONFIG: Record<GlassFrost, { alpha: number; blur: number }> = {
  none: { alpha: 0, blur: 0 },
  light: { alpha: glassTokens.lightGlassAlpha, blur: 8 },
  heavy: { alpha: glassTokens.heavyGlassAlpha, blur: 14 },
};

interface GlassCardProps extends PaperProps {
  /** How strongly frosted this panel is. Defaults to "heavy". Use "none" for
   * a plain transparent shell around other frosted panels (no text of its
   * own); "light" for a panel with text that should still read as closer to
   * see-through than "heavy". Both "light" and "heavy" guarantee accessible
   * text contrast — "light" is not a lighter-touch accessibility trade-off,
   * just a visually calmer one. */
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
        background: frostAlpha > 0 ? alpha("#000000", frostAlpha) : "transparent",
        backdropFilter: isFrosted ? `blur(${blur}px)` : "none",
        WebkitBackdropFilter: isFrosted ? `blur(${blur}px)` : "none",
        // A light edge, not a dark one: once a panel is frosted its interior
        // reads as a dark surface regardless of what's behind it (that's the
        // point of the tint), so a light border is what actually stays
        // visible against both the dark interior and an arbitrary, variable
        // photo just outside it. Kept even on "none" for the same reason
        // GlassCard existed in the first place — a visible edge so a
        // transparent panel doesn't look like a layout accident.
        border: `1px solid ${alpha("#FFFFFF", frost === "none" ? 0.16 : 0.22)}`,
        borderRadius: Number(theme.shape.borderRadius),
        boxShadow: frost === "none" ? "none" : "0 8px 32px 0 rgba(0, 0, 0, 0.4)",

        // Fallback for browsers without backdrop-filter support
        ...(isFrosted && {
          "@supports not ((backdrop-filter: blur(1px)) or (-webkit-backdrop-filter: blur(1px)))": {
            background: "rgba(13, 31, 51, 0.94)",
          },
          // Respect users who've asked for reduced transparency
          "@media (prefers-reduced-transparency: reduce)": {
            background: "rgba(13, 31, 51, 0.96)",
            backdropFilter: "none",
            WebkitBackdropFilter: "none",
          },
        }),

        ...sx,
      }}
    />
  );
}
