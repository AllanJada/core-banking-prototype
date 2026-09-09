import { Box, Button, Typography } from "@mui/material";
import ChevronLeftIcon from "@mui/icons-material/ChevronLeft";
import ChevronRightIcon from "@mui/icons-material/ChevronRight";

interface PagerProps {
  page: number;
  totalPages: number;
  totalElements: number;
  hasNext: boolean;
  loading?: boolean;
  onPageChange: (page: number) => void;
}

/**
 * Previous/next controls with a position indicator.
 *
 * Deliberately not numbered page links: the tables here are read in order and the useful
 * question is "is there more", not "take me to page seven". It also means the control does
 * not need to know the total page count to render, which keeps it honest when a list is
 * growing while being read.
 *
 * Renders nothing when everything fits on one page — a pager under a three-row table is
 * clutter that tells the reader nothing.
 */
export default function Pager({
  page,
  totalPages,
  totalElements,
  hasNext,
  loading,
  onPageChange,
}: PagerProps) {
  if (totalPages <= 1) return null;

  return (
    <Box
      sx={{
        display: "flex",
        alignItems: "center",
        justifyContent: "flex-end",
        gap: 1.5,
        px: 2,
        py: 1.5,
      }}
    >
      <Typography variant="body2" color="text.secondary">
        Page {page + 1} of {totalPages} · {totalElements} in total
      </Typography>
      <Button
        size="small"
        startIcon={<ChevronLeftIcon />}
        disabled={page === 0 || loading}
        onClick={() => onPageChange(page - 1)}
      >
        Previous
      </Button>
      <Button
        size="small"
        endIcon={<ChevronRightIcon />}
        disabled={!hasNext || loading}
        onClick={() => onPageChange(page + 1)}
      >
        Next
      </Button>
    </Box>
  );
}
