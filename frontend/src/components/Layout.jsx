import { Navigate, NavLink, useNavigate } from "react-router-dom";
import { clearSession, getSession } from "../auth";

/** Page frame with the top navigation. Redirects to login if needed. */
export default function Layout({ children, adminOnly = false, wide = false }) {
  const session = getSession();
  const navigate = useNavigate();

  if (!session) return <Navigate to="/" replace />;
  if (adminOnly && !session.isAdmin) return <Navigate to="/events" replace />;

  function handleLogout() {
    clearSession();
    navigate("/");
  }

  return (
    <>
      <header className="nav">
        <div className="nav-inner">
          <NavLink to="/events" className="brand">Ticket Booking</NavLink>
          <nav className="nav-links">
            <NavLink to="/events">Events</NavLink>
            <NavLink to="/bookings">My bookings</NavLink>
            {session.isAdmin && <NavLink to="/admin/events/new">New event</NavLink>}
            {session.isAdmin && <NavLink to="/checkin">Check-in</NavLink>}
          </nav>
          <div className="nav-user">
            <span className="nav-email" title={session.email}>{session.email}</span>
            <button className="logout-btn" onClick={handleLogout}>Log out</button>
          </div>
        </div>
      </header>
      <main className={wide ? "page page-wide" : "page"}>{children}</main>
    </>
  );
}
