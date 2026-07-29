/*
 * The D3 status indicator required by docs/DESIGN.md §6.3, shared by every console.
 * Served from the fxc-common jar at /common/fxc-status.js. Requires d3 and fxc-api.js.
 *
 * Three parts, left to right:
 *   1. a state pill — distinct glyph + status colour + text label. The glyph and
 *      label are what carry the meaning; the colour only reinforces it, so the
 *      indicator still reads under any colour-vision deficiency or in mono print.
 *   2. a throughput sparkline (one series, so no legend — the adjacent label names
 *      it) with the current value printed beside it, never hover-gated.
 *   3. plain metric readouts.
 */
(function (global) {
  "use strict";

  var Fxc = global.Fxc || (global.Fxc = {});

  var STATES = {
    good: { color: "--status-good", glyph: "●" },      /* filled circle */
    warn: { color: "--status-warn", glyph: "▲" },      /* triangle */
    serious: { color: "--status-serious", glyph: "◆" }, /* diamond */
    critical: { color: "--status-critical", glyph: "■" } /* square */
  };

  var SPARK_W = 108;
  var SPARK_H = 26;
  var SPARK_SAMPLES = 60;

  /**
   * @param container element to render into (typically .fxc-status)
   * @param opts      { sparklineLabel: string }
   */
  Fxc.status = function (container, opts) {
    opts = opts || {};
    var samples = [];

    var pill = document.createElement("div");
    pill.className = "fxc-status-state";
    var glyph = document.createElement("span");
    glyph.className = "fxc-glyph";
    var stateLabel = document.createElement("span");
    pill.appendChild(glyph);
    pill.appendChild(stateLabel);

    var sparkWrap = document.createElement("div");
    sparkWrap.className = "fxc-status-metrics";
    var sparkName = document.createElement("span");
    sparkName.textContent = opts.sparklineLabel || "activity";
    var sparkSvg = d3.select(sparkWrap).append("svg")
      .attr("width", SPARK_W)
      .attr("height", SPARK_H)
      .attr("aria-hidden", "true");
    var sparkValue = document.createElement("b");
    sparkValue.textContent = "0";
    sparkWrap.insertBefore(sparkName, sparkWrap.firstChild);
    sparkWrap.appendChild(sparkValue);

    var metrics = document.createElement("div");
    metrics.className = "fxc-status-metrics";

    container.appendChild(pill);
    container.appendChild(sparkWrap);
    container.appendChild(metrics);

    var area = sparkSvg.append("path").attr("fill", Fxc.cssVar("--accent")).attr("fill-opacity", 0.16);
    var line = sparkSvg.append("path")
      .attr("fill", "none")
      .attr("stroke", Fxc.cssVar("--accent"))
      .attr("stroke-width", 2)
      .attr("stroke-linejoin", "round")
      .attr("stroke-linecap", "round");

    function drawSpark() {
      var x = d3.scaleLinear().domain([0, Math.max(1, SPARK_SAMPLES - 1)]).range([1, SPARK_W - 1]);
      // Floor the domain at 1 so an all-zero series draws flat on the baseline
      // instead of exploding a 0-height scale.
      var y = d3.scaleLinear()
        .domain([0, Math.max(1, d3.max(samples) || 0)])
        .range([SPARK_H - 2, 2]);
      var idx = function (d, i) { return x(i + Math.max(0, SPARK_SAMPLES - samples.length)); };
      line.datum(samples).attr("d", d3.line().x(idx).y(function (d) { return y(d); }));
      area.datum(samples).attr("d", d3.area().x(idx).y0(SPARK_H - 1).y1(function (d) { return y(d); }));
    }

    drawSpark();

    return {
      /**
       * @param s { state, label, sample, sampleText, metrics: [{label, value}] }
       */
      update: function (s) {
        var def = STATES[s.state] || STATES.warn;
        glyph.textContent = def.glyph;
        pill.style.color = Fxc.cssVar(def.color);
        stateLabel.textContent = s.label;

        if (s.sample !== null && s.sample !== undefined) {
          samples.push(s.sample);
          while (samples.length > SPARK_SAMPLES) {
            samples.shift();
          }
          sparkValue.textContent = s.sampleText !== undefined ? s.sampleText : String(s.sample);
          drawSpark();
        }

        if (s.metrics) {
          metrics.textContent = "";
          s.metrics.forEach(function (m) {
            var span = document.createElement("span");
            span.appendChild(document.createTextNode(m.label + " "));
            var b = document.createElement("b");
            b.textContent = m.value;
            span.appendChild(b);
            metrics.appendChild(span);
          });
        }
      },

      /** Reset the sparkline (e.g. after a session restart). */
      clear: function () {
        samples = [];
        drawSpark();
      }
    };
  };
}(window));
