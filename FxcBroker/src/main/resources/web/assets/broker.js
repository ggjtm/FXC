/*
 * FxcBroker console (docs/DESIGN.md §6, FxcBroker/docs/stories/002).
 *
 * Top: a ticker of last sale prices from the exchange feed. Below: one D3 line per managed account,
 * cumulative trade count on the x axis against P&L relative to the start of the session on the y.
 *
 * Design notes:
 *
 *  - One axis. Every account's y value is USD relative to its own session start, so the accounts are
 *    directly comparable on a single scale — no second axis is introduced for accounts of different
 *    size.
 *
 *  - Colour follows the account, assigned from the fixed categorical order by sorted account number.
 *    Adding or removing an account never repaints the others.
 *
 *  - The curve is a ROLLING WINDOW (stories/003): the backend keeps the last `windowMs` of points and
 *    drops the rest, so this shows recent trading rather than a session that eventually froze. The
 *    axis says which window it is, because a chart that quietly forgets its left edge is a chart that
 *    lies about its own x range.
 *
 *  - With an account per agent (stories/004) there are more accounts than the palette has colours, so
 *    the backend plots three groups of five — the best by P&L, the most active, and the worst — and
 *    tags each account with the `groups` it is in. Colour follows the GROUP, not the account: green
 *    for winners, blue for the most active, red for losers, so a glance at the chart says which
 *    question each line answers. Accounts in two groups (the most active is often also the worst) are
 *    drawn once, in the first group that claims them. The table below still lists every account.
 *
 *  - Everything plotted is also in the table below, and any account the backend could not fully value
 *    (unpriced holdings) is called out in text rather than silently drawn as if complete.
 *
 *  - The broker polls its own REST endpoints once a second instead of opening a WebSocket: the
 *    console then keeps working when the exchange's feed service is switched off, and it reads the
 *    broker's own market data rather than reaching past it to the exchange.
 */
