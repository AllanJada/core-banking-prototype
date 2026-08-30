import { alpha, createTheme } from "@mui/material/styles";

// ---------------------------------------------------------------------------
// Colour
//
// A small, intentional palette rather than MUI's default blue: deep navy for
// structure, a teal for "settled/received", amber for "in flight". These map
// directly onto the two transfer statuses, so color carries meaning, not
// just decoration — the brand colour is reserved for interactive elements
// (buttons, links, focus) so people learn what's clickable.
const navy = "#16324F";
const navyDark = "#0D1F33";
const teal = "#1F9E89";
const amber = "#C6820F";

// A brighter tint of the same teal, used only on the login screen's dark
// glass panels (see glassTokens below / GlassCard.tsx). The base teal above
// doesn't clear the 3:1 contrast a focus ring needs against a worst-case
// bright patch of the background photo once blended through the panel's
// tint (checked: ~1.9-2.6:1, depending on tint strength) — this variant
// does (~2.9-4.4:1, see glassTokens comment). Everywhere else — buttons,
// success states, focus rings on ordinary white/paper backgrounds — keeps
// using the base teal; this exists only for text/controls sitting directly
// on the photo.
const tealOnGlass = "#3ABFA8";

export const theme = createTheme({
  palette: {
    mode: "light",
    primary: {
      main: navy,
      dark: navyDark,
    },
    secondary: {
      main: teal,
    },
    warning: {
      main: amber,
      // MUI auto-derives white as contrastText for both of these unless told
      // otherwise — checked and it doesn't clear the mark: white-on-amber is
      // 3.18:1, white-on-teal 3.33:1, both under the 4.5:1 small-text/button-
      // text minimum (this is exactly the same failure the Sign In button on
      // the login page had, from the same root cause — a mid-brightness
      // brand colour that reads as "dark enough" for white text but isn't).
      // navyDark clears both comfortably (5.24:1 / 5.0:1) and keeps every
      // filled chip/button reading as "on-brand" rather than pulling in an
      // unrelated dark colour just for this.
      contrastText: navyDark,
    },
    success: {
      main: teal,
      contrastText: navyDark,
    },
    // Not used for anything structural — only SignatureChip's "Signed"
    // state (signed, not yet verified), kept visually distinct from navy/
    // teal/amber/red so all four chip states stay tellable apart at a
    // glance. MUI's default info blue (#0288d1) is too light here: 3.86:1
    // as outlined-chip text and 2.54:1 for its border, both under threshold
    // (4.5:1 text, 3:1 non-text UI). This is MUI's own darker "info.dark"
    // swatch, not an invented colour — 7.4:1 text / 3.74:1 border.
    info: {
      main: "#01579B",
    },
    background: {
      default: "#F4F6F8",
      paper: "#FFFFFF",
    },
    text: {
      primary: "#16202A",
      secondary: "#51606E",
    },
  },
  typography: {
    fontFamily: '"IBM Plex Sans", "Helvetica Neue", Arial, sans-serif',
    // A Major Second (1.125) progression rather than MUI's stock sizes:
    // dense, data-heavy screens (this dashboard, its tables, the multi-field
    // slip form) read better with smaller steps between sizes than a
    // marketing site's wider ratio would give — more distinct sizes fit
    // before anything feels like a jump. Sizes are rounded to whole pixels;
    // line-height loosens as size drops (headings ~1.2-1.4, body copy
    // ~1.5+) so paragraph text stays comfortable while headings stay tight.
    // Weight is limited to 600/700 for headings and 400 for body text
    // throughout — no in-between weights to keep usage unambiguous.
    h1: { fontSize: "2.5rem", lineHeight: 1.2, fontWeight: 700 }, // 40px
    h2: { fontSize: "2rem", lineHeight: 1.25, fontWeight: 700 }, // 32px
    h3: { fontSize: "1.625rem", lineHeight: 1.3, fontWeight: 700 }, // 26px
    h4: { fontSize: "1.4375rem", lineHeight: 1.3, fontWeight: 700 }, // 23px
    h5: { fontSize: "1.25rem", lineHeight: 1.35, fontWeight: 600 }, // 20px
    h6: { fontSize: "1.125rem", lineHeight: 1.4, fontWeight: 700 }, // 18px — the de facto panel/dialog title size in this app
    subtitle1: { fontSize: "1rem", lineHeight: 1.5, fontWeight: 600 }, // 16px
    subtitle2: { fontSize: "0.875rem", lineHeight: 1.45, fontWeight: 600 }, // 14px — form section labels
    body1: { fontSize: "0.9375rem", lineHeight: 1.55 }, // 15px
    body2: { fontSize: "0.875rem", lineHeight: 1.55 }, // 14px
    caption: { fontSize: "0.75rem", lineHeight: 1.5 }, // 12px
    button: {
      textTransform: "none",
      fontWeight: 600,
    },
  },
  shape: {
    // Small and deliberate rather than the bubbly 12-16px radius common on
    // template dashboards — reads as restrained/serious, which suits a
    // banking tool. Applied consistently: this is the *only* radius token
    // in the app now (previously a couple of components hand-set 16px
    // locally, out of step with every card/table using this 6px value).
    borderRadius: 6,
  },
  components: {
    MuiPaper: {
      styleOverrides: {
        root: {
          backgroundImage: "none",
        },
      },
    },
    MuiAppBar: {
      styleOverrides: {
        root: {
          backgroundColor: navy,
        },
      },
    },
    MuiChip: {
      styleOverrides: {
        root: {
          fontFamily: '"IBM Plex Mono", "Courier New", monospace',
          fontSize: "0.75rem",
          fontWeight: 600,
          letterSpacing: "0.02em",
        },
      },
    },
    MuiButton: {
      styleOverrides: {
        // MUI's own default for an outlined button's border is alpha(colour,
        // 0.5) — for every outlined button in this app that resolves to
        // navy@50%, which measures 2.93:1 against white, just under the 3:1
        // WCAG minimum for a non-text UI boundary. Every outlined button
        // here uses the default (primary/navy) colour — none pass a
        // different `color` prop — so raising the alpha a bit is safe app-
        // wide: navy@60% clears it (3.84:1) without reading as a noticeably
        // heavier border than before.
        outlined: {
          borderColor: alpha(navy, 0.6),
        },
      },
    },
  },
});

