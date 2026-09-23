import { alpha, createTheme } from "@mui/material/styles";

/**
 * The application theme, built on the structure MUI's own dashboard templates use
 * (see docs/data/material/getting-started/templates/shared-theme): a set of colour
 * ramps as primitives, then component customizations expressed against them.
 *
 * The palette is this application's own rather than the template's blue — deep navy for
 * structure, teal for "settled/received", amber for "in flight" — because those colours
 * already carry meaning in the tables: they map onto transfer and payment statuses, so
 * colour here is information, not decoration. What is adopted from the template is the
 * *language*: a near-white canvas, hairline borders instead of shadows, an 8px radius,
 * pill-shaped tinted chips, and a compact 14px type scale.
 */

const defaultTheme = createTheme();

/** Cool blue-grey, the neutral the whole UI is built on. */
export const gray = {
  50: "hsl(220, 35%, 97%)",
  100: "hsl(220, 30%, 94%)",
  200: "hsl(220, 20%, 88%)",
  300: "hsl(220, 20%, 80%)",
  400: "hsl(220, 20%, 65%)",
  500: "hsl(220, 20%, 42%)",
  600: "hsl(220, 20%, 35%)",
  700: "hsl(220, 20%, 25%)",
  800: "hsl(220, 30%, 12%)",
  900: "hsl(220, 35%, 6%)",
};

/** Navy: structure and primary actions. 700 is the original brand #16324F. */
export const navy = {
  50: "hsl(210, 55%, 96%)",
  100: "hsl(210, 52%, 90%)",
  200: "hsl(210, 45%, 78%)",
  300: "hsl(210, 42%, 60%)",
  400: "hsl(210, 45%, 40%)",
  500: "hsl(210, 52%, 30%)",
  600: "hsl(210, 57%, 24%)",
  700: "hsl(210, 57%, 20%)",
  800: "hsl(210, 60%, 14%)",
  900: "hsl(210, 62%, 10%)",
};

/** Teal: money that has settled, a signature that verified. 400 is the original #1F9E89. */
export const teal = {
  50: "hsl(170, 60%, 96%)",
  100: "hsl(170, 58%, 88%)",
  200: "hsl(170, 55%, 75%)",
  300: "hsl(170, 52%, 58%)",
  400: "hsl(170, 67%, 37%)",
  500: "hsl(170, 72%, 30%)",
  600: "hsl(170, 75%, 24%)",
  700: "hsl(170, 78%, 18%)",
  800: "hsl(170, 80%, 12%)",
  900: "hsl(170, 82%, 8%)",
};

/** Amber: in flight, awaiting a decision. 400 is the original #C6820F. */
export const amber = {
  50: "hsl(42, 100%, 96%)",
  100: "hsl(42, 94%, 88%)",
  200: "hsl(42, 92%, 76%)",
  300: "hsl(40, 90%, 60%)",
  400: "hsl(39, 86%, 42%)",
  500: "hsl(39, 90%, 34%)",
  600: "hsl(39, 92%, 27%)",
  700: "hsl(39, 94%, 21%)",
  800: "hsl(39, 95%, 15%)",
  900: "hsl(39, 93%, 11%)",
};

/** Red: refused, invalid, out of balance. */
export const red = {
  50: "hsl(0, 100%, 97%)",
  100: "hsl(0, 92%, 90%)",
  200: "hsl(0, 94%, 80%)",
  300: "hsl(0, 90%, 65%)",
  400: "hsl(0, 78%, 46%)",
  500: "hsl(0, 82%, 38%)",
  600: "hsl(0, 85%, 30%)",
  700: "hsl(0, 88%, 24%)",
  800: "hsl(0, 90%, 16%)",
  900: "hsl(0, 90%, 10%)",
};

