import { useState } from "react";
import { useNavigate } from "react-router-dom";
import api, { errorMessage } from "../api";
import Layout from "../components/Layout";

export default function NewEventPage() {
  const [form, setForm] = useState({ name: "", venue: "", eventDate: "", rows: 5, seatsPerRow: 8, price: 499 });
  const [errors, setErrors] = useState({});
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();

  const set = (field) => (e) => setForm({ ...form, [field]: e.target.value });

  async function handleSubmit(e) {
    e.preventDefault();
    setError("");
    setErrors({});
    setBusy(true);
    try {
      const res = await api.post("/events", {
        ...form,
        rows: Number(form.rows),
        seatsPerRow: Number(form.seatsPerRow),
        price: Number(form.price),
      });
      navigate(`/events/${res.data.id}`);
    } catch (err) {
      setErrors(err.response?.data?.fields ?? {});
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const field = (name, label, props = {}) => (
    <div className="form-group">
      <label htmlFor={name}>{label}</label>
      <input id={name} value={form[name]} onChange={set(name)} required {...props} />
      {errors[name] && <p className="error-text">{errors[name]}</p>}
    </div>
  );

  const total = Number(form.rows) * Number(form.seatsPerRow) || 0;

  return (
    <Layout>
      <h2>New event</h2>
      <form className="card-form" onSubmit={handleSubmit}>
        {field("name", "Event name")}
        {field("venue", "Venue")}
        {field("eventDate", "Date and time", { type: "datetime-local" })}
        <div className="form-row">
          {field("rows", "Rows (A, B, C...)", { type: "number", min: 1, max: 26 })}
          {field("seatsPerRow", "Seats per row", { type: "number", min: 1, max: 30 })}
          {field("price", "Price (₹)", { type: "number", min: 0, step: "1" })}
        </div>
        <p className="hint">This creates {total} seats.</p>
        {error && !Object.keys(errors).length && <p className="error-text">{error}</p>}
        <button type="submit" className="btn-primary" disabled={busy}>{busy ? "Creating..." : "Create event"}</button>
      </form>
    </Layout>
  );
}
