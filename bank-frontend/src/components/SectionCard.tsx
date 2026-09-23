import type { ReactNode } from "react";
import { Box, Card, Stack, Typography } from "@mui/material";

interface SectionCardProps {
  title?: string;
  description?: string;
  actions?: ReactNode;
  /** Set for tables, which need to meet the card's edges rather than sit inside padding. */
  disablePadding?: boolean;
  children: ReactNode;
}

/**
 * An outlined surface with an optional heading — the container every table and panel on a
 * dashboard sits in.
 *
 * Tables pass `disablePadding` so their rows run the full width of the card and the last
 * row's border lands on the card's own edge; panels of text and controls keep the padding.
 */
export default function SectionCard({
  title,
  description,
  actions,
  disablePadding,
  children,
}: SectionCardProps) {
  const hasHeading = Boolean(title || actions);

  return (
    <Card variant="outlined" sx={{ p: 0, overflow: "hidden" }}>
      {hasHeading && (
        <Stack
          direction="row"
          sx={{
            px: 2,
            py: 1.5,
            gap: 2,
            alignItems: "center",
            justifyContent: "space-between",
            borderBottom: "1px solid",
            borderColor: "divider",
          }}
        >
          <Box sx={{ minWidth: 0 }}>
            {title && (
              <Typography variant="subtitle2" component="h2" sx={{ fontWeight: 600 }}>
                {title}
              </Typography>
            )}
            {description && (
              <Typography variant="caption" color="text.secondary" sx={{ display: "block" }}>
                {description}
              </Typography>
            )}
          </Box>
          {actions && (
            <Stack direction="row" sx={{ gap: 1, flexShrink: 0 }}>
              {actions}
            </Stack>
          )}
        </Stack>
      )}
      <Box sx={disablePadding ? undefined : { p: 2 }}>{children}</Box>
    </Card>
  );
}
