import { useState } from "react";
import { useNavigate, Link, Navigate, useSearchParams } from "react-router-dom";
import api, { errorMessage } from "../api";
import { getSession, saveSession } from "../auth";

export default function LoginPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [params] = useSearchParams();
  const navigate = useNavigate();

  if (getSession()) return <Navigate to="/events" replace />;

  async function handleLogin(e) {
    e.preventDefault();
    setError("");
    setBusy(true);
    try {
      const response = await api.post("/auth/login", { email, password });
      saveSession(response.data.token);
      navigate("/events");
    } catch (err) {
      setError(errorMessage(err, "Login failed — check your email and password."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-card">
      <div className="brand auth-brand">Ticket Booking</div>
      <h2>Log in</h2>
      {params.get("expired") && <p className="notice">Your session ended. Please log in again.</p>}
      <form onSubmit={handleLogin}>
        <div className="form-group">
          <label htmlFor="email">Email</label>
          <input id="email" type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </div>
        <div className="form-group">
          <label htmlFor="password">Password</label>
          <input
            id="password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </div>
        {error && <p className="error-text">{error}</p>}
        <button type="submit" className="btn-primary" disabled={busy}>{busy ? "Logging in..." : "Log in"}</button>
      </form>
      <p className="switch-link">
        Don't have an account? <Link to="/register">Register</Link>
      </p>
    </div>
  );
}
