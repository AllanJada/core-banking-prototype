import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import {
  Alert,
  Box,
  Button,
  IconButton,
  InputAdornment,
  Link,
  Snackbar,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import VisibilityIcon from "@mui/icons-material/Visibility";
import VisibilityOffIcon from "@mui/icons-material/VisibilityOff";
import { login as loginRequest } from "../api/client";
import cardLogo from "../assets/cardLogo.png";
import { useAuth } from "../context/AuthContext";
import GlassCard from "../components/GlassCard";
import LoginBackground from "../components/LoginBackground";
import SecurityHighlights from "../components/SecurityHighlights";
import { glassTokens } from "../theme";

// Translucent styling for inputs sitting on the glass panel. Border/label
// colours use glassTokens.accent (not the app's base teal) for the same
// contrast reason as everywhere else on this screen — see theme.ts.
//
// The `:-webkit-autofill` block exists for a real, verified bug, not a
// hypothetical: Chrome forces its own opaque fill (measured on the live page —
// rgb(232,240,254), its standard autofill blue) and forces black input text
// (webkitTextFillColor: rgb(0,0,0)) on any field it autofills, both applied
// with higher specificity than a normal backgroundColor/color override can
// beat. On a solid light form that's barely noticeable; on this dark glass
// panel it replaces the intended translucent field with an opaque light-blue
// box, which is exactly the "padding looks wrong" effect Ramsey flagged —
// the notched label (correct, standard MUI behaviour — it's meant to overlap
// the border line by design) suddenly reads as broken because it's sitting
// against a jarring solid colour instead of the translucent glass it was
// designed against.
//
// First attempt used the field's own translucent colour
// (rgba(255,255,255,0.07)) for the override box-shadow — checked live on the
// running page and it did NOT work: Chrome's own fill is opaque and sits
// underneath at a layer a 7%-alpha shadow can't fully mask, so the pale blue
// still showed through almost unchanged. An inset box-shadow can only truly
// win against Chrome's fill by being opaque itself, so this uses
// glassTokens.autofillBg (navyDark) instead — a flat approximation of the
// panel's own dark tone rather than genuine translucency, which Chrome
// doesn't leave a way to preserve here. `-webkit-text-fill-color` restores
// the intended light text colour (a plain `color` override does not work on
// autofilled text — Chrome ignores it).
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
  "& input:-webkit-autofill, & input:-webkit-autofill:hover, & input:-webkit-autofill:focus": {
    WebkitBoxShadow: `0 0 0 100px ${glassTokens.autofillBg} inset`,
    WebkitTextFillColor: glassTokens.textStrong,
    caretColor: glassTokens.textStrong,
  },
};

// Secondary touch only, not the primary contrast fix — the scrim strength in
// GlassCard/glassTokens is what actually guarantees readable text. This just
// softens the edge slightly further.
const textShadowSx = {
  textShadow: "0 1px 3px rgba(0,0,0,0.5)",
};

const linkSx = {
  color: glassTokens.textLink,
  fontSize: 13,
  cursor: "pointer",
  ...textShadowSx,
  "&:hover": { color: glassTokens.textStrong },
};

export default function LoginPage() {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [infoMessage, setInfoMessage] = useState<string | null>(null);

  const { login } = useAuth();
  const navigate = useNavigate();

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const user = await loginRequest(username.trim(), password);
      login(user);
      navigate("/dashboard", { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Login failed");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <LoginBackground>
      <GlassCard
        frost="none"
        elevation={0}
        sx={{
          width: "100%",
          maxWidth: 840,
          display: "flex",
          flexDirection: { xs: "column", md: "row" },
          overflow: "hidden",
        }}
      >
        {/* Branding panel — frosted enough to guarantee readable text (see
            glassTokens in theme.ts), but the lighter of the two panels so it
            still reads as closer to see-through than the form beside it. */}
        <GlassCard
          frost="light"
          elevation={0}
          sx={{
            flex: 1,
            p: { xs: 3, md: 5 },
            display: "flex",
            flexDirection: "column",
            gap: 3,
            alignItems: { xs: "center", md: "flex-start" },
            textAlign: { xs: "center", md: "left" },
            border: "none",
            borderRadius: 0,
            boxShadow: "none",
            borderRight: { md: "1px solid rgba(255,255,255,0.2)" },
            borderBottom: { xs: "1px solid rgba(255,255,255,0.2)", md: "none" },
          }}
        >
            <Box
              sx={{
                width: "100%",
                display: "flex",
                flexDirection: "column",
                alignItems: "center",
                justifyContent: "center",
                textAlign: "center",
                flex: 1,
              }}
            >
              <Box
                component="img"
                src={cardLogo}
                alt="Bank of Tanzania logo"
                sx={{ width: { xs: 120, md: 150 }, height: "auto", display: "block", mx: "auto" }}
              />
            </Box>

          <Box>
            <Typography
              variant="h6"
              sx={{ color: glassTokens.textStrong, ...textShadowSx, textAlign: "center" }}
            >
              Secure File Transfer
            </Typography>
            <Typography variant="body2" sx={{ color: glassTokens.textWeak, mt: 0.5, ...textShadowSx }}>
              Direct, auditable file exchange between financial institutions.
            </Typography>
          </Box>

          <Box sx={{ display: { xs: "none", md: "block" }, width: "100%" }}>
            <SecurityHighlights />
          </Box>
        </GlassCard>

        {/* Form panel — the more strongly frosted of the two, since input
            legibility here matters more than how much photo shows through. */}
        <GlassCard
          frost="heavy"
          elevation={0}
          component="form"
          onSubmit={handleSubmit}
          sx={{ flex: 1, p: { xs: 3, md: 5 }, border: "none", borderRadius: 0, boxShadow: "none" }}
        >
          <Stack spacing={2.5}>
            <Box>
              <Typography variant="h6" sx={{ color: glassTokens.textStrong, ...textShadowSx }}>
                Sign in
              </Typography>
              <Typography variant="body2" sx={{ color: glassTokens.textWeak, ...textShadowSx }}>
                Use your institution account
              </Typography>
            </Box>

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

            {/* Solid fill, so this is a plain white-vs-teal contrast question —
                the glass-panel accent colour doesn't apply here, since the
                button isn't translucent against the photo. Text is navy, not
                white: white-on-this-teal only reaches ~3.3:1 (fails the 4.5:1
                button-text minimum), navy-on-teal reaches 5:1. */}
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
                boxShadow: "0 4px 14px rgba(31, 158, 137, 0.4)",
                "&:hover": { bgcolor: "#28B79E" },
              }}
            >
              {submitting ? "Signing in…" : "Sign In"}
            </Button>

            <Stack direction="row" sx={{ justifyContent: "space-between" }}>
              <Link
                sx={linkSx}
                underline="hover"
                onClick={() =>
                  setInfoMessage("Self-service account creation isn't available yet. Contact your administrator.")
                }
              >
                Create account
              </Link>
              <Link
                sx={linkSx}
                underline="hover"
                onClick={() =>
                  setInfoMessage("Password recovery isn't available yet. Contact your administrator.")
                }
              >
                Forgot password?
              </Link>
            </Stack>
          </Stack>
        </GlassCard>
      </GlassCard>

      <Snackbar
        open={infoMessage !== null}
        autoHideDuration={4000}
        onClose={() => setInfoMessage(null)}
        message={infoMessage}
      />
    </LoginBackground>
  );
}
