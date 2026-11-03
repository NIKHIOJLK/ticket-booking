import { useNavigate } from "react-router-dom";
import Layout from "../components/Layout";
import { useFetch } from "../hooks";
import { dateTime, money } from "../format";

function Availability({ event }) {
  if (event.ended) return <span className="pill pill-muted">Ended</span>;
  if (event.soldOut) return <span className="pill pill-red">Sold out</span>;
  const few = event.availableSeats <= Math.max(3, event.totalSeats * 0.1);
  return (
    <span className={few ? "pill pill-amber" : "pill pill-green"}>
      {event.availableSeats} of {event.totalSeats} seats left
    </span>
  );
}

export default function EventsPage() {
  const { data: events, error, loading } = useFetch("/events", { intervalMs: 15000 });
  const navigate = useNavigate();

  return (
    <Layout>
      <h2>Upcoming Events</h2>
      {loading && <p>Loading events...</p>}
      {error && <p className="error-text">{error}</p>}
      {events && events.length === 0 && <p className="hint">No events yet.</p>}
      {events?.map((event) => (
        <button key={event.id} className="event-card" onClick={() => navigate(`/events/${event.id}`)}>
          <div className="event-card-main">
            <h3>{event.name}</h3>
            <p>{event.venue} · {dateTime(event.eventDate)}</p>
          </div>
          <div className="event-card-side">
            <span className="price">{money(event.price)}</span>
            <Availability event={event} />
          </div>
        </button>
      ))}
    </Layout>
  );
}
