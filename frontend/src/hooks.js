import { useEffect, useState } from "react";
import api, { errorMessage } from "./api";

/**
 * GET a URL and keep the result fresh.
 * Returns { data, error, loading, reload }. With intervalMs it re-fetches on a timer,
 * so changes made by background tasks (like an expired hold) show up on their own.
 */
export function useFetch(url, { intervalMs } = {}) {
  const [state, setState] = useState({ data: null, error: "", loading: true });
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    const load = () =>
      api.get(url).then(
        (res) => !cancelled && setState({ data: res.data, error: "", loading: false }),
        (err) => !cancelled && setState((s) => ({ ...s, error: errorMessage(err), loading: false }))
      );
    load();
    const timer = intervalMs ? setInterval(load, intervalMs) : null;
    return () => {
      cancelled = true;
      if (timer) clearInterval(timer);
    };
  }, [url, tick, intervalMs]);

  return { ...state, reload: () => setTick((t) => t + 1) };
}

/** The current time, updated every second (for countdowns). */
export function useNow() {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  return now;
}