export const theme = createTheme({
  palette: {
    mode: "light",
    primary: {
      light: navy[300],
      main: navy[700],
      dark: navy[900],
      contrastText: "#FFFFFF",
    },
    secondary: {
      light: teal[200],
      main: teal[400],
      dark: teal[700],
      contrastText: "#FFFFFF",
    },
    info: {
      light: navy[100],
      main: navy[400],
      dark: navy[700],
      contrastText: "#FFFFFF",
    },
    success: {
      light: teal[200],
      main: teal[400],
      dark: teal[700],
    },
    warning: {
      light: amber[200],
      main: amber[400],
      dark: amber[700],
    },
    error: {
      light: red[200],
      main: red[400],
      dark: red[700],
    },
    grey: { ...gray },
    divider: alpha(gray[300], 0.7),
    background: {
      // Canvas slightly off-white, surfaces pure white: the hairline borders below are
      // what separates them, rather than drop shadows.
      default: "hsl(0, 0%, 99%)",
      paper: "#FFFFFF",
    },
    text: {
      primary: gray[800],
      secondary: gray[500],
    },
    action: {
      hover: alpha(gray[200], 0.4),
      selected: alpha(navy[100], 0.6),
    },
  },

  typography: {
    fontFamily: '"IBM Plex Sans", "Helvetica Neue", Arial, sans-serif',
    h1: { fontSize: defaultTheme.typography.pxToRem(44), fontWeight: 600, lineHeight: 1.2 },
    h2: { fontSize: defaultTheme.typography.pxToRem(34), fontWeight: 600, lineHeight: 1.2 },
    h3: { fontSize: defaultTheme.typography.pxToRem(28), fontWeight: 600, lineHeight: 1.25 },
    // The page-title size across every dashboard screen.
    h4: { fontSize: defaultTheme.typography.pxToRem(24), fontWeight: 600, lineHeight: 1.4 },
    h5: { fontSize: defaultTheme.typography.pxToRem(20), fontWeight: 600 },
    h6: { fontSize: defaultTheme.typography.pxToRem(17), fontWeight: 600 },
    subtitle1: { fontSize: defaultTheme.typography.pxToRem(16), fontWeight: 500 },
    subtitle2: { fontSize: defaultTheme.typography.pxToRem(14), fontWeight: 500 },
    body1: { fontSize: defaultTheme.typography.pxToRem(14) },
    body2: { fontSize: defaultTheme.typography.pxToRem(14) },
    caption: { fontSize: defaultTheme.typography.pxToRem(12), lineHeight: 1.5 },
    button: { textTransform: "none", fontWeight: 600 },
  },

  shape: { borderRadius: 8 },

  components: {
    /* ---------------------------------------------------------------- surfaces */
    MuiPaper: {
      defaultProps: { elevation: 0 },
      styleOverrides: { root: { backgroundImage: "none" } },
    },
    MuiCard: {
      defaultProps: { variant: "outlined" },
      styleOverrides: {
        root: ({ theme: t }) => ({
          padding: 16,
          borderRadius: t.shape.borderRadius,
          border: `1px solid ${t.palette.divider}`,
          backgroundColor: t.palette.background.paper,
          boxShadow: "none",
        }),
      },
    },
    MuiCardContent: {
      styleOverrides: { root: { padding: 0, "&:last-child": { paddingBottom: 0 } } },
    },
    MuiCardHeader: { styleOverrides: { root: { padding: 0 } } },
    MuiCardActions: { styleOverrides: { root: { padding: 0 } } },

    /* ---------------------------------------------------------------- inputs */
    MuiButton: {
      styleOverrides: {
        root: ({ theme: t }) => ({
          boxShadow: "none",
          borderRadius: t.shape.borderRadius,
          textTransform: "none",
          variants: [
            { props: { size: "small" }, style: { padding: "5px 10px" } },
            {
              props: { variant: "outlined" },
              style: {
                color: t.palette.text.primary,
                borderColor: gray[200],
                backgroundColor: alpha(gray[50], 0.4),
                "&:hover": { backgroundColor: gray[100], borderColor: gray[300] },
              },
            },
            {
              props: { variant: "contained", color: "primary" },
              style: {
                "&:hover": { backgroundColor: navy[800], boxShadow: "none" },
              },
            },
          ],
        }),
      },
    },
    MuiIconButton: {
      styleOverrides: {
        root: ({ theme: t }) => ({ borderRadius: t.shape.borderRadius }),
      },
    },
    MuiOutlinedInput: {
      styleOverrides: {
        root: ({ theme: t }) => ({
          borderRadius: t.shape.borderRadius,
          backgroundColor: t.palette.background.paper,
        }),
      },
    },

    /* ---------------------------------------------------------------- navigation */
    // The sidebar look from the template: compact rows, rounded selection, muted until
    // selected, so the current section is legible at a glance without a heavy highlight.
    MuiListItemButton: {
      styleOverrides: {
        root: ({ theme: t }) => ({
          borderRadius: t.shape.borderRadius,
          padding: "6px 10px",
          gap: 10,
          opacity: 0.8,
          "& .MuiSvgIcon-root": { fontSize: "1.15rem", color: t.palette.text.secondary },
          "&.Mui-selected": {
            opacity: 1,
            backgroundColor: alpha(navy[100], 0.7),
            "& .MuiSvgIcon-root": { color: t.palette.primary.main },
            "& .MuiListItemText-primary": { fontWeight: 600 },
            "&:hover": { backgroundColor: alpha(navy[100], 0.9) },
          },
        }),
      },
    },
    MuiListItemIcon: { styleOverrides: { root: { minWidth: 0 } } },
    MuiListItemText: {
      styleOverrides: {
        primary: ({ theme: t }) => ({
          fontSize: t.typography.body2.fontSize,
          fontWeight: 500,
        }),
        secondary: ({ theme: t }) => ({ fontSize: t.typography.caption.fontSize }),
      },
    },

    /* ---------------------------------------------------------------- data display */
    // Pill chips with a tinted fill and a matching border, one variant per status colour.
    // Filled and outlined deliberately land in the same place: a status is a status, and
    // the tables read more calmly when the only difference between two of them is hue.
    MuiChip: {
      defaultProps: { size: "small" },
      styleOverrides: {
        root: ({ theme: t }) => ({
          borderRadius: "999px",
          border: "1px solid",
          fontWeight: 600,
          "& .MuiChip-label": { fontWeight: 600 },
          variants: [
            {
              props: { size: "small" },
              style: {
                height: 22,
                "& .MuiChip-label": { fontSize: t.typography.caption.fontSize, px: 1 },
                "& .MuiSvgIcon-root": { fontSize: "0.9rem" },
              },
            },
            {
              props: { color: "default" },
              style: {
                borderColor: gray[200],
                backgroundColor: gray[100],
                color: gray[600],
                "& .MuiChip-icon": { color: gray[500] },
              },
            },
            {
              props: { color: "success" },
              style: {
                borderColor: teal[200],
                backgroundColor: teal[50],
                color: teal[600],
                "& .MuiChip-icon": { color: teal[500] },
              },
            },
            {
              props: { color: "warning" },
              style: {
                borderColor: amber[200],
                backgroundColor: amber[50],
                color: amber[600],
                "& .MuiChip-icon": { color: amber[500] },
              },
            },
            {
              props: { color: "error" },
              style: {
                borderColor: red[100],
                backgroundColor: red[50],
                color: red[500],
                "& .MuiChip-icon": { color: red[400] },
              },
            },
            {
              props: { color: "info" },
              style: {
                borderColor: navy[100],
                backgroundColor: navy[50],
                color: navy[600],
                "& .MuiChip-icon": { color: navy[400] },
              },
            },
          ],
        }),
      },
    },
    MuiTableCell: {
      styleOverrides: {
        root: ({ theme: t }) => ({
          borderColor: t.palette.divider,
          paddingTop: 10,
          paddingBottom: 10,
        }),
        head: ({ theme: t }) => ({
          fontSize: t.typography.caption.fontSize,
          fontWeight: 600,
          letterSpacing: "0.03em",
          textTransform: "uppercase",
          color: t.palette.text.secondary,
          backgroundColor: alpha(gray[50], 0.7),
          whiteSpace: "nowrap",
        }),
      },
    },
    MuiTableRow: {
      styleOverrides: {
        root: ({ theme: t }) => ({
          "&:hover > .MuiTableCell-body": { backgroundColor: alpha(gray[50], 0.8) },
          // The last row sits on the card's own border, so it needs none of its own.
          "&:last-of-type > .MuiTableCell-body": { borderBottom: "none" },
          transition: t.transitions.create("background-color", { duration: 120 }),
        }),
      },
    },
    MuiDivider: { styleOverrides: { root: ({ theme: t }) => ({ borderColor: t.palette.divider }) } },

    /* ---------------------------------------------------------------- feedback */
    MuiAlert: {
      styleOverrides: {
        // Per-severity tints are expressed as variants rather than `standardError` and
        // friends: those slots no longer exist, the severity-specific classes are now
        // colorError/colorSuccess/… and only apply on top of a variant.
        root: ({ theme: t }) => ({
          borderRadius: t.shape.borderRadius,
          border: "1px solid",
          variants: [
            {
              props: { severity: "error" },
              style: { borderColor: alpha(red[200], 0.6), backgroundColor: red[50] },
            },
            {
              props: { severity: "warning" },
              style: { borderColor: alpha(amber[200], 0.7), backgroundColor: amber[50] },
            },
            {
              props: { severity: "info" },
              style: { borderColor: alpha(navy[100], 0.8), backgroundColor: navy[50] },
            },
            {
              props: { severity: "success" },
              style: { borderColor: alpha(teal[200], 0.7), backgroundColor: teal[50] },
            },
          ],
        }),
      },
    },
    MuiDialog: {
      styleOverrides: {
        paper: ({ theme: t }) => ({
          borderRadius: 12,
          border: `1px solid ${t.palette.divider}`,
          backgroundImage: "none",
        }),
      },
    },
    MuiTooltip: {
      styleOverrides: {
        tooltip: ({ theme: t }) => ({
          borderRadius: 6,
          backgroundColor: gray[800],
          fontSize: t.typography.caption.fontSize,
        }),
      },
    },
  },
});

// Applied selectively to data cells (account numbers, references, filenames, timestamps)
// so the tables read like a ledger — a small, deliberate signature rather than a
// wholesale font swap across the whole UI.
export const dataFontFamily = '"IBM Plex Mono", "Courier New", monospace';
