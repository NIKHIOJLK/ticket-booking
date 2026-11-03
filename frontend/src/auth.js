// The logged-in user lives in the JWT stored in localStorage. Its middle part is
// readable JSON: { sub: email, userId, role, exp }.

const KEY = "token";

function decode(token) {
  try {
    const part = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    const json = decodeURIComponent(
      atob(part.padEnd(part.length + ((4 - (part.length % 4)) % 4), "="))
        .split("")
        .map((c) => "%" + c.charCodeAt(0).toString(16).padStart(2, "0"))
        .join("")
    );
    return JSON.parse(json);
  } catch {
    return null;
  }
}

/** { token, email, userId, role, isAdmin } or null when logged out or the token expired. */
export function getSession() {
  const token = localStorage.getItem(KEY);
  if (!token) return null;
  const claims = decode(token);
  if (!claims || (claims.exp && claims.exp * 1000 < Date.now())) {
    localStorage.removeItem(KEY);
    return null;
  }
  const role = claims.role || "USER";
  return { token, email: claims.sub, userId: claims.userId, role, isAdmin: role === "ADMIN" };
}

export function saveSession(token) {
  localStorage.setItem(KEY, token);
}

export function clearSession() {
  localStorage.removeItem(KEY);
}
