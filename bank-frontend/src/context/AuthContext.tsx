import { createContext, useContext, useState, type ReactNode } from "react";
import { setAuthToken } from "../api/client";
import type { AuthResult, User } from "../types";

const STORAGE_KEY = "bank-demo-user";

interface StoredAuth {
  user: User;
  token: string;
}

interface AuthContextValue {
  user: User | null;
  login: (result: AuthResult) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

function readStored(): StoredAuth | null {
  const stored = localStorage.getItem(STORAGE_KEY);
  if (!stored) return null;
  try {
    return JSON.parse(stored) as StoredAuth;
  } catch {
    return null;
  }
}

// Set synchronously at module load (covers page load/refresh) rather than from a component
// effect — the api client needs the token in place before any component's own mount effect
// fires its first request (e.g. DashboardPage's inbox/outbox fetch), and effect ordering
// between an ancestor provider (AuthProvider) and a descendant page isn't something to rely
// on for that: React runs a descendant's effects before an ancestor's on the same commit, so
// a "persist token" effect here could still be pending when DashboardPage's fetch fires.
// login()/logout() below set it synchronously too, for the same reason.
const initialStored = readStored();
setAuthToken(initialStored?.token ?? null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(initialStored?.user ?? null);

  function login(result: AuthResult) {
    const { token, ...loggedInUser } = result;
    setAuthToken(token);
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ user: loggedInUser, token }));
    setUser(loggedInUser);
  }

  function logout() {
    setAuthToken(null);
    localStorage.removeItem(STORAGE_KEY);
    setUser(null);
  }

  return (
    <AuthContext.Provider value={{ user, login, logout }}>{children}</AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
}
