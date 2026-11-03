import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";
import api, { errorMessage } from "../api";
import Layout from "../components/Layout";
import { dateTime } from "../format";

/**
 * For door staff (admins). The QR code on each ticket opens this page with ?ref=...,
 * or the reference can be typed in. A ticket can only be checked in once.
 */
export default function CheckInPage() {
  const [params, setParams] = useSearchParams();
  const [reference, setReference] = useState(params.get("ref") ?? "");
  const [ticket, setTicket] = useState(null);
  const [result, setResult] = useState(null); // { kind: "ok" | "error", text }

  const refFromUrl = params.get("ref");
  useEffect(() => {
    if (!refFromUrl) return;
    let cancelled = false;
    api.get(`/checkin/${encodeURIComponent(refFromUrl)}`).then(
      (res) => !cancelled && setTicket(res.data),
      (err) => !cancelled && setResult({ kind: "error", text: errorMessage(err) })
    );
    return () => {
      cancelled = true;
    };
  }, [refFromUrl]);

  async function lookUp(e) {
    e.preventDefault();
    setResult(null);
    setTicket(null);
    const ref = reference.trim();
    if (!ref) return;
    setParams({ ref }, { replace: true });
    try {
      const res = await api.get(`/checkin/${encodeURIComponent(ref)}`);
      setTicket(res.data);
    } catch (err) {
      setResult({ kind: "error", text: errorMessage(err) });
    }
  }

  async function checkIn() {
    try {
      const res = await api.post(`/checkin/${encodeURIComponent(ticket.reference)}`);
      setTicket(res.data);
      setResult({ kind: "ok", text: `Checked in. Seat ${res.data.seatLabel}: let them in.` });
    } catch (err) {
      setResult({ kind: "error", text: errorMessage(err) });
    }
  }

  const usable = ticket && ticket.status === "CONFIRMED" && !ticket.checkedInAt;

  return (
    <Layout adminOnly>
      <h2>Check-in</h2>
      <form className="checkin-form" onSubmit={lookUp}>
        <label htmlFor="ref" className="visually-hidden">Ticket reference</label>
        <input
          id="ref"
          placeholder="Ticket reference, e.g. TKT-7Q4M2XKD"
          value={reference}
          onChange={(e) => setReference(e.target.value)}
          autoFocus
        />
        <button type="submit" className="btn-secondary">Look up</button>
      </form>

      {result && <p className={result.kind === "ok" ? "banner banner-ok big" : "banner banner-error big"}>{result.text}</p>}

      {ticket && (
        <article className="ticket-check">
          <dl>
            <dt>Reference</dt><dd className="mono">{ticket.reference}</dd>
            <dt>Event</dt><dd>{ticket.eventName}</dd>
            <dt>When</dt><dd>{dateTime(ticket.eventDate)} · {ticket.venue}</dd>
            <dt>Seat</dt><dd><strong>{ticket.seatLabel}</strong></dd>
            <dt>Ticket holder</dt><dd>{ticket.holderEmail}</dd>
            <dt>Status</dt>
            <dd>
              {ticket.checkedInAt
                ? `Already used at ${new Date(ticket.checkedInAt).toLocaleTimeString()}`
                : ticket.status === "CONFIRMED" ? "Valid" : ticket.status.toLowerCase()}
            </dd>
          </dl>
          {usable && <button className="btn-primary" onClick={checkIn}>Check in</button>}
        </article>
      )}
    </Layout>
  );
}
