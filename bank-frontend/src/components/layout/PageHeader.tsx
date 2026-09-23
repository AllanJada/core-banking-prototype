import type { ReactNode } from "react";
import { Box, Breadcrumbs, Stack, Typography, breadcrumbsClasses } from "@mui/material";
import NavigateNextRoundedIcon from "@mui/icons-material/NavigateNextRounded";

interface PageHeaderProps {
  /** The console this page belongs to, shown as the first, muted crumb. */
  context: string;
  title: string;
  description?: string;
  actions?: ReactNode;
}

/**
 * Breadcrumbs, a page title and the page's actions — the header from MUI's CRUD dashboard
 * template.
 *
 * The crumbs are not links: there is one level of navigation here and the sidebar already
 * owns it, so a crumb that navigated would just duplicate the list on the left. They exist
 * to say where you are, which matters more once the section name has moved off the top of
 * the screen and into the sidebar.
 */
export default function PageHeader({ context, title, description, actions }: PageHeaderProps) {
  return (
    <Stack spacing={1}>
      <Breadcrumbs
        aria-label="breadcrumb"
        separator={<NavigateNextRoundedIcon fontSize="small" />}
        sx={{
          [`& .${breadcrumbsClasses.separator}`]: { color: "action.disabled", mx: 0.5 },
          [`& .${breadcrumbsClasses.ol}`]: { alignItems: "center" },
        }}
      >
        <Typography variant="body2" color="text.secondary">
          {context}
        </Typography>
        <Typography variant="body2" sx={{ color: "text.primary", fontWeight: 600 }}>
          {title}
        </Typography>
      </Breadcrumbs>

      <Stack
        direction="row"
        sx={{ gap: 2, alignItems: "flex-start", justifyContent: "space-between", flexWrap: "wrap" }}
      >
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="h4" component="h1">
            {title}
          </Typography>
          {description && (
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, maxWidth: 820 }}>
              {description}
            </Typography>
          )}
        </Box>
        {actions && (
          <Stack direction="row" sx={{ gap: 1, flexWrap: "wrap", ml: "auto" }}>
            {actions}
          </Stack>
        )}
      </Stack>
    </Stack>
  );
}
