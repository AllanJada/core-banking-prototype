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

// Translucent styling for inputs sitting on the glass panel.
const glassInputSx = {
  "& .MuiOutlinedInput-root": {
    backgroundColor: "rgba(255,255,255,0.05)",
    borderRadius: 2,
    "& fieldset": { borderColor: "rgba(255,255,255,0.25)" },
    "&:hover fieldset": { borderColor: "rgba(255,255,255,0.45)" },
    "&.Mui-focused fieldset": { borderColor: "#1F9E89" },
  },
  "& .MuiInputBase-input": { color: "#fff" },
  "& .MuiInputLabel-root": { color: "rgba(255,255,255,0.75)" },
  "& .MuiInputLabel-root.Mui-focused": { color: "#1F9E89" },
};

// Readability workaround, independent of the glass panels themselves: a soft
// dark halo behind light text so it stays legible over whatever happens to
// be directly behind it in the photo (sky, glass, foliage all vary a lot).
// Doesn't touch GlassCard's blur/opacity/frost config at all.
const textShadowSx = {
  textShadow: "0 1px 3px rgba(0,0,0,0.65), 0 1px 8px rgba(0,0,0,0.4)",
};

const linkSx = {
  color: "rgba(255,255,255,0.75)",
  fontSize: 13,
  cursor: "pointer",
  ...textShadowSx,
  "&:hover": { color: "#fff" },
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
        {/* Branding panel — lightly frosted, stays closer to see-through */}
        <GlassCard
          frost="none"
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
            borderRight: { md: "1px solid rgba(255,255,255,0.14)" },
            borderBottom: { xs: "1px solid rgba(255,255,255,0.14)", md: "none" },
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
            <Typography variant="h6" sx={{ fontWeight: 700, color: "#fff", ...textShadowSx, textAlign: "center" }}>
              Secure File Transfer
            </Typography>
            <Typography variant="body2" sx={{ color: "rgba(255,255,255,0.7)", mt: 0.5, ...textShadowSx }}>
              Direct, auditable file exchange between financial institutions.
            </Typography>
          </Box>

          <Box sx={{ display: { xs: "none", md: "block" }, width: "100%" }}>
            <SecurityHighlights />
          </Box>
        </GlassCard>

        {/* Form panel — more heavily frosted, prioritizes input legibility */}
        <GlassCard
          frost="heavy"
          elevation={0}
          component="form"
          onSubmit={handleSubmit}
          sx={{ flex: 1, p: { xs: 3, md: 5 }, border: "none", borderRadius: 0, boxShadow: "none" }}
        >
          <Stack spacing={2.5}>
            <Box>
              <Typography variant="h6" sx={{ fontWeight: 700, color: "#fff", ...textShadowSx }}>
                Sign in
              </Typography>
              <Typography variant="body2" sx={{ color: "rgba(255,255,255,0.65)", ...textShadowSx }}>
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
                        sx={{ color: "rgba(255,255,255,0.6)" }}
                        aria-label={showPassword ? "Hide password" : "Show password"}
                      >
                        {showPassword ? <VisibilityOffIcon /> : <VisibilityIcon />}
                      </IconButton>
                    </InputAdornment>
                  ),
                },
              }}
            />

            <Button
              type="submit"
              variant="contained"
              size="large"
              disabled={submitting}
              fullWidth
              sx={{
                borderRadius: 2,
                py: 1.3,
                bgcolor: "#1F9E89",
                boxShadow: "0 4px 14px rgba(31, 158, 137, 0.35)",
                "&:hover": { bgcolor: "#188275" },
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
