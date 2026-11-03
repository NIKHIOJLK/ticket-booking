import axios from "axios";
import { clearSession } from "./auth";

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || "http://localhost:8080/api",
});

// Automatically attach the JWT token (if we have one) to every request.
api.interceptors.request.use((config) => {
  const token = localStorage.getItem("token");
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// 401 means the token is missing or expired: send the user back to the login page.
api.interceptors.response.use(
  (response) => response,
  (error) => {
    const isLogin = error.config?.url?.startsWith("/auth/");
    if (error.response?.status === 401 && !isLogin) {
      clearSession();
      window.location.assign("/?expired=1");
    }
    return Promise.reject(error);
  }
);

/** The server's explanation of what went wrong, or a fallback. */
export function errorMessage(error, fallback = "Something went wrong. Please try again.") {
  if (!error?.response) return "Can't reach the server. Is the backend running?";
  return error.response.data?.error || fallback;
}

export default api;
