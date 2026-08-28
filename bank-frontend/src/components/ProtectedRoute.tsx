import type { ReactNode } from "react";
import { Navigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import type { UserType } from "../types";

interface ProtectedRouteProps {
  children: ReactNode;
  requiredType: UserType;
  redirectTo: string;
}

/**
 * Guards a route both against being logged out AND against being the wrong
 * account type — a BANK account hitting /dashboard by URL, or an INSTITUTION
 * account hitting /bank-dashboard, gets sent to the login page appropriate
 * for what they actually are, not just bounced to a generic "please log in".
 */
export default function ProtectedRoute({ children, requiredType, redirectTo }: ProtectedRouteProps) {
  const { user } = useAuth();
  if (!user || user.userType !== requiredType) {
    return <Navigate to={redirectTo} replace />;
  }
  return <>{children}</>;
}
