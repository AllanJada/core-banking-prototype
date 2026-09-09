import type { Role } from "./types";

/**
 * Where each role lands after signing in.
 *
 * There is one login screen for all three roles, so this mapping is what replaced the
 * old arrangement of a separate page per account type. Keeping it in one place means
 * the login screen and the router agree by construction about where a role belongs.
 *
 * Note this only decides what the UI shows. The API authorizes every request against
 * the role inside the signed token, so reaching another role's URL yields an empty,
 * erroring page rather than access to its data.
 */
export const DASHBOARD_PATH_BY_ROLE: Record<Role, string> = {
  NORMAL_USER: "/account",
  INSTITUTION: "/dashboard",
  BANK: "/bank-dashboard",
};

export function dashboardPathFor(role: Role): string {
  return DASHBOARD_PATH_BY_ROLE[role];
}
