/*
 * FxcExchange console (docs/DESIGN.md §6, FxcExchange/docs/stories/002).
 *
 * Main pane: 1-minute price candles for a selectable security, with a translucent volume bar
 * underlay occupying the bottom 20% of the plot area (§6) and story 001's right-side
 * volume-by-price histogram at 30% opacity. Rendered with D3 into SVG.
 *
 * Two deliberate design notes:
 *
 *  - The x scale is a real time scale. Candle buckets with no trades are omitted by the REST
 *    service, so indexing bars by array position (as the first Canvas version did) drew unequal
 *    time gaps as equal ones. A time scale makes gaps truthful.
 *
 *  - Volume is drawn against its own vertical extent, which is a second scale on one plot. That is
 *    normally a chart smell, so it is contained: the volume band carries NO axis and no gridlines,
 *    is confined to the bottom fifth beneath the price marks, and its values are readable as text in
 *    the readout and tooltip. Nothing invites reading a volume magnitude off the price axis.
 */
(function (global) {
  "use strict";

  var Fxc = global.Fxc;

  // §6: the volume underlay occupies the bottom 20% of the canvas.
  var VOL_FRACTION = 0.20;
  // story 001: the volume-by-price histogram extends from the right edge, 30% transparent.
  var HIST_FRACTION = 0.30;
  var HIST_OPACITY = 0.30;
  var VOL_OPACITY = 0.35;
  var MARGIN = { top: 12, right: 14, bottom: 26, left: 64 };

  var GRAN_MS = {
    "1m": 60000, "5m": 300000, "15m": 900000, "30m": 1800000,
    "1h": 3600000, "4h": 14400000, "1d": 86400000, "1w": 604800000
  };

  var state = {
    symbol: null,
    style: "candles",
    granularity: "1m",
    granularityMs: 60000,
    candles: [],
    volumeByPrice: [],
    live: false,
    socket: null,
    hover: null,
    status: null,
    wsPort: null,
    controlsEnabled: false
  };

  var el = {};
  var colors = {};
  var statusStrip = null;
  var menu = null;
  var svg = null;

  // ---------- boot ----------

  function init() {
    ["symbol", "start", "end", "granularity", "load", "styleToggle", "applied",
      "plot", "chart", "empty", "tooltip", "readout", "status", "controls"].forEach(function (id) {
      el[id] = document.getElementById(id);
    });

    ["--surface", "--panel", "--panel2", "--border", "--grid", "--text", "--muted", "--accent",
      "--up", "--down", "--series-1"].forEach(function (name) {
      colors[name.replace("--", "")] = Fxc.cssVar(name);
    });

    svg = d3.select(el.chart);
    statusStrip = Fxc.status(el.status, { sparklineLabel: "trades/s" });

    wireToolbar();

    Fxc.getJson("/api/config").then(function (config) {
      state.wsPort = config.wsPort;
      state.controlsEnabled = !!config.controlsEnabled;
      wireControls();
      return Fxc.getJson("/api/symbols");
    }).then(function (symbols) {
      symbols.forEach(function (s) {
        var option = document.createElement("option");
        option.value = s;
        option.textContent = s;
        el.symbol.appendChild(option);
      });
      state.symbol = symbols.length ? symbols[0] : null;
      el.symbol.value = state.symbol;
      // Poll status independently of the chart so the state pill stays live even if a
      // candle request fails.
      Fxc.poll(refreshStatus, 1000);
      return load();
    }).catch(function (err) {
      Fxc.banner("Startup failed: " + err.message);
    });

    var resizeTimer = null;
    global.addEventListener("resize", function () {
      global.clearTimeout(resizeTimer);
      resizeTimer = global.setTimeout(render, 120);
    });
  }

  function wireToolbar() {
    el.load.addEventListener("click", function () {
      state.symbol = el.symbol.value;
      load();
    });
    el.symbol.addEventListener("change", function () {
      state.symbol = el.symbol.value;
      load();
    });
    el.granularity.addEventListener("change", function () {
      load();
    });
    Array.prototype.forEach.call(el.styleToggle.querySelectorAll("button"), function (button) {
      button.addEventListener("click", function () {
        Array.prototype.forEach.call(el.styleToggle.querySelectorAll("button"), function (b) {
          b.classList.remove("is-active");
        });
        button.classList.add("is-active");
        state.style = button.getAttribute("data-style");
        render();
      });
    });

    el.plot.addEventListener("mousemove", onHover);
    el.plot.addEventListener("mouseleave", function () {
      state.hover = null;
      el.tooltip.style.display = "none";
      renderOverlay();
      renderReadout();
    });
  }

  function wireControls() {
    if (!state.controlsEnabled) {
      // A read-only console must not offer controls it cannot perform.
      el.controls.style.display = "none";
      return;
    }
    menu = Fxc.menu(el.controls, {
      haltMarket: function () { return control("/api/session/halt", null); },
      openMarket: function () { return control("/api/session/open", null); },
      haltSymbol: function () { return control("/api/session/halt", state.symbol); },
      openSymbol: function () { return control("/api/session/open", state.symbol); },
      clearSymbol: function () { return control("/api/book/clear", state.symbol); },
      clearAll: function () { return control("/api/book/clear", null); }
    });
  }

  function control(path, symbol) {
    Fxc.banner(null);
    return Fxc.postJson(path, symbol ? { symbol: symbol } : {}).then(function () {
      return refreshStatus();
    });
  }

  // ---------- data ----------

  function toMillis(input) {
    return input.value ? new Date(input.value).getTime() : null;
  }

  function load() {
    if (!state.symbol) {
      return Promise.resolve();
    }
    state.granularity = el.granularity.value;
    state.granularityMs = GRAN_MS[state.granularity] || 60000;

    var end = toMillis(el.end);
    var start = toMillis(el.start);
    state.live = end === null; // an open-ended range means "follow the live feed"

    el.plot.classList.add("is-loading");
    return Fxc.getJson("/api/candles", {
      symbol: state.symbol,
      start: start,
      end: end,
      granularity: state.granularity
    }).then(function (data) {
      state.candles = (data.candles || []).map(function (c) {
        return { t: c.t, o: +c.o, h: +c.h, l: +c.l, c: +c.c, v: +c.v };
      });
      state.volumeByPrice = (data.volumeByPrice || []).map(function (p) {
        return { price: +p.price, volume: +p.volume };
      });
      // The server may coarsen the granularity for old windows; show what was actually applied.
      state.granularityMs = data.granularityMs || state.granularityMs;
      el.applied.textContent = describeGranularity(state.granularityMs)
        + (state.live ? " · live" : " · historical");
      Fxc.banner(null);
      openOrCloseSocket();
      render();
    }).catch(function (err) {
      Fxc.banner("Could not load candles: " + err.message);
    }).then(function () {
      el.plot.classList.remove("is-loading");
    });
  }

  function describeGranularity(ms) {
    var names = Object.keys(GRAN_MS);
    for (var i = 0; i < names.length; i++) {
      if (GRAN_MS[names[i]] === ms) {
        return names[i];
      }
    }
    return Math.round(ms / 1000) + "s";
  }

  function openOrCloseSocket() {
    if (state.socket) {
      state.socket.close();
      state.socket = null;
    }
    if (!state.live || !state.wsPort) {
      return;
    }
    state.socket = Fxc.liveSocket({
      url: "ws://" + location.hostname + ":" + state.wsPort + "/ws?symbol="
        + encodeURIComponent(state.symbol),
      onMessage: function (msg) {
        if (msg.type === "tick") {
          foldTick(msg);
        }
        // A heartbeat carries no data — receiving it is the point (liveness).
      },
      onState: function () {
        refreshStatusPill();
      }
    });
  }

  /**
   * Merge a one-second tick window into the live candle, appending a new bucket when the window
   * crosses a boundary. Keeps the chart moving without refetching history.
   */
  function foldTick(tick) {
    var bucket = Math.floor(tick.windowStart / state.granularityMs) * state.granularityMs;
    var last = state.candles.length ? state.candles[state.candles.length - 1] : null;
    var price = +tick.last;
    var volume = +tick.volume;

    if (!last || bucket > last.t) {
      state.candles.push({ t: bucket, o: price, h: price, l: price, c: price, v: volume });
    } else if (bucket === last.t) {
      last.h = Math.max(last.h, price);
      last.l = Math.min(last.l, price);
      last.c = price;
      last.v += volume;
    } else {
      return; // a late window for an already-closed bucket; history will correct it
    }

    (tick.byPrice || []).forEach(function (pv) {
      var p = +pv.price;
      var existing = null;
      for (var i = 0; i < state.volumeByPrice.length; i++) {
        if (state.volumeByPrice[i].price === p) {
          existing = state.volumeByPrice[i];
          break;
        }
      }
      if (existing) {
        existing.volume += +pv.volume;
      } else {
        state.volumeByPrice.push({ price: p, volume: +pv.volume });
        state.volumeByPrice.sort(function (a, b) { return a.price - b.price; });
      }
    });

    render();
  }

  // ---------- status ----------

  function refreshStatus() {
    return Fxc.getJson("/api/status").then(function (status) {
      state.status = status;
      refreshStatusPill();
      if (menu) {
        var halted = state.status.marketState === "HALTED";
        menu.setEnabled("haltMarket", !halted);
        menu.setEnabled("openMarket", halted);
        var sym = symbolStatus();
        var symHalted = !!sym && sym.state === "HALTED";
        menu.setEnabled("haltSymbol", !symHalted);
        menu.setEnabled("openSymbol", symHalted);
      }
    });
  }

  function symbolStatus() {
    if (!state.status || !state.status.symbols) {
      return null;
    }
    for (var i = 0; i < state.status.symbols.length; i++) {
      if (state.status.symbols[i].symbol === state.symbol) {
        return state.status.symbols[i];
      }
    }
    return null;
  }

  /**
   * The status pill. Colour reinforces, but the glyph and the label are what carry the state, so it
   * stays readable without colour.
   */
  function refreshStatusPill() {
    var status = state.status;
    if (!status) {
      statusStrip.update({ state: "warn", label: "CONNECTING", metrics: [] });
      return;
    }
    var sym = symbolStatus();
    var pill;
    if (status.marketState === "HALTED") {
      pill = { state: "critical", label: "MARKET HALTED" };
    } else if (sym && sym.state === "HALTED") {
      pill = { state: "serious", label: state.symbol + " HALTED" };
    } else if (state.live && state.socket && state.socket.state() === "closed") {
      pill = { state: "critical", label: "FEED DISCONNECTED" };
    } else if (state.live && state.socket && state.socket.state() === "stale") {
      pill = { state: "warn", label: "FEED STALE" };
    } else {
      pill = { state: "good", label: "OPEN" };
    }

    pill.sample = status.tradesPerSec;
    pill.sampleText = Number(status.tradesPerSec).toFixed(2);
    pill.metrics = [
      { label: "resting", value: sym ? String(sym.restingOrders) : "—" },
      { label: "trades", value: Fxc.fmt.qty(status.totalTrades) },
      { label: "clients", value: String(status.wsClients) },
      { label: "up", value: Fxc.fmt.duration(status.uptimeMs) }
    ];
    statusStrip.update(pill);
  }

  // ---------- chart ----------

  function plotBox() {
    var w = el.plot.clientWidth;
    var h = el.plot.clientHeight;
    return {
      w: w,
      h: h,
      iw: Math.max(10, w - MARGIN.left - MARGIN.right),
      ih: Math.max(10, h - MARGIN.top - MARGIN.bottom)
    };
  }

  var scales = { x: null, y: null, box: null };

  function render() {
    var box = plotBox();
    var data = state.candles;
    svg.attr("viewBox", "0 0 " + box.w + " " + box.h);
    svg.selectAll("*:not(title)").remove();

    el.empty.style.display = data.length ? "none" : "flex";
    if (!data.length) {
      scales.x = null;
      renderReadout();
      return;
    }

    var x = d3.scaleTime()
      .domain([data[0].t, data[data.length - 1].t + state.granularityMs])
      .range([0, box.iw]);

    var lo = d3.min(data, function (d) { return d.l; });
    var hi = d3.max(data, function (d) { return d.h; });
    var pad = (hi - lo) * 0.06 || Math.max(hi * 0.001, 0.01);
    var y = d3.scaleLinear().domain([lo - pad, hi + pad]).nice().range([box.ih, 0]);

    scales.x = x;
    scales.y = y;
    scales.box = box;

    var root = svg.append("g").attr("transform", "translate(" + MARGIN.left + "," + MARGIN.top + ")");
    var decimals = hi < 10 ? 5 : 2;

    drawGrid(root, y, box, decimals);
    drawPriceHistogram(root, y, box);   // right-side volume-by-price, underneath the marks
    drawVolumeUnderlay(root, x, box, data);
    if (state.style === "lines") {
      drawLines(root, x, y, data);
    } else {
      drawCandles(root, x, y, data);
    }
    drawTimeAxis(root, x, box);

    root.append("g").attr("class", "fxc-crosshair fxc-overlay");
    renderOverlay();
    renderReadout();
  }

  function drawGrid(root, y, box, decimals) {
    var ticks = y.ticks(6);
    root.append("g").attr("class", "fxc-grid").selectAll("line").data(ticks).join("line")
      .attr("x1", 0).attr("x2", box.iw)
      .attr("y1", function (d) { return y(d); })
      .attr("y2", function (d) { return y(d); });

    root.append("g").attr("class", "fxc-axis")
      .call(d3.axisLeft(y).ticks(6).tickFormat(d3.format("." + decimals + "f")).tickSizeOuter(0));
  }

  function drawTimeAxis(root, x, box) {
    root.append("g").attr("class", "fxc-axis")
      .attr("transform", "translate(0," + box.ih + ")")
      .call(d3.axisBottom(x).ticks(Math.max(2, Math.floor(box.iw / 110))).tickSizeOuter(0));
  }

  /**
   * Volume as a translucent underlay in the bottom 20% of the plot area (§6). No axis and no
   * gridlines: the band shows relative activity, it is not a second readable scale.
   */
  function drawVolumeUnderlay(root, x, box, data) {
    var vMax = d3.max(data, function (d) { return d.v; }) || 1;
    var height = d3.scaleLinear().domain([0, vMax]).range([0, box.ih * VOL_FRACTION]);
    var bw = barWidth(x, box);

    root.append("g").attr("class", "fxc-volume").selectAll("rect").data(data).join("rect")
      .attr("x", function (d) { return x(d.t) + 1; })
      .attr("width", bw)
      .attr("y", function (d) { return box.ih - height(d.v); })
      .attr("height", function (d) { return height(d.v); })
      .attr("fill", function (d) { return d.c >= d.o ? colors.up : colors.down; })
      .attr("fill-opacity", VOL_OPACITY);
  }

  /** Story 001's volume-at-price histogram: bars from the right edge, 30% transparent. */
  function drawPriceHistogram(root, y, box) {
    var points = state.volumeByPrice;
    if (!points.length) {
      return;
    }
    var vMax = d3.max(points, function (d) { return d.volume; }) || 1;
    var width = d3.scaleLinear().domain([0, vMax]).range([0, box.iw * HIST_FRACTION]);
    // Keep bars separated by roughly the 2px surface gap rather than merging into a block.
    var barH = Math.max(2, Math.min(14, box.ih / (points.length * 1.6)));

    root.append("g").attr("class", "fxc-hist").selectAll("rect").data(points).join("rect")
      .attr("x", function (d) { return box.iw - width(d.volume); })
      .attr("width", function (d) { return width(d.volume); })
      .attr("y", function (d) { return y(d.price) - barH / 2; })
      .attr("height", Math.max(1, barH - 2))
      .attr("fill", colors.muted)
      .attr("fill-opacity", HIST_OPACITY);
  }

  function barWidth(x, box) {
    var span = x(state.candles[0].t + state.granularityMs) - x(state.candles[0].t);
    return Math.max(1, Math.min(box.iw, span) - 2); // 2px surface gap between adjacent bars
  }

  /**
   * Candles. Direction is carried by shape as well as colour — hollow body for up, filled for down,
   * the traditional convention — because red/green alone is the weakest possible channel for the
   * one distinction this chart most needs to convey.
   */
  function drawCandles(root, x, y, data) {
    var bw = barWidth(x, scales.box);
    var group = root.append("g").attr("class", "fxc-candles");
    var hollow = bw >= 3; // below that a hollow body has no visible interior

    group.selectAll("line.wick").data(data).join("line")
      .attr("class", "wick")
      .attr("x1", function (d) { return x(d.t) + 1 + bw / 2; })
      .attr("x2", function (d) { return x(d.t) + 1 + bw / 2; })
      .attr("y1", function (d) { return y(d.h); })
      .attr("y2", function (d) { return y(d.l); })
      .attr("stroke", function (d) { return d.c >= d.o ? colors.up : colors.down; })
      .attr("stroke-width", 1);

    group.selectAll("rect.body").data(data).join("rect")
      .attr("class", "body")
      .attr("x", function (d) { return x(d.t) + 1; })
      .attr("width", bw)
      .attr("y", function (d) { return y(Math.max(d.o, d.c)); })
      .attr("height", function (d) { return Math.max(1, Math.abs(y(d.o) - y(d.c))); })
      .attr("fill", function (d) {
        if (d.c < d.o) {
          return colors.down;
        }
        return hollow ? colors.surface : colors.up;
      })
      .attr("stroke", function (d) { return d.c >= d.o ? colors.up : colors.down; })
      .attr("stroke-width", hollow ? 1 : 0);
  }

  /** A single close-price series: slot 1 of the categorical palette, 2px. */
  function drawLines(root, x, y, data) {
    var bw = barWidth(x, scales.box);
    root.append("path")
      .datum(data)
      .attr("fill", "none")
      .attr("stroke", colors["series-1"])
      .attr("stroke-width", 2)
      .attr("stroke-linejoin", "round")
      .attr("stroke-linecap", "round")
      .attr("d", d3.line()
        .x(function (d) { return x(d.t) + 1 + bw / 2; })
        .y(function (d) { return y(d.c); }));
  }

  // ---------- hover ----------

  function onHover(event) {
    if (!scales.x || !state.candles.length) {
      return;
    }
    var rect = el.plot.getBoundingClientRect();
    var px = event.clientX - rect.left - MARGIN.left;
    var py = event.clientY - rect.top - MARGIN.top;
    if (px < 0 || px > scales.box.iw || py < 0 || py > scales.box.ih) {
      return;
    }
    var t = scales.x.invert(px).getTime();
    var nearest = null;
    var bestGap = Infinity;
    for (var i = 0; i < state.candles.length; i++) {
      var gap = Math.abs(state.candles[i].t + state.granularityMs / 2 - t);
      if (gap < bestGap) {
        bestGap = gap;
        nearest = state.candles[i];
      }
    }
    state.hover = { candle: nearest, px: px, py: py, clientX: event.clientX, clientY: event.clientY };
    renderOverlay();
    renderReadout();
    showTooltip();
  }

  function renderOverlay() {
    var layer = svg.select("g.fxc-overlay");
    if (layer.empty()) {
      return;
    }
    layer.selectAll("*").remove();
    if (!state.hover || !scales.x) {
      return;
    }
    var bw = barWidth(scales.x, scales.box);
    var cx = scales.x(state.hover.candle.t) + 1 + bw / 2;
    layer.append("line").attr("x1", cx).attr("x2", cx).attr("y1", 0).attr("y2", scales.box.ih);
    layer.append("line")
      .attr("x1", 0).attr("x2", scales.box.iw)
      .attr("y1", state.hover.py).attr("y2", state.hover.py);
  }

  function showTooltip() {
    var candle = state.hover.candle;
    var rect = el.plot.getBoundingClientRect();
    el.tooltip.innerHTML = "";

    var title = document.createElement("div");
    title.className = "fxc-tt-title";
    title.textContent = state.symbol + " · " + new Date(candle.t).toLocaleString();
    el.tooltip.appendChild(title);

    var dl = document.createElement("dl");
    [["Open", Fxc.fmt.px(candle.o)], ["High", Fxc.fmt.px(candle.h)], ["Low", Fxc.fmt.px(candle.l)],
      ["Close", Fxc.fmt.px(candle.c)], ["Volume", Fxc.fmt.qty(candle.v)]].forEach(function (row) {
      var dt = document.createElement("dt");
      dt.textContent = row[0];
      var dd = document.createElement("dd");
      dd.textContent = row[1];
      dl.appendChild(dt);
      dl.appendChild(dd);
    });
    el.tooltip.appendChild(dl);

    el.tooltip.style.display = "block";
    var left = state.hover.clientX - rect.left + 14;
    var top = state.hover.clientY - rect.top + 12;
    if (left + el.tooltip.offsetWidth > rect.width) {
      left = state.hover.clientX - rect.left - el.tooltip.offsetWidth - 14;
    }
    if (top + el.tooltip.offsetHeight > rect.height) {
      top = rect.height - el.tooltip.offsetHeight - 6;
    }
    el.tooltip.style.left = Math.max(0, left) + "px";
    el.tooltip.style.top = Math.max(0, top) + "px";
  }

  /**
   * The hovered bar, or the latest one when not hovering, as plain text — so no value in this chart
   * is reachable only by hovering.
   */
  function renderReadout() {
    el.readout.textContent = "";
    if (!state.candles.length) {
      return;
    }
    var candle = state.hover ? state.hover.candle : state.candles[state.candles.length - 1];
    var up = candle.c >= candle.o;

    var swatch = document.createElement("span");
    swatch.className = "swatch";
    swatch.style.background = up ? colors.up : colors.down;

    var direction = document.createElement("span");
    direction.className = "item " + (up ? "up" : "down");
    direction.appendChild(swatch);
    var bold = document.createElement("b");
    bold.textContent = up ? "UP (hollow)" : "DOWN (filled)";
    direction.appendChild(bold);
    el.readout.appendChild(direction);

    [[state.hover ? "at" : "latest", new Date(candle.t).toLocaleString()],
      ["O", Fxc.fmt.px(candle.o)], ["H", Fxc.fmt.px(candle.h)], ["L", Fxc.fmt.px(candle.l)],
      ["C", Fxc.fmt.px(candle.c)], ["V", Fxc.fmt.qty(candle.v)],
      ["bars", String(state.candles.length)]].forEach(function (pair) {
      var item = document.createElement("span");
      item.className = "item";
      item.appendChild(document.createTextNode(pair[0]));
      var value = document.createElement("b");
      value.textContent = pair[1];
      item.appendChild(value);
      el.readout.appendChild(item);
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
}(window));
