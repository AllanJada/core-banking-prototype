import { createTheme } from "@mui/material/styles";

// A small, intentional palette rather than MUI's default blue:
// deep navy for structure, a teal for "settled/received", amber for "in flight".
// These map directly onto the two transfer statuses, so color carries meaning,
// not just decoration.
const navy = "#16324F";
const navyDark = "#0D1F33";
const teal = "#1F9E89";
const amber = "#C6820F";

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
    },
    success: {
      main: teal,
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
    h1: { fontWeight: 600 },
    h2: { fontWeight: 600 },
    h3: { fontWeight: 600 },
    h4: { fontWeight: 600 },
    h5: { fontWeight: 600 },
    h6: { fontWeight: 600 },
    button: {
      textTransform: "none",
      fontWeight: 600,
    },
  },
  shape: {
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
  },
});

// Applied selectively to data cells (filenames, timestamps, transfer ids) so the
// tables read like a ledger — a small, deliberate signature rather than a
// wholesale font swap across the whole UI.
export const dataFontFamily = '"IBM Plex Mono", "Courier New", monospace';
