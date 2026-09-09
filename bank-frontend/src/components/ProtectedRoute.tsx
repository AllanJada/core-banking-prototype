import type { ReactNode } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import { dashboardPathFor } from "../routes";
import type { Role } from "../types";

interface ProtectedRouteProps {
  children: ReactNode;
  allowedRoles: Role[];
}

/**
 * Guards a route against being signed out, and against being the wrong role for it.
 *
 * A signed-in user who lands on a route meant for another role is sent to their own
 * dashboard rather than to the login screen — bouncing them to a login form they're
 * already past would look like the session broke. Only genuinely signed-out visitors
 * go to /login.
 */
export default function ProtectedRoute({ children, allowedRoles }: ProtectedRouteProps) {
  const { user, initializing } = useAuth();
  const location = useLocation();

  // Wait for the stored token to be validated before deciding — rendering this too
  // early would redirect a returning user to /login for a moment on every refresh.
  if (initializing) {
    return null;
  }

  if (!user) {
    // Remember where they were going so signing in resumes it. This matters for shared
    // payment links above all: someone opening one while signed out would otherwise land
    // on their own dashboard afterwards, with the link they followed lost.
    return (
      <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
    );
  }

  if (!allowedRoles.includes(user.role)) {
    return <Navigate to={dashboardPathFor(user.role)} replace />;
  }

  return <>{children}</>;
}
