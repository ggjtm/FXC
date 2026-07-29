/*
 * Shared transport + formatting for the FXC consoles (docs/DESIGN.md §6).
 * Served from the fxc-common jar at /common/fxc-api.js.
 *
 * Deliberately conservative JS — IIFE, "use strict", no modules, no build step —
 * matching the rest of the repo's front end.
 *
 * Provides what a live operator console needs and the first chart UI lacked:
 * a polling loop that backs off instead of hammering a broken endpoint, and a
 * WebSocket client that reconnects and can tell "quiet market" from "dead socket".
 */
(function (global) {
  "use strict";

  var Fxc = global.Fxc || (global.Fxc = {});

  // ---------- HTTP ----------

  function qs(params) {
    var parts = [];
    Object.keys(params || {}).forEach(function (k) {
      var v = params[k];
      if (v !== null && v !== undefined && v !== "") {
        parts.push(encodeURIComponent(k) + "=" + encodeURIComponent(v));
      }
    });
    return parts.length ? "?" + parts.join("&") : "";
  }

  function request(method, path, params) {
    return fetch(path + qs(params), { method: method, cache: "no-store" }).then(function (res) {
      return res.text().then(function (body) {
        var parsed = null;
        try {
          parsed = body ? JSON.parse(body) : null;
        } catch (e) {
          parsed = null;
        }
        if (!res.ok) {
          var msg = (parsed && parsed.error) || body || (method + " " + path + " failed: " + res.status);
          throw new Error(msg);
        }
        return parsed;
      });
    });
  }

  Fxc.getJson = function (path, params) {
    return request("GET", path, params);
  };

  /** Control endpoints take query parameters and an empty body — no JSON parser server-side. */
  Fxc.postJson = function (path, params) {
    return request("POST", path, params);
  };

  Fxc.qs = qs;

  // ---------- Polling with backoff ----------

  /**
   * Call `task` (returning a promise) every `intervalMs`. On failure the delay
   * doubles up to 8x so a downed component is not hammered, and resets on the
   * first success. Returns a handle with stop()/now().
   */
  Fxc.poll = function (task, intervalMs, onError) {
    var stopped = false;
    var timer = null;
    var fails = 0;

    function delay() {
      return intervalMs * Math.min(8, Math.pow(2, fails));
    }

    function run() {
      if (stopped) {
        return;
      }
      var done = function (ok, err) {
        if (ok) {
          fails = 0;
        } else {
          fails++;
          if (onError) {
            onError(err);
          }
        }
        if (!stopped) {
          timer = global.setTimeout(run, delay());
        }
      };
      var result;
      try {
        result = task();
      } catch (e) {
        done(false, e);
        return;
      }
      if (result && typeof result.then === "function") {
        result.then(function () { done(true); }, function (e) { done(false, e); });
      } else {
        done(true);
      }
    }

    run();
    return {
      stop: function () {
        stopped = true;
        if (timer) {
          global.clearTimeout(timer);
        }
      },
      now: function () {
        if (timer) {
          global.clearTimeout(timer);
        }
        run();
      }
    };
  };

  // ---------- Live WebSocket with reconnect + staleness ----------

  /**
   * opts: { url, onMessage(obj), onState(state, info), staleAfterMs }
   * state is "connecting" | "open" | "stale" | "closed".
   *
   * The exchange feed sends a tick only when a window actually traded, plus a
   * periodic heartbeat, so "no message" alone does not mean "disconnected":
   * staleness is judged against the last message of ANY kind.
   */
  Fxc.liveSocket = function (opts) {
    var url = opts.url;
    var staleAfter = opts.staleAfterMs || 8000;
    var closed = false;
    var sock = null;
    var attempt = 0;
    var lastMessageAt = 0;
    var state = "connecting";
    var staleTimer = null;

    function setState(next, info) {
      if (state !== next) {
        state = next;
        if (opts.onState) {
          opts.onState(next, info);
        }
      }
    }

    function checkStale() {
      if (state === "open" && Date.now() - lastMessageAt > staleAfter) {
        setState("stale");
      }
    }

    function connect() {
      if (closed) {
        return;
      }
      setState("connecting");
      try {
        sock = new WebSocket(url);
      } catch (e) {
        retry();
        return;
      }
      sock.onopen = function () {
        attempt = 0;
        lastMessageAt = Date.now();
        setState("open");
      };
      sock.onmessage = function (ev) {
        lastMessageAt = Date.now();
        setState("open");
        var msg = null;
        try {
          msg = JSON.parse(ev.data);
        } catch (e) {
          return;
        }
        if (opts.onMessage) {
          opts.onMessage(msg);
        }
      };
      sock.onclose = function () {
        if (!closed) {
          retry();
        }
      };
      sock.onerror = function () {
        // onclose always follows; reconnect is handled there.
      };
    }

    function retry() {
      setState("closed");
      attempt++;
      var wait = Math.min(30000, 1000 * Math.pow(2, Math.min(5, attempt - 1)));
      global.setTimeout(connect, wait);
    }

    connect();
    staleTimer = global.setInterval(checkStale, 1000);

    return {
      close: function () {
        closed = true;
        global.clearInterval(staleTimer);
        if (sock) {
          try {
            sock.close();
          } catch (e) {
            // already closing
          }
        }
      },
      state: function () {
        return state;
      },
      ageMs: function () {
        return lastMessageAt ? Date.now() - lastMessageAt : Infinity;
      }
    };
  };

  // ---------- Formatting ----------

  Fxc.fmt = {
    /** Prices span FX (1.08425) and equities (42.00); pick decimals by magnitude. */
    px: function (v) {
      if (v === null || v === undefined || isNaN(v)) {
        return "—";
      }
      var abs = Math.abs(v);
      return Number(v).toFixed(abs !== 0 && abs < 10 ? 5 : 2);
    },
    qty: function (v) {
      if (v === null || v === undefined || isNaN(v)) {
        return "—";
      }
      return Number(v).toLocaleString(undefined, { maximumFractionDigits: 2 });
    },
    signed: function (v, decimals) {
      if (v === null || v === undefined || isNaN(v)) {
        return "—";
      }
      var d = decimals === undefined ? 2 : decimals;
      return (v > 0 ? "+" : "") + Number(v).toFixed(d);
    },
    pct: function (v) {
      if (v === null || v === undefined || isNaN(v)) {
        return "—";
      }
      return (v > 0 ? "+" : "") + Number(v).toFixed(2) + "%";
    },
    clock: function (ms) {
      if (!ms) {
        return "—";
      }
      var d = new Date(ms);
      return String(d.getHours()).padStart(2, "0") + ":"
        + String(d.getMinutes()).padStart(2, "0") + ":"
        + String(d.getSeconds()).padStart(2, "0");
    },
    duration: function (ms) {
      if (ms === null || ms === undefined) {
        return "—";
      }
      var s = Math.floor(ms / 1000);
      var h = Math.floor(s / 3600);
      var m = Math.floor((s % 3600) / 60);
      if (h > 0) {
        return h + "h " + m + "m";
      }
      if (m > 0) {
        return m + "m " + (s % 60) + "s";
      }
      return s + "s";
    }
  };

  /**
   * Categorical series colour for an entity. Fixed slot order, assigned by a
   * stable key order and never cycled or re-assigned by rank — so filtering or
   * re-sorting never repaints the survivors. Past 8 entities the caller folds the
   * tail into "Other" rather than generating a 9th hue.
   */
  Fxc.SERIES_SLOTS = 8;

  Fxc.seriesColor = function (index) {
    return "var(--series-" + ((index % Fxc.SERIES_SLOTS) + 1) + ")";
  };

  /** Resolve a CSS custom property to its computed value (D3 needs real colours). */
  Fxc.cssVar = function (name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  };

  /** Show/hide the shared error banner. */
  Fxc.banner = function (message) {
    var el = document.getElementById("banner");
    if (!el) {
      return;
    }
    if (message) {
      el.textContent = message;
      el.style.display = "block";
    } else {
      el.style.display = "none";
    }
  };
}(window));
