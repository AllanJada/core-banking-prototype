import type { ReactNode } from "react";
import { Chip, List, ListItem, ListItemButton, ListItemIcon, ListItemText, Stack } from "@mui/material";

/**
 * One navigable section of a dashboard.
 *
 * `count` is the number of rows behind the section, shown as a chip the way the tab
 * labels used to carry it — so the sidebar still answers "is there anything in my inbox"
 * without opening it.
 */
export interface NavSection<T extends string> {
  value: T;
  label: string;
  icon: ReactNode;
  count?: number;
}

interface MenuContentProps<T extends string> {
  sections: NavSection<T>[];
  section: T;
  onSectionChange: (section: T) => void;
}

/** The sidebar's list of sections, shared by the permanent and the mobile drawer. */
export default function MenuContent<T extends string>({
  sections,
  section,
  onSectionChange,
}: MenuContentProps<T>) {
  return (
    <Stack sx={{ flexGrow: 1, p: 1 }}>
      <List dense disablePadding sx={{ display: "flex", flexDirection: "column", gap: 0.25 }}>
        {sections.map((item) => (
          <ListItem key={item.value} disablePadding sx={{ display: "block" }}>
            <ListItemButton
              selected={item.value === section}
              onClick={() => onSectionChange(item.value)}
            >
              <ListItemIcon>{item.icon}</ListItemIcon>
              <ListItemText primary={item.label} />
              {/* Only when there is something to count: a "0" chip on every empty
                  section is noise, and the empty state inside says it better. */}
              {item.count !== undefined && item.count > 0 && (
                <Chip label={item.count} size="small" color="default" />
              )}
            </ListItemButton>
          </ListItem>
        ))}
      </List>
    </Stack>
  );
}
