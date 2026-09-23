import type { ReactNode } from "react";
import { Card, CardContent, Stack, Typography } from "@mui/material";

interface StatCardProps {
  label: string;
  value: string;
  hint?: string;
  /** A status chip sitting beside the figure — balanced/not balanced, and the like. */
  badge?: ReactNode;
  /** Draws the figure in a status colour. Used where the sign carries meaning. */
  tone?: "default" | "success" | "error";
}

/**
 * One figure on an overview, in the shape of MUI's dashboard-template StatCard: a muted
 * label, a large value, and a caption underneath saying where the number came from.
 *
 * The template's version carries a sparkline. There is no trend series behind any of
 * these figures — they are current positions read from the ledger, not a time series —
 * so the chart is left out rather than filled with something invented.
 *
 * Previously this was defined twice, once in each dashboard, with the two copies already
 * drifting apart.
 */
export default function StatCard({ label, value, hint, badge, tone = "default" }: StatCardProps) {
  const valueColor =
    tone === "success" ? "success.dark" : tone === "error" ? "error.main" : "text.primary";

  return (
    <Card variant="outlined" sx={{ height: "100%" }}>
      <CardContent>
        <Stack spacing={0.75}>
          <Typography variant="subtitle2" color="text.secondary" component="h3">
            {label}
          </Typography>
          <Stack direction="row" sx={{ gap: 1, alignItems: "center", flexWrap: "wrap" }}>
            <Typography variant="h4" component="p" sx={{ color: valueColor, lineHeight: 1.2 }}>
              {value}
            </Typography>
            {badge}
          </Stack>
          {hint && (
            <Typography variant="caption" color="text.secondary">
              {hint}
            </Typography>
          )}
        </Stack>
      </CardContent>
    </Card>
  );
}