// Applied selectively to data cells (filenames, timestamps, transfer ids) so the
// tables read like a ledger — a small, deliberate signature rather than a
// wholesale font swap across the whole UI.
export const dataFontFamily = '"IBM Plex Mono", "Courier New", monospace';

// ---------------------------------------------------------------------------
// Login-screen glass tokens
//
// Pulled out here rather than left as inline magic numbers in GlassCard/
// LoginPage so the reasoning lives in one place. Every value below was
// checked against the worst realistic case — a pure-white patch of the
// background photo showing through the tint — using the WCAG 2.1 contrast
// formula (relative luminance, not an eyeballed guess):
//
//   scrim = black overlay blended over the photo pixel at the given alpha
//   text  = white blended over that scrim at the given text alpha
//   ratio = contrast(text, scrim)
//
// lightGlassAlpha (0.66, the branding panel) and heavyGlassAlpha (0.74, the
// form panel) both keep every text tier below at >=4.5:1 — the small-text
// WCAG AA threshold — even in that worst case; `accent` clears >=3:1 (the
// threshold for non-text UI like a focus ring) on both. The previous values
// (0.05-0.16 alpha) failed this outright — as low as 1.08:1 in bright areas
// of the photo — and leaned on a text-shadow halo to compensate, which only
// softens local pixel-level noise, not a genuinely bright region of the
// photo sitting behind an entire panel. Text-shadow is kept as a secondary
// touch, not the primary fix.
export const glassTokens = {
  lightGlassAlpha: 0.66,
  heavyGlassAlpha: 0.74,
  textStrong: "rgba(255,255,255,0.96)",
  textWeak: "rgba(255,255,255,0.82)",
  textLink: "rgba(255,255,255,0.88)",
  accent: tealOnGlass,
  // Fallback surface colour for the one spot true translucency isn't
  // achievable: a browser-autofilled input. Verified live (not assumed) that
  // Chrome paints its own opaque fill underneath whatever box-shadow an app
  // supplies — a translucent override (tried first, e.g. rgba(255,255,255,0.07))
  // still shows Chrome's pale autofill blue through it, since a low-alpha
  // shadow can't mask an opaque layer the way a fully opaque one can. Reusing
  // navyDark rather than inventing a new colour keeps the fallback inside the
  // app's own dark end of the palette instead of an arbitrary flat grey.
  autofillBg: navyDark,
};
