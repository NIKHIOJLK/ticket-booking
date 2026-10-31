import { useEffect, useState } from "react";
import { useParams, useNavigate } from "react-router-dom";
import api from "../api";

export default function SeatPickerPage() {
  const { eventId } = useParams();
  const [seats, setSeats] = useState([]);
  const [message, setMessage] = useState("");
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  async function loadSeats() {
    const response = await api.get(`/events/${eventId}/seats`);
    setSeats(response.data);
    setLoading(false);
  }

  useEffect(() => {
    loadSeats();
  }, [eventId]);

  async function handleBook(seatId, seatLabel) {
    setMessage("");
    try {
      await api.post("/bookings", { seatId });
      setMessage(`✅ Booked seat ${seatLabel}!`);
      loadSeats();
    } catch (err) {
      if (err.response?.status === 409) {
        setMessage(`❌ Seat ${seatLabel} is already booked.`);
      } else if (err.response?.status === 403) {
        setMessage("❌ You need to be logged in to book a seat.");
      } else {
        setMessage("❌ Something went wrong.");
      }
      loadSeats();
    }
  }

  const rows = {};
  seats.forEach((seat) => {
    const rowLetter = seat.seatLabel[0];
    if (!rows[rowLetter]) rows[rowLetter] = [];
    rows[rowLetter].push(seat);
  });

  return (
    <div className="page">
      <div className="top-bar">
        <h2>Pick your seat</h2>
        <button className="logout-btn" onClick={() => navigate("/events")}>← Back</button>
      </div>
      {loading && <p>Loading seats...</p>}
      {message && <p>{message}</p>}
      {Object.keys(rows).sort().map((rowLetter) => (
        <div key={rowLetter} className="seat-row">
          {rows[rowLetter].map((seat) => (
            <button
              key={seat.id}
              disabled={seat.booked}
              onClick={() => handleBook(seat.id, seat.seatLabel)}
              className={`seat-btn ${seat.booked ? "booked" : "available"}`}
            >
              {seat.seatLabel}
            </button>
          ))}
        </div>
      ))}
    </div>
  );
}