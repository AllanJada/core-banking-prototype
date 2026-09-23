import { useState, type ReactNode } from "react";
import {
  AppBar,
  Box,
  Divider,
  Drawer,
  IconButton,
  Stack,
  Toolbar,
  Typography,
  drawerClasses,
} from "@mui/material";
import MenuRoundedIcon from "@mui/icons-material/MenuRounded";
import MenuContent, { type NavSection } from "./MenuContent";
import SidebarBrand from "./SidebarBrand";
import SidebarFooter from "./SidebarFooter";

const DRAWER_WIDTH = 248;

interface DashboardLayoutProps<T extends string> {
  brand: string;
  brandDetail?: string;
  sections: NavSection<T>[];
  section: T;
  onSectionChange: (section: T) => void;
  username: string;
  roleLabel: string;
  onLogout: () => void;
  children: ReactNode;
}

/**
 * The shell every signed-in screen sits in: a permanent sidebar on desktop, a collapsing
 * one behind a menu button on mobile, and a scrolling main region.
 *
 * This is the layout MUI's dashboard template uses, and it replaces the previous
 * arrangement of a coloured top app bar over a scrollable tab strip. The sections are the
 * same sections the tabs addressed — nothing became reachable or unreachable by moving
 * them — but navigation no longer competes for horizontal room with the content, which is
 * what forced the tabs to scroll on narrow screens.
 */
export default function DashboardLayout<T extends string>({
  brand,
  brandDetail,
  sections,
  section,
  onSectionChange,
  username,
  roleLabel,
  onLogout,
  children,
}: DashboardLayoutProps<T>) {
  const [mobileOpen, setMobileOpen] = useState(false);

  const nav = (
    <MenuContent
      sections={sections}
      section={section}
      onSectionChange={(next) => {
        onSectionChange(next);
        // Choosing a section on a phone should reveal it, not leave the drawer covering it.
        setMobileOpen(false);
      }}
    />
  );

  const sidebar = (
    <>
      <SidebarBrand brand={brand} detail={brandDetail} />
      <Divider />
      <Box sx={{ overflow: "auto", flexGrow: 1 }}>{nav}</Box>
      <SidebarFooter username={username} roleLabel={roleLabel} onLogout={onLogout} />
    </>
  );

  return (
    <Box sx={{ display: "flex", minHeight: "100vh", bgcolor: "background.default" }}>
      {/* Desktop: always present, and part of the layout rather than over it. */}
      <Drawer
        variant="permanent"
        sx={{
          display: { xs: "none", md: "block" },
          width: DRAWER_WIDTH,
          flexShrink: 0,
          [`& .${drawerClasses.paper}`]: {
            width: DRAWER_WIDTH,
            boxSizing: "border-box",
            backgroundColor: "background.paper",
            borderRight: "1px solid",
            borderColor: "divider",
          },
        }}
      >
        {sidebar}
      </Drawer>

      {/* Mobile: the same sidebar, temporarily. */}
      <Drawer
        variant="temporary"
        open={mobileOpen}
        onClose={() => setMobileOpen(false)}
        ModalProps={{ keepMounted: true }}
        sx={{
          display: { xs: "block", md: "none" },
          [`& .${drawerClasses.paper}`]: {
            width: DRAWER_WIDTH,
            boxSizing: "border-box",
            display: "flex",
            flexDirection: "column",
          },
        }}
      >
        {sidebar}
      </Drawer>

      <AppBar
        position="fixed"
        elevation={0}
        sx={{
          display: { xs: "block", md: "none" },
          bgcolor: "background.paper",
          color: "text.primary",
          borderBottom: "1px solid",
          borderColor: "divider",
        }}
      >
        <Toolbar sx={{ gap: 1.5 }}>
          <IconButton edge="start" aria-label="Open navigation" onClick={() => setMobileOpen(true)}>
            <MenuRoundedIcon />
          </IconButton>
          <Typography variant="subtitle2" sx={{ fontWeight: 600 }} noWrap>
            {brand}
            {brandDetail ? ` · ${brandDetail}` : ""}
          </Typography>
        </Toolbar>
      </AppBar>

      <Box
        component="main"
        sx={{
          flexGrow: 1,
          minWidth: 0,
          overflow: "auto",
          bgcolor: "background.default",
        }}
      >
        <Stack
          spacing={2.5}
          sx={{
            // Clears the fixed app bar on mobile; on desktop there is no bar to clear.
            mt: { xs: 8, md: 0 },
            px: { xs: 2, md: 3 },
            pt: { xs: 2, md: 2.5 },
            pb: 5,
            maxWidth: 1600,
            mx: "auto",
          }}
        >
          {children}
        </Stack>
      </Box>
    </Box>
  );
}
