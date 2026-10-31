import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import api from "../api";

export default function EventsPage() {
  const [events, setEvents] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const navigate = useNavigate();

  useEffect(() => {
    async function loadEvents() {
      try {
        const response = await api.get("/events");
        setEvents(response.data);
      } catch (err) {
        setError("Could not load events.");
      } finally {
        setLoading(false);
      }
    }
    loadEvents();
  }, []);

  function handleLogout() {
    localStorage.removeItem("token");
    navigate("/");
  }

  return (
    <div className="page">
      <div className="top-bar">
        <h2>Upcoming Events</h2>
        <button className="logout-btn" onClick={handleLogout}>Log out</button>
      </div>
      {loading && <p>Loading events...</p>}
      {error && <p className="error-text">{error}</p>}
      {events.map((event) => (
        <div key={event.id} className="event-card" onClick={() => navigate(`/events/${event.id}`)}>
          <h3>{event.name}</h3>
          <p>{event.venue} · {new Date(event.eventDate).toLocaleString()}</p>
        </div>
      ))}
    </div>
  );
}