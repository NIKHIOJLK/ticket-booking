import { useState } from "react";
import { Link, useParams } from "react-router-dom";
import api, { errorMessage } from "../api";
import Layout from "../components/Layout";
import { useFetch, useNow } from "../hooks";
import { countdown, dateTime, money, saveBlob } from "../format";

const LABEL = { AVAILABLE: "Available", HELD: "Someone is paying for this seat", BOOKED: "Booked" };

function seatClass(seat) {
  if (seat.mine) return seat.status === "HELD" ? "seat-btn mine-held" : "seat-btn mine-booked";
  return `seat-btn ${seat.status.toLowerCase()}`;
}

export default function SeatPickerPage() {
  const { eventId } = useParams();
  const event = useFetch(`/events/${eventId}`, { intervalMs: 10000 });
  // Re-check every few seconds so seats held or released by other people show up.
  const seats = useFetch(`/events/${eventId}/seats`, { intervalMs: 4000 });
  const now = useNow();
  const [message, setMessage] = useState(null); // { kind: "ok" | "error", text, link? }
  const [busy, setBusy] = useState(null);

  const ev = event.data;
  const seatList = seats.data ?? [];
  const mySeats = seatList.filter((s) => s.mine);
  const rows = {};
  seatList.forEach((seat) => {
    const rowLetter = seat.seatLabel.match(/^[A-Z]+/)?.[0] ?? "?";
    (rows[rowLetter] ??= []).push(seat);
  });

  function refresh() {
    seats.reload();
    event.reload();
  }

  async function act(key, request, success) {
    setMessage(null);
    setBusy(key);
    try {
      const res = await request();
      if (success) setMessage({ kind: "ok", ...success(res.data) });
    } catch (err) {
      setMessage({ kind: "error", text: errorMessage(err) });
    } finally {
      setBusy(null);
      refresh();
    }
  }

  function handleHold(seat) {
    act(`hold-${seat.id}`, () => api.post("/bookings", { seatId: seat.id }), (b) => ({
      text: `Seat ${b.seatLabel} is yours for the next few minutes. Pay below to confirm it.`,
    }));
  }

  function handleConfirm(seat) {
    act(`pay-${seat.id}`, () => api.post(`/bookings/${seat.bookingId}/confirm`), (b) => ({
      text: `Booked seat ${b.seatLabel}! Your ticket ${b.reference} is on its way to your email.`,
      link: true,
    }));
  }

  function handleRelease(seat) {
    act(`release-${seat.id}`, () => api.post(`/bookings/${seat.bookingId}/release`), (b) => ({
      text: `Released seat ${b.seatLabel}.`,
    }));
  }

  async function handleTicket(seat) {
    try {
      const res = await api.get(`/bookings/${seat.bookingId}/ticket.pdf`, { responseType: "blob" });
      saveBlob(res.data, `ticket-${seat.seatLabel}.pdf`);
    } catch (err) {
      setMessage({ kind: "error", text: errorMessage(err) });
    }
  }

  function handleWaitlist(join) {
    act("waitlist", () => (join ? api.post : api.delete)(`/events/${eventId}/waitlist`), () => ({
      text: join ? "You're on the waitlist. We'll email you if a seat frees up." : "You left the waitlist.",
    }));
  }

  return (
    <Layout wide>
      <div className="top-bar">
        <Link to="/events" className="back-link">← All events</Link>
      </div>

      {ev && (
        <section className="event-header">
          <div>
            <h2>{ev.name}</h2>
            <p>{ev.venue} · {dateTime(ev.eventDate)}</p>
          </div>
          <div className="event-header-side">
            <span className="price">{money(ev.price)}</span>
            <span className="hint">
              {ev.ended ? "This event has ended" : ev.soldOut ? "Sold out" : `${ev.availableSeats} of ${ev.totalSeats} seats left`}
            </span>
          </div>
        </section>
      )}
      {(event.error || seats.error) && <p className="error-text">{event.error || seats.error}</p>}

      {message && (
        <p className={message.kind === "ok" ? "banner banner-ok" : "banner banner-error"} role="status">
          {message.text} {message.link && <Link to="/bookings">See My bookings</Link>}
        </p>
      )}

      <div className="seat-layout">
        <section className="seat-map" aria-label="Seat map">
          <div className="stage">Stage</div>
          {seats.loading && <p>Loading seats...</p>}
          {Object.keys(rows).sort().map((rowLetter) => (
            <div key={rowLetter} className="seat-row">
              <span className="row-label">{rowLetter}</span>
              {rows[rowLetter].map((seat) => (
                <button
                  key={seat.id}
                  disabled={seat.status !== "AVAILABLE" || ev?.ended || busy !== null}
                  onClick={() => handleHold(seat)}
                  className={seatClass(seat)}
                  title={seat.mine ? (seat.status === "HELD" ? "Held for you" : "Your seat") : LABEL[seat.status]}
                  aria-label={`Seat ${seat.seatLabel}, ${seat.mine ? "yours" : LABEL[seat.status]}`}
                >
                  {seat.seatLabel}
                </button>
              ))}
            </div>
          ))}
          <div className="legend">
            <span><i className="swatch available" /> Available</span>
            <span><i className="swatch held" /> Being paid for</span>
            <span><i className="swatch booked" /> Booked</span>
            <span><i className="swatch mine-booked" /> Yours</span>
          </div>
        </section>

        <aside className="side-panel">
          <h3>Your seats</h3>
          {mySeats.length === 0 && <p className="hint">Pick a green seat to hold it while you pay.</p>}
          {mySeats.map((seat) =>
            seat.status === "HELD" ? (
              <div key={seat.id} className="my-seat held">
                <div className="my-seat-head">
                  <strong>Seat {seat.seatLabel}</strong>
                  <span className="timer" title="Time left to pay">
                    {countdown(new Date(seat.holdExpiresAt).getTime() - now)}
                  </span>
                </div>
                <p className="hint">Held for you. Pay before the timer runs out or the seat goes back on sale.</p>
                <button className="btn-primary" disabled={busy !== null} onClick={() => handleConfirm(seat)}>
                  {busy === `pay-${seat.id}` ? "Paying..." : `Pay ${money(ev?.price)} & confirm`}
                </button>
                <button className="btn-link" disabled={busy !== null} onClick={() => handleRelease(seat)}>
                  Release seat
                </button>
                <p className="demo-note">Demo payment: no money is taken.</p>
              </div>
            ) : (
              <div key={seat.id} className="my-seat booked">
                <div className="my-seat-head">
                  <strong>Seat {seat.seatLabel}</strong>
                  <span className="pill pill-green">Booked</span>
                </div>
                <button className="btn-secondary" onClick={() => handleTicket(seat)}>Download ticket</button>
              </div>
            )
          )}

          {ev?.soldOut && !ev.ended && (
            <div className="waitlist">
              <h3>Sold out</h3>
              {ev.onWaitlist ? (
                <>
                  <p>You're on the waitlist. If a seat frees up, we'll email you.</p>
                  <button className="btn-link" disabled={busy !== null} onClick={() => handleWaitlist(false)}>Leave waitlist</button>
                </>
              ) : (
                <>
                  <p className="hint">Join the waitlist and we'll email you when a seat frees up.</p>
                  <button className="btn-primary" disabled={busy !== null} onClick={() => handleWaitlist(true)}>Join waitlist</button>
                </>
              )}
            </div>
          )}
        </aside>
      </div>
    </Layout>
  );
}
