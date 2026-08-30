import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import {
  Alert,
  Box,
  Button,
  IconButton,
  InputAdornment,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import AccountBalanceOutlinedIcon from "@mui/icons-material/AccountBalanceOutlined";
import VisibilityIcon from "@mui/icons-material/Visibility";
import VisibilityOffIcon from "@mui/icons-material/VisibilityOff";
import { login as loginRequest } from "../api/client";
import { useAuth } from "../context/AuthContext";
import GlassCard from "../components/GlassCard";
import LoginBackground from "../components/LoginBackground";
import AuthNavMenu from "../components/AuthNavMenu";
import { glassTokens } from "../theme";

// Rebuilt against glassTokens rather than the hardcoded values this page
// originally shipped with (rgba(255,255,255,0.05) background, plain #fff/
// rgba(255,255,255,0.65) text, hardcoded #1F9E89 accent). Those predate the
// UI/UX pass on the regular LoginPage.tsx and would reintroduce the exact
// contrast failures that pass fixed: the old text alpha here (0.65) is lower
// than the 0.82 glassTokens.textWeak was specifically calculated to clear
// 4.5:1 against a worst-case bright patch of the background photo, and the
// autofill fix below didn't exist here at all. Same reasoning as LoginPage.tsx
// — see theme.ts for the underlying contrast math.
const glassInputSx = {
  "& .MuiOutlinedInput-root": {
    backgroundColor: "rgba(255,255,255,0.07)",
    "& fieldset": { borderColor: "rgba(255,255,255,0.3)" },
    "&:hover fieldset": { borderColor: "rgba(255,255,255,0.5)" },
    "&.Mui-focused fieldset": { borderColor: glassTokens.accent, borderWidth: 2 },
  },
  "& .MuiInputBase-input": { color: glassTokens.textStrong },
  "& .MuiInputLabel-root": { color: glassTokens.textWeak },
  "& .MuiInputLabel-root.Mui-focused": { color: glassTokens.accent },
  // Same verified Chrome-autofill fix as LoginPage.tsx — see that file's
  // comment for the full diagnosis. Any translucent field with a saved
  // username/password is subject to it, not just the original login page.
  "& input:-webkit-autofill, & input:-webkit-autofill:hover, & input:-webkit-autofill:focus": {
    WebkitBoxShadow: `0 0 0 100px ${glassTokens.autofillBg} inset`,
    WebkitTextFillColor: glassTokens.textStrong,
    caretColor: glassTokens.textStrong,
  },
};

const textShadowSx = {
  textShadow: "0 1px 3px rgba(0,0,0,0.65), 0 1px 8px rgba(0,0,0,0.4)",
};

export default function BankLoginPage() {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const { login } = useAuth();
  const navigate = useNavigate();

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const user = await loginRequest(username.trim(), password);
      if (user.userType !== "BANK") {
        setError("This is not a bank account. Use the menu in the top-left corner to switch to Institution Login.");
        return;
      }
      login(user);
      navigate("/bank-dashboard", { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Login failed");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <LoginBackground>
      <AuthNavMenu targetLabel="Institution Login" targetPath="/login" />
      <GlassCard
        frost="heavy"
        elevation={0}
        component="form"
        onSubmit={handleSubmit}
        sx={{ p: { xs: 3, md: 5 }, width: "100%", maxWidth: 420 }}
      >
        <Stack spacing={2.5}>
          <Stack spacing={1} sx={{ alignItems: "center", textAlign: "center" }}>
            <Box
              sx={{
                width: 56,
                height: 56,
                borderRadius: "50%",
                display: "flex",
                alignItems: "center",
                justifyContent: "center",
                background: "rgba(255,255,255,0.12)",
                border: "1px solid rgba(255,255,255,0.2)",
              }}
            >
              <AccountBalanceOutlinedIcon sx={{ color: "#fff", fontSize: 28 }} />
            </Box>
            <Typography variant="h6" sx={{ fontWeight: 700, color: glassTokens.textStrong, ...textShadowSx }}>
              Bank Login
            </Typography>
            <Typography variant="body2" sx={{ color: glassTokens.textWeak, ...textShadowSx }}>
              For registered bank accounts only
            </Typography>
          </Stack>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField
            label="Username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoFocus
            fullWidth
            required
            sx={glassInputSx}
          />
          <TextField
            label="Password"
            type={showPassword ? "text" : "password"}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            fullWidth
            required
            sx={glassInputSx}
            slotProps={{
              input: {
                endAdornment: (
                  <InputAdornment position="end">
                    <IconButton
                      onClick={() => setShowPassword((v) => !v)}
                      edge="end"
                      sx={{ color: glassTokens.textWeak }}
                      aria-label={showPassword ? "Hide password" : "Show password"}
                    >
                      {showPassword ? <VisibilityOffIcon /> : <VisibilityIcon />}
                    </IconButton>
                  </InputAdornment>
                ),
              },
            }}
          />
          {/* Same fix as LoginPage.tsx's Sign In button: variant="contained" with
              no explicit color inherits palette.primary.contrastText (white, since
              primary is navy-based) rather than anything derived from this button's
              own bgcolor override — so without an explicit navy text color here,
              this button would render white-on-#1F9E89, the same ~3.3:1 failure
              already measured and fixed on the other login page. */}
          <Button
            type="submit"
            variant="contained"
            size="large"
            disabled={submitting}
            fullWidth
            sx={{
              py: 1.3,
              bgcolor: "#1F9E89",
              color: "#0D1F33",
              fontWeight: 700,
              boxShadow: "0 4px 14px rgba(31, 158, 137, 0.35)",
              "&:hover": { bgcolor: "#188275" },
            }}
          >
            {submitting ? "Signing in…" : "Sign In"}
          </Button>
        </Stack>
      </GlassCard>
    </LoginBackground>
  );
}
