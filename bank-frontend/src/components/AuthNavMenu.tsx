import { useState } from "react";
import type { MouseEvent } from "react";
import { IconButton, Menu, MenuItem, ListItemIcon, ListItemText } from "@mui/material";
import MenuIcon from "@mui/icons-material/MenuOutlined";
import AccountBalanceOutlinedIcon from "@mui/icons-material/AccountBalanceOutlined";
import { useNavigate } from "react-router-dom";

interface AuthNavMenuProps {
  targetLabel: string;
  targetPath: string;
}

/**
 * Small hamburger menu offering a single alternate destination — used to move
 * between the institution login and the bank login without either one needing
 * to know much about the other beyond a label and a path.
 */
export default function AuthNavMenu({ targetLabel, targetPath }: AuthNavMenuProps) {
  const [anchorEl, setAnchorEl] = useState<null | HTMLElement>(null);
  const navigate = useNavigate();
  const open = Boolean(anchorEl);

  function handleOpen(event: MouseEvent<HTMLElement>) {
    setAnchorEl(event.currentTarget);
  }

  function handleClose() {
    setAnchorEl(null);
  }

  function handleNavigate() {
    handleClose();
    navigate(targetPath);
  }

  return (
    <>
      <IconButton
        onClick={handleOpen}
        aria-label="Open navigation menu"
        sx={{
          position: "fixed",
          top: 16,
          left: 16,
          color: "#fff",
          background: "rgba(255,255,255,0.1)",
          border: "1px solid rgba(255,255,255,0.2)",
          "&:hover": { background: "rgba(255,255,255,0.18)" },
        }}
      >
        <MenuIcon />
      </IconButton>
      <Menu anchorEl={anchorEl} open={open} onClose={handleClose}>
        <MenuItem onClick={handleNavigate}>
          <ListItemIcon>
            <AccountBalanceOutlinedIcon fontSize="small" />
          </ListItemIcon>
          <ListItemText>{targetLabel}</ListItemText>
        </MenuItem>
      </Menu>
    </>
  );
}
