import { useState } from "react";
import { useNavigate, Link } from "react-router-dom";
import api, { errorMessage } from "../api";

export default function RegisterPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [success, setSuccess] = useState(false);
  const navigate = useNavigate();

  async function handleRegister(e) {
    e.preventDefault();
    setError("");
    try {
      await api.post("/auth/register", { email, password });
      setSuccess(true);
      setTimeout(() => navigate("/"), 1200);
    } catch (err) {
      setError(errorMessage(err, "Registration failed. Please try again."));
    }
  }

  return (
    <div className="auth-card">
      <div className="brand auth-brand">Ticket Booking</div>
      <h2>Create an account</h2>
      {success ? (
        <p className="success-text">✅ Account created! Redirecting to login...</p>
      ) : (
        <form onSubmit={handleRegister}>
          <div className="form-group">
            <label htmlFor="email">Email</label>
            <input id="email" type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
          </div>
          <div className="form-group">
            <label htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              autoComplete="new-password"
              minLength={8}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
            <p className="hint">At least 8 characters.</p>
          </div>
          {error && <p className="error-text">{error}</p>}
          <button type="submit" className="btn-primary">Register</button>
        </form>
      )}
      <p className="switch-link">
        Already have an account? <Link to="/">Log in</Link>
      </p>
    </div>
  );
}
