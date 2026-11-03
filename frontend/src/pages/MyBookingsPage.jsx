import { useState } from "react";
import { Link } from "react-router-dom";
import api, { errorMessage } from "../api";
import Layout from "../components/Layout";
import { useFetch, useNow } from "../hooks";
import { countdown, dateTime, money, saveBlob } from "../format";

function StatusPill({ booking, now }) {
  if (booking.status === "CONFIRMED" && booking.checkedInAt) return <span className="pill pill-muted">Used at entry</span>;
  if (booking.status === "CONFIRMED") return <span className="pill pill-green">Confirmed</span>;
  if (booking.status === "HELD") {
    const left = new Date(booking.holdExpiresAt).getTime() - now;
    return <span className="pill pill-amber">Awaiting payment · {countdown(left)}</span>;
  }
  if (booking.status === "EXPIRED") return <span className="pill pill-muted">Hold expired</span>;
  return <span className="pill pill-red">Cancelled</span>;
}

function BookingCard({ booking, now, onChanged, onError }) {
  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [busy, setBusy] = useState(false);

  async function run(request) {
    setBusy(true);
    try {
      await request();
      onChanged();
    } catch (err) {
      onError(errorMessage(err));
    } finally {
      setBusy(false);
      setConfirmingCancel(false);
    }
  }

  async function download() {
    try {
      const res = await api.get(`/bookings/${booking.id}/ticket.pdf`, { responseType: "blob" });
      saveBlob(res.data, `ticket-${booking.reference}.pdf`);
    } catch (err) {
      onError(errorMessage(err));
    }
  }

  const held = booking.status === "HELD" && new Date(booking.holdExpiresAt).getTime() > now;

  return (
    <article className="booking-card">
      <div className="booking-main">
        <div className="booking-title">
          <Link to={`/events/${booking.eventId}`}>{booking.eventName}</Link>
          <StatusPill booking={booking} now={now} />
        </div>
        <p>{dateTime(booking.eventDate)} · {booking.venue}</p>
        <p className="booking-meta">
          Seat <strong>{booking.seatLabel}</strong> · {money(booking.price)} · <span className="mono">{booking.reference}</span>
        </p>
      </div>
      <div className="booking-actions">
        {booking.status === "CONFIRMED" && (
          <button className="btn-secondary" onClick={download}>Download ticket</button>
        )}
        {held && (
          <>
            <button className="btn-primary" disabled={busy} onClick={() => run(() => api.post(`/bookings/${booking.id}/confirm`))}>
              Pay & confirm
            </button>
            <button className="btn-link" disabled={busy} onClick={() => run(() => api.post(`/bookings/${booking.id}/release`))}>
              Release
            </button>
          </>
        )}
        {booking.cancellable && !confirmingCancel && (
          <button className="btn-link danger" onClick={() => setConfirmingCancel(true)}>Cancel booking</button>
        )}
        {confirmingCancel && (
          <div className="confirm-row">
            <span>Cancel this booking?</span>
            <button className="btn-danger" disabled={busy} onClick={() => run(() => api.post(`/bookings/${booking.id}/cancel`))}>
              Yes, cancel
            </button>
            <button className="btn-link" onClick={() => setConfirmingCancel(false)}>Keep it</button>
          </div>
        )}
        {booking.status === "CONFIRMED" && !booking.cancellable && !booking.checkedInAt && (
          <span className="hint">Can't be cancelled within 2 hours of the show.</span>
        )}
      </div>
    </article>
  );
}

export default function MyBookingsPage() {
  // Refresh now and then: background tasks change bookings too (e.g. a hold expiring).
  const { data, error, loading, reload } = useFetch("/bookings/me", { intervalMs: 10000 });
  const [actionError, setActionError] = useState("");
  const now = useNow();

  const bookings = data ?? [];
  const isUpcoming = (b) =>
    new Date(b.eventDate).getTime() > now && (b.status === "CONFIRMED" || b.status === "HELD");
  const upcoming = bookings.filter(isUpcoming).sort((a, b) => new Date(a.eventDate) - new Date(b.eventDate));
  const past = bookings.filter((b) => !isUpcoming(b));

  const card = (b) => (
    <BookingCard key={b.id} booking={b} now={now} onChanged={() => { setActionError(""); reload(); }} onError={setActionError} />
  );

  return (
    <Layout wide>
      <h2>My bookings</h2>
      {loading && <p>Loading...</p>}
      {(error || actionError) && <p className="banner banner-error">{actionError || error}</p>}
      {!loading && bookings.length === 0 && (
        <p className="hint">No bookings yet. <Link to="/events">Find an event</Link>.</p>
      )}
      {upcoming.length > 0 && (
        <section>
          <h3 className="section-title">Upcoming</h3>
          {upcoming.map(card)}
        </section>
      )}
      {past.length > 0 && (
        <section>
          <h3 className="section-title">Past and cancelled</h3>
          {past.map(card)}
        </section>
      )}
    </Layout>
  );
}
