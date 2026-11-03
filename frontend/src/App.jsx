import { BrowserRouter, Routes, Route, Navigate } from "react-router-dom";
import LoginPage from "./pages/LoginPage";
import RegisterPage from "./pages/RegisterPage";
import EventsPage from "./pages/EventsPage";
import SeatPickerPage from "./pages/SeatPickerPage";
import MyBookingsPage from "./pages/MyBookingsPage";
import NewEventPage from "./pages/NewEventPage";
import CheckInPage from "./pages/CheckInPage";

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/events" element={<EventsPage />} />
        <Route path="/events/:eventId" element={<SeatPickerPage />} />
        <Route path="/bookings" element={<MyBookingsPage />} />
        <Route path="/admin/events/new" element={<NewEventPage />} />
        <Route path="/checkin" element={<CheckInPage />} />
        <Route path="*" element={<Navigate to="/events" replace />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