(function (global) {
  "use strict";

  var Fxc = global.Fxc;
  var MARGIN = { top: 14, right: 92, bottom: 30, left: 74 };
  var POLL_MS = 1000;
  var MAX_DIRECT_LABELS = 4;

  var state = {
    status: null,
    pnl: [],
    lastSales: {},
    colorOf: {},
    hover: null,
    controlsEnabled: false
  };

  var el = {};
  var colors = {};
  var statusStrip = null;
  var menu = null;
  var svg = null;
  var scales = { x: null, y: null, box: null };
  var previousFills = null;

  // ---------- boot ----------

  function init() {
    ["status", "controls", "banner", "ticker", "plot", "chart", "empty", "tooltip",
      "legend", "tableBody", "notice"].forEach(function (id) {
      el[id] = document.getElementById(id);
    });
    ["--surface", "--border", "--grid", "--text", "--muted", "--up", "--down"].forEach(function (name) {
      colors[name.replace("--", "")] = Fxc.cssVar(name);
    });
    for (var slot = 1; slot <= Fxc.SERIES_SLOTS; slot++) {
      colors["series-" + slot] = Fxc.cssVar("--series-" + slot);
    }

    svg = d3.select(el.chart);
    statusStrip = Fxc.status(el.status, { sparklineLabel: "fills/s" });

    el.plot.addEventListener("mousemove", onHover);
    el.plot.addEventListener("mouseleave", function () {
      state.hover = null;
      el.tooltip.style.display = "none";
      renderOverlay();
    });

    Fxc.getJson("/api/config").then(function (config) {
      state.controlsEnabled = !!config.controlsEnabled;
      wireControls();
    }).catch(function (err) {
      Fxc.banner("Could not read console config: " + err.message);
    });

    Fxc.poll(refresh, POLL_MS, function (err) {
      Fxc.banner("Broker unreachable: " + err.message);
    });

    var resizeTimer = null;
    global.addEventListener("resize", function () {
      global.clearTimeout(resizeTimer);
      resizeTimer = global.setTimeout(render, 120);
    });
  }

  function wireControls() {
    if (!state.controlsEnabled) {
      el.controls.style.display = "none";
      return;
    }
    menu = Fxc.menu(el.controls, {
      stop: function () { return trading("/api/trading/stop"); },
      start: function () { return trading("/api/trading/start"); }
    });
  }

  function trading(path) {
    Fxc.banner(null);
    return Fxc.postJson(path, {}).then(function () {
      return refresh();
    });
  }

  // ---------- data ----------

  function refresh() {
    return Promise.all([
      Fxc.getJson("/api/status"),
      Fxc.getJson("/api/pnl"),
      Fxc.getJson("/api/lastsale")
    ]).then(function (results) {
      state.status = results[0];
      state.pnl = results[1] || [];
      updateTicker(results[2] || []);
      assignColors();
      Fxc.banner(null);
      refreshStatusPill();
      render();
      renderTable();
      renderNotice();
      if (menu) {
        menu.setEnabled("stop", state.status.tradingEnabled);
        menu.setEnabled("start", !state.status.tradingEnabled);
      }
    });
  }

  //  Group palettes: five shades apiece, so five lines in a group stay distinguishable while the hue
  //  still says which group it is. Local to this console — the shared theme's --series-* slots are a
  //  categorical scale, and this is three ordered scales.
  var GROUP_COLORS = {
    top:    ["#1a7f4f", "#26a269", "#3fbc82", "#63d3a0", "#8fe4be"],
    active: ["#1f6fd0", "#3987e5", "#5da3f0", "#87bdf7", "#b3d6fb"],
    bottom: ["#a51d2d", "#c01c28", "#e01b24", "#ee5a60", "#f79ba0"]
  };
  var GROUP_LABELS = { top: "Best P&L", active: "Most active", bottom: "Worst P&L" };
  var GROUP_ORDER = ["top", "active", "bottom"];

  /** Which group owns an account's colour: the first of the three that claims it. */
  function groupOf(account) {
    var groups = account.groups || [];
    for (var i = 0; i < GROUP_ORDER.length; i++) {
      if (groups.indexOf(GROUP_ORDER[i]) >= 0) {
        return GROUP_ORDER[i];
      }
    }
    return null;
  }

  /** Assign each plotted account a shade of its group's hue, ranked within the group. */
  function assignGroupColors(accounts) {
    var seen = {};
    state.colorOf = {};
    GROUP_ORDER.forEach(function (group) {
      var members = accounts.filter(function (a) { return groupOf(a) === group; });
      members.forEach(function (account, index) {
        var palette = GROUP_COLORS[group];
        state.colorOf[account.account] = palette[index % palette.length];
        seen[account.account] = group;
      });
    });
    return seen;
  }

  /** Stable slot per account: sorted account order, so a new account never recolours the others. */
  function assignColors() {
    var accounts = state.pnl.map(function (p) { return p.account; }).sort();
    accounts.forEach(function (account, index) {
      if (state.colorOf[account] === undefined) {
        state.colorOf[account] = colors["series-" + ((index % Fxc.SERIES_SLOTS) + 1)];
      }
    });
  }

  function colorFor(account) {
    return state.colorOf[account] || colors.muted;
  }

  // ---------- ticker ----------

  function updateTicker(sales) {
    el.ticker.textContent = "";
    if (!sales.length) {
      var idle = document.createElement("span");
      idle.className = "fxc-tick";
      idle.textContent = "no sales yet";
      idle.style.color = colors.muted;
      el.ticker.appendChild(idle);
      return;
    }
    sales.forEach(function (sale) {
      var previous = state.lastSales[sale.symbol];
      var price = +sale.price;
      var direction = previous === undefined || price === previous ? ""
        : (price > previous ? " is-up" : " is-down");
      state.lastSales[sale.symbol] = price;

      var tick = document.createElement("span");
      tick.className = "fxc-tick" + direction;
      var symbol = document.createElement("span");
      symbol.className = "fxc-sym";
      symbol.textContent = sale.symbol;
      var value = document.createElement("span");
      value.className = "fxc-px";
      value.textContent = Fxc.fmt.px(price);
      var arrow = document.createElement("span");
      arrow.className = "fxc-arrow";
      // A glyph as well as a colour, so direction is not colour-alone.
      arrow.textContent = direction === " is-up" ? "▲" : (direction === " is-down" ? "▼" : "");
      tick.appendChild(symbol);
      tick.appendChild(value);
      tick.appendChild(arrow);
      el.ticker.appendChild(tick);
    });
  }

  // ---------- status ----------

  function refreshStatusPill() {
    var status = state.status;
    if (!status) {
      statusStrip.update({ state: "warn", label: "CONNECTING", metrics: [] });
      return;
    }
    var pill;
    if (!status.exchangeConnected) {
      pill = { state: "critical", label: "EXCHANGE DISCONNECTED" };
    } else if (!status.tradingEnabled) {
      pill = { state: "serious", label: "TRADING STOPPED" };
    } else {
      pill = { state: "good", label: "TRADING" };
    }

    // Fills per second, from the change in the cumulative counter between polls.
    var fillsPerSec = previousFills === null ? 0
      : Math.max(0, (status.fills - previousFills) * 1000 / POLL_MS);
    previousFills = status.fills;

    pill.sample = fillsPerSec;
    pill.sampleText = fillsPerSec.toFixed(1);
    pill.metrics = [
      { label: "routed", value: Fxc.fmt.qty(status.ordersRouted) },
      { label: "fills", value: Fxc.fmt.qty(status.fills) },
      { label: "rejects", value: Fxc.fmt.qty(status.rejects) },
      { label: "accounts", value: String(status.accounts) },
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

  function plotted() {
    return state.pnl.filter(function (p) { return p.points && p.points.length; });
  }

  function render() {
    var box = plotBox();
    var accounts = plotted();
    assignGroupColors(accounts);
    svg.attr("viewBox", "0 0 " + box.w + " " + box.h);
    svg.selectAll("*:not(title)").remove();
    el.empty.style.display = accounts.length ? "none" : "flex";
    renderLegend(accounts);
    if (!accounts.length) {
      scales.x = null;
      return;
    }

    var maxTrades = d3.max(accounts, function (a) {
      return d3.max(a.points, function (p) { return p.n; });
    }) || 1;
    var values = [];
    accounts.forEach(function (a) {
      a.points.forEach(function (p) { values.push(+p.relative); });
    });
    var lo = Math.min(0, d3.min(values));
    var hi = Math.max(0, d3.max(values));
    var pad = (hi - lo) * 0.1 || 1;

    var x = d3.scaleLinear().domain([0, Math.max(1, maxTrades)]).range([0, box.iw]);
    var y = d3.scaleLinear().domain([lo - pad, hi + pad]).nice().range([box.ih, 0]);
    scales.x = x;
    scales.y = y;
    scales.box = box;

    var root = svg.append("g").attr("transform", "translate(" + MARGIN.left + "," + MARGIN.top + ")");

    // Gridlines: solid hairlines, one shade off the surface.
    root.append("g").attr("class", "fxc-grid").selectAll("line").data(y.ticks(6)).join("line")
      .attr("x1", 0).attr("x2", box.iw)
      .attr("y1", function (d) { return y(d); })
      .attr("y2", function (d) { return y(d); });

    // Break-even is the reference the whole chart is read against, so it is drawn distinctly.
    root.append("line")
      .attr("x1", 0).attr("x2", box.iw)
      .attr("y1", y(0)).attr("y2", y(0))
      .attr("stroke", colors.border).attr("stroke-width", 1)
      .attr("shape-rendering", "crispEdges");

    root.append("g").attr("class", "fxc-axis")
      .call(d3.axisLeft(y).ticks(6).tickFormat(d3.format("+,.0f")).tickSizeOuter(0));
    root.append("g").attr("class", "fxc-axis")
      .attr("transform", "translate(0," + box.ih + ")")
      .call(d3.axisBottom(x)
        .ticks(Math.min(10, Math.max(2, maxTrades)))
        .tickFormat(d3.format("d"))
        .tickSizeOuter(0));

    root.append("text")
      .attr("x", box.iw / 2).attr("y", box.ih + 26)
      .attr("text-anchor", "middle").attr("fill", colors.muted)
      .attr("font-size", 10).text("cumulative trades · " + windowLabel(accounts));

    var line = d3.line()
      .x(function (p) { return x(p.n); })
      .y(function (p) { return y(+p.relative); });

    accounts.forEach(function (account) {
      var color = colorFor(account.account);
      var group = root.append("g").attr("class", "fxc-series");

      group.append("path")
        .datum(account.points)
        .attr("fill", "none")
        .attr("stroke", color)
        .attr("stroke-width", 2)
        .attr("stroke-linejoin", "round")
        .attr("stroke-linecap", "round")
        .attr("d", line);

      // Markers with a 2px surface ring, so overlapping series stay separable and a one- or
      // two-trade account is still visible rather than a bare dot.
      group.selectAll("circle").data(account.points).join("circle")
        .attr("cx", function (p) { return x(p.n); })
        .attr("cy", function (p) { return y(+p.relative); })
        .attr("r", account.points.length > 120 ? 0 : 3)
        .attr("fill", color)
        .attr("stroke", colors.surface)
        .attr("stroke-width", 2);

      // Direct end labels while there are few enough not to collide.
      if (accounts.length <= MAX_DIRECT_LABELS) {
        var last = account.points[account.points.length - 1];
        group.append("text")
          .attr("x", x(last.n) + 8)
          .attr("y", y(+last.relative) + 4)
          .attr("fill", color)
          .attr("font-size", 11)
          .attr("font-weight", 600)
          .text(account.account);
      }
    });

    root.append("g").attr("class", "fxc-crosshair fxc-overlay");
    renderOverlay();
  }

  /** "last 15 min" — what the backend says its window is, not what this file assumes. */
  function windowLabel(accounts) {
    var ms = accounts.length && accounts[0].windowMs ? +accounts[0].windowMs : 0;
    if (!ms) {
      return "session";
    }
    var minutes = Math.round(ms / 60000);
    return minutes >= 1 ? "last " + minutes + " min" : "last " + Math.round(ms / 1000) + " s";
  }

  /** Accounts the backend chose not to plot — reported, never silently missing. */
  function foldedAway() {
    return state.pnl.filter(function (p) { return !(p.points && p.points.length); }).length;
  }

  function renderLegend(accounts) {
    el.legend.textContent = "";
    var folded = foldedAway();
    if (!accounts.length) {
      return;
    }
    // One section per group, in a fixed order, so the eye learns where to look. An account that is in
    // two groups is listed under each — it really is both the most active and the worst — but it is
    // drawn once, in its colour-owning group.
    GROUP_ORDER.forEach(function (group) {
      var members = accounts.filter(function (a) { return (a.groups || []).indexOf(group) >= 0; });
      if (!members.length) {
        return;
      }
      var section = document.createElement("span");
      section.className = "fxc-legend-group";
      var label = document.createElement("span");
      label.className = "fxc-legend-label";
      label.textContent = GROUP_LABELS[group];
      section.appendChild(label);
      members.forEach(function (account) {
        var item = document.createElement("span");
        item.className = "fxc-legend-item";
        var swatch = document.createElement("span");
        swatch.className = "fxc-legend-swatch";
        swatch.style.background = colorFor(account.account);
        item.appendChild(swatch);
        item.appendChild(document.createTextNode(account.account + " "));
        var value = document.createElement("b");
        value.textContent = Fxc.fmt.signed(+account.relative);
        item.appendChild(value);
        section.appendChild(item);
      });
      el.legend.appendChild(section);
    });
    if (folded) {
      // The chart shows fifteen accounts at most; the table below has all of them.
      var rest = document.createElement("span");
      rest.className = "fxc-legend-item";
      rest.style.color = colors.muted;
      rest.textContent = "+" + folded + " more in the table";
      el.legend.appendChild(rest);
    }
  }

  // ---------- hover ----------

  function onHover(event) {
    var accounts = plotted();
    if (!scales.x || !accounts.length) {
      return;
    }
    var rect = el.plot.getBoundingClientRect();
    var px = event.clientX - rect.left - MARGIN.left;
    var py = event.clientY - rect.top - MARGIN.top;
    if (px < 0 || px > scales.box.iw || py < 0 || py > scales.box.ih) {
      return;
    }
    // Nearest point across all series, so the hit area is the whole plot rather than each 3px dot.
    var best = null;
    var bestDistance = Infinity;
    accounts.forEach(function (account) {
      account.points.forEach(function (point) {
        var dx = scales.x(point.n) - px;
        var dy = scales.y(+point.relative) - py;
        var distance = dx * dx + dy * dy;
        if (distance < bestDistance) {
          bestDistance = distance;
          best = { account: account, point: point };
        }
      });
    });
    if (!best) {
      return;
    }
    state.hover = { hit: best, clientX: event.clientX, clientY: event.clientY };
    renderOverlay();
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
    var hit = state.hover.hit;
    var cx = scales.x(hit.point.n);
    var cy = scales.y(+hit.point.relative);
    layer.append("line").attr("x1", cx).attr("x2", cx).attr("y1", 0).attr("y2", scales.box.ih);
    layer.append("circle")
      .attr("cx", cx).attr("cy", cy).attr("r", 5)
      .attr("fill", "none")
      .attr("stroke", colorFor(hit.account.account))
      .attr("stroke-width", 2);
  }

  function showTooltip() {
    var hit = state.hover.hit;
    var rect = el.plot.getBoundingClientRect();
    el.tooltip.innerHTML = "";

    var title = document.createElement("div");
    title.className = "fxc-tt-title";
    title.style.color = colorFor(hit.account.account);
    title.textContent = hit.account.account;
    el.tooltip.appendChild(title);

    var dl = document.createElement("dl");
    [["Trades", String(hit.point.n)],
      ["Relative", Fxc.fmt.signed(+hit.point.relative)],
      ["Realized", Fxc.fmt.signed(+hit.point.realized)],
      ["Unrealized", Fxc.fmt.signed(+hit.point.unrealized)],
      ["At", Fxc.fmt.clock(hit.point.ts)]].forEach(function (row) {
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

  // ---------- table + disclosures ----------

  function renderTable() {
    el.tableBody.textContent = "";
    state.pnl.forEach(function (account) {
      var row = document.createElement("tr");
      var relative = +account.relative;

      var name = document.createElement("td");
      var swatch = document.createElement("span");
      swatch.className = "fxc-legend-swatch";
      swatch.style.display = "inline-block";
      swatch.style.marginRight = "7px";
      swatch.style.background = colorFor(account.account);
      name.appendChild(swatch);
      name.appendChild(document.createTextNode(account.account));
      row.appendChild(name);

      [String(account.tradeCount),
        Fxc.fmt.signed(+account.realized),
        Fxc.fmt.signed(+account.unrealized)].forEach(function (text) {
        var cell = document.createElement("td");
        cell.textContent = text;
        row.appendChild(cell);
      });

      var relativeCell = document.createElement("td");
      relativeCell.className = relative > 0 ? "fxc-up" : (relative < 0 ? "fxc-down" : "");
      relativeCell.textContent = Fxc.fmt.signed(relative);
      row.appendChild(relativeCell);

      [Fxc.fmt.qty(+account.equity), Fxc.fmt.qty(+account.baseline)].forEach(function (text) {
        var cell = document.createElement("td");
        cell.textContent = text;
        row.appendChild(cell);
      });

      el.tableBody.appendChild(row);
    });
  }

  /** Say out loud when a figure is incomplete, rather than drawing it as if it were not. */
  function renderNotice() {
    var messages = [];
    state.pnl.forEach(function (account) {
      if (account.unpricedHoldings > 0) {
        messages.push(account.account + ": " + account.unpricedHoldings
          + " holding(s) excluded from equity (no price available)");
      }

    });
    if (messages.length) {
      el.notice.textContent = "⚠ " + messages.join(" · ");
      el.notice.classList.add("is-shown");
    } else {
      el.notice.textContent = "";
      el.notice.classList.remove("is-shown");
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
}(window));
