import type { ReactNode } from "react";
import { Navigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext";
import type { UserType } from "../types";

interface ProtectedRouteProps {
  children: ReactNode;
  // Which account type may view this route — INSTITUTION for the existing
  // file-transfer dashboard, BANK for the bank_dashboard one. An unauthenticated
  // user, or one signed in as the wrong type, is sent to redirectTo rather than
  // shown the page (e.g. a BANK account hitting /dashboard bounces to /bank-login,
  // not /login, since that's the sign-in page that actually applies to them).
  requiredType: UserType;
  redirectTo: string;
}

export default function ProtectedRoute({ children, requiredType, redirectTo }: ProtectedRouteProps) {
  const { user } = useAuth();
  if (!user || user.userType !== requiredType) {
    return <Navigate to={redirectTo} replace />;
  }
  return <>{children}</>;
}
