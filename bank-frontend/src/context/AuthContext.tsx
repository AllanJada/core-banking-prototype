import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { getCurrentUser } from "../api/client";
import { clearSession, loadSession, saveSession } from "../api/session";
import type { AuthSession, User } from "../types";

interface AuthContextValue {
  user: User | null;
  /** False until the stored token has been re-checked against the backend on startup. */
  initializing: boolean;
  login: (session: AuthSession) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(() => loadSession()?.user ?? null);
  const [initializing, setInitializing] = useState(true);

  // A token restored from storage may have expired while the tab was closed. Ask the
  // backend once on startup rather than trusting what's in localStorage: it's the only
  // way to tell a still-valid session from a stale one, and it means an expired token
  // signs the user out at the login screen instead of failing mid-dashboard.
  useEffect(() => {
    if (!loadSession()) {
      setInitializing(false);
      return;
    }

    let cancelled = false;
    getCurrentUser()
      .then((currentUser) => {
        if (!cancelled) setUser(currentUser);
      })
      .catch(() => {
        if (cancelled) return;
        clearSession();
        setUser(null);
      })
      .finally(() => {
        if (!cancelled) setInitializing(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  const login = (session: AuthSession) => {
    saveSession(session);
    setUser(session.user);
  };

  const logout = () => {
    clearSession();
    setUser(null);
  };

  return (
    <AuthContext.Provider value={{ user, initializing, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
}
