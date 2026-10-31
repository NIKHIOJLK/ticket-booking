import { useState } from "react";
import { useNavigate, Link } from "react-router-dom";
import api from "../api";

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
      setError("Registration failed — that email might already be taken.");
    }
  }

  return (
    <div className="auth-card">
      <h2>Create an account</h2>
      {success ? (
        <p className="success-text">✅ Account created! Redirecting to login...</p>
      ) : (
        <form onSubmit={handleRegister}>
          <div className="form-group">
            <label>Email</label>
            <input value={email} onChange={(e) => setEmail(e.target.value)} required />
          </div>
          <div className="form-group">
            <label>Password</label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
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