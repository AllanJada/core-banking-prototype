import { Navigate, Route, Routes } from "react-router-dom";
import LoginPage from "./pages/LoginPage";
import DashboardPage from "./pages/DashboardPage";
import BankDashboardPage from "./pages/BankDashboardPage";
import AccountPage from "./pages/AccountPage";
import PayLinkPage from "./pages/PayLinkPage";
import ProtectedRoute from "./components/ProtectedRoute";
import { useAuth } from "./context/AuthContext";
import { dashboardPathFor } from "./routes";

/**
 * Sends whoever lands on an unknown path to the right place for who they are —
 * their own dashboard when signed in, the single login screen when not.
 */
function HomeRedirect() {
  const { user, initializing } = useAuth();
  if (initializing) return null;
  return <Navigate to={user ? dashboardPathFor(user.role) : "/login"} replace />;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/account"
        element={
          <ProtectedRoute allowedRoles={["NORMAL_USER"]}>
            <AccountPage />
          </ProtectedRoute>
        }
      />
      {/* A shared payment link. Guarded like everything else, but a signed-out visitor
          following one is returned here after logging in rather than losing it. */}
      <Route
        path="/pay/:linkId"
        element={
          <ProtectedRoute allowedRoles={["NORMAL_USER"]}>
            <PayLinkPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/dashboard"
        element={
          <ProtectedRoute allowedRoles={["INSTITUTION"]}>
            <DashboardPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/bank-dashboard"
        element={
          <ProtectedRoute allowedRoles={["BANK"]}>
            <BankDashboardPage />
          </ProtectedRoute>
        }
      />
      <Route path="*" element={<HomeRedirect />} />
    </Routes>
  );
}
