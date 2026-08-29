import { Navigate, Route, Routes } from "react-router-dom";
import LoginPage from "./pages/LoginPage";
import BankLoginPage from "./pages/BankLoginPage";
import DashboardPage from "./pages/DashboardPage";
import BankDashboardPage from "./pages/BankDashboardPage";
import ProtectedRoute from "./components/ProtectedRoute";

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/bank-login" element={<BankLoginPage />} />
      <Route
        path="/dashboard"
        element={
          <ProtectedRoute requiredType="INSTITUTION" redirectTo="/login">
            <DashboardPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/bank-dashboard"
        element={
          <ProtectedRoute requiredType="BANK" redirectTo="/bank-login">
            <BankDashboardPage />
          </ProtectedRoute>
        }
      />
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
}
