import { Box } from "@mui/material";
import type { ReactNode } from "react";

export default function LoginBackground({ children }: { children: ReactNode }) {
  return (
    <Box
      sx={{
        minHeight: "100vh",
        position: "relative",
        overflow: "hidden",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        px: { xs: 1, sm: 3 },
        py: { xs: 2, sm: 3 },
        backgroundImage: "url(/skyline-background.jpg)",
        backgroundSize: "cover",
        backgroundPosition: "center",
        backgroundRepeat: "no-repeat",
      }}
    >
      <Box sx={{ position: "relative", width: "100%", display: "flex", justifyContent: "center" }}>
        {children}
      </Box>
    </Box>
  );
}
