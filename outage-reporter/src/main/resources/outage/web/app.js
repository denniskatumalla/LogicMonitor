// Example Power & Light Outage Center. Plain browser JavaScript, no libraries.
// Every page works as server-rendered HTML first; this adds inline results,
// loading and error states, and live refresh.
(function () {
  "use strict";

  var PHONE = "1-800-555-0142";
  var UNAVAILABLE = "We can't take your report right now — please try again in a few minutes or call " + PHONE + ".";
  var LOOKUP_UNAVAILABLE = "Outage information is temporarily unavailable — please try again in a few minutes or call " + PHONE + ".";
  var TIMEOUT_MS = 10000;
  var SLOW_MS = 1000;

  function $(id) { return document.getElementById(id); }

  function el(tag, attrs, children) {
    var e = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (attrs[k] == null) return;
      if (k === "text") e.textContent = attrs[k];
      else if (k === "className") e.className = attrs[k];
      else e.setAttribute(k, attrs[k]);
    });
    (children || []).forEach(function (c) { if (c) e.appendChild(typeof c === "string" ? document.createTextNode(c) : c); });
    return e;
  }

  function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }

  function time(iso) {
    return iso ? new Date(iso).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" }) : null;
  }

  function number(n) { return Number(n).toLocaleString(); }

  // fetch with a timeout. Resolves to {status, body}; rejects only on network failure or timeout.
  function request(method, url, body) {
    var ctrl = new AbortController();
    var timer = setTimeout(function () { ctrl.abort(); }, TIMEOUT_MS);
    var opts = { method: method, headers: { "Accept": "application/json" }, signal: ctrl.signal, cache: "no-store" };
    if (body !== undefined) {
      opts.headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }
    return fetch(url, opts).then(function (res) {
      return res.json().catch(function () { return {}; }).then(function (json) {
        return { status: res.status, body: json };
      });
    }).finally(function () { clearTimeout(timer); });
  }

  // Busy state for a button: spinner and disabled at once; "still working" note after SLOW_MS.
  function busy(button, slowNote) {
    var label = button.querySelector(".button-label");
    var idle = label.textContent;
    button.disabled = true;
    button.classList.add("is-loading");
    button.setAttribute("aria-busy", "true");
    label.textContent = button.getAttribute("data-busy") || "Please wait…";
    var slow = setTimeout(function () { if (slowNote) slowNote.hidden = false; }, SLOW_MS);
    return function done() {
      clearTimeout(slow);
      if (slowNote) slowNote.hidden = true;
      button.disabled = false;
      button.classList.remove("is-loading");
      button.removeAttribute("aria-busy");
      label.textContent = idle;
    };
  }

  function setStorm(on) {
    var banner = $("storm-banner");
    if (banner) banner.hidden = !on;
  }

  function panel(tone, heading, paragraphs) {
    return el("div", { className: "panel " + tone }, [el("h2", { text: heading })].concat(paragraphs));
  }

  // ---- Report an outage --------------------------------------------------

  function initReport() {
    var form = $("report-form");
    var result = $("report-result");
    var submit = $("report-submit");
    submit.setAttribute("data-busy", "Sending your report…");
    form.noValidate = true; // our messages instead of the browser's; the server checks everything again

    function fieldError(name, message) {
      ["zip", "address", "phone", "notes"].forEach(function (f) {
        var input = $(f), err = $(f + "-error");
        var on = f === name;
        input.setAttribute("aria-invalid", on ? "true" : "false");
        err.hidden = !on;
        err.textContent = on ? message : "";
      });
      if (name) $(name).focus();
    }

    function show(node) {
      clear(result);
      result.appendChild(node);
      result.hidden = false;
      result.focus();
    }

    form.addEventListener("submit", function (ev) {
      ev.preventDefault();
      var data = {};
      ["zip", "address", "phone", "notes"].forEach(function (f) { data[f] = $(f).value; });
      if (!data.zip.trim()) { fieldError("zip", "Enter the ZIP code where the power is out."); return; }
      fieldError(null);
      result.hidden = true;
      var done = busy(submit, $("slow-note"));
      request("POST", "/api/reports", data).then(function (r) {
        done();
        if (r.status === 201) {
          form.reset();
          var link = el("a", { className: "button", href: "/status?ticket=" + encodeURIComponent(r.body.id), text: "Track this outage" });
          show(panel("ok", "Report received", [
            el("p", {}, ["Your ticket number is ", el("strong", { className: "ticket-id", id: "ticket-id", text: r.body.id }), "."]),
            el("p", { text: "We'll confirm the outage in " + r.body.area.name + " and post an estimated restoration time shortly." }),
            el("p", {}, [link])
          ]));
        } else if (r.status === 400 && r.body.field) {
          fieldError(r.body.field, r.body.error);
        } else if (r.status >= 400 && r.status < 500) {
          show(panel("error", "Please check your report", [el("p", { text: r.body.error || "Something in the form needs fixing." })]));
        } else {
          show(panel("error", "Report not sent", [el("p", { text: UNAVAILABLE })]));
        }
      }, function () {
        done();
        show(panel("error", "Report not sent", [el("p", { text: UNAVAILABLE })]));
      });
    });
  }

  // ---- Check status -------------------------------------------------------

  var STATUS_TEXT = {
    reported: "We've received the report and are confirming the outage. An estimated restoration time will appear here shortly.",
    confirmed: "We've confirmed the outage and are assigning a crew.",
    crew_assigned: "A crew is assigned and working to restore power.",
    restored: "Power has been restored. If you're still without power, please report it again."
  };

  function ticketView(t) {
    var steps = el("ol", { className: "steps", "aria-label": "Progress" });
    var reached = true;
    t.timeline.forEach(function (s) {
      var current = s.status === t.status;
      var cls = current ? (t.status === "restored" ? "done" : "current") : (reached ? "done" : "");
      steps.appendChild(el("li", { className: cls, "aria-current": current ? "step" : null }, [
        s.label, el("span", { className: "when", text: s.at ? time(s.at) : "—" })
      ]));
      if (current) reached = false;
    });

    var facts = el("dl", { className: "facts" });
    function fact(label, value, extra) {
      facts.appendChild(el("div", {}, [el("dt", { text: label }), el("dd", {}, [value, extra])]));
    }
    if (t.status === "restored") fact("Restored", time(t.timeline[3].at));
    else fact("Estimated restoration", t.etr ? time(t.etr) : "Being assessed",
        t.etrRevisions > 0 ? el("span", { className: "revised", text: " (revised)" }) : null);
    fact("Customers affected", t.customersAffected == null ? "Being assessed" : "About " + number(t.customersAffected));
    fact("Last update", time(t.updatedAt));

    var where = "ZIP " + t.zip + " · " + t.area.name + (t.address ? " · " + t.address : "");
    return el("div", {}, [
      el("div", { className: "ticket-head" }, [
        el("h2", { text: "Ticket " + t.id }),
        el("span", { className: "badge " + t.status, text: t.statusLabel })
      ]),
      el("p", { className: "where", text: where }),
      steps, facts,
      el("p", { text: STATUS_TEXT[t.status] })
    ]);
  }

  function zipView(z) {
    var lines = [];
    if (z.openOutages === 0) {
      lines.push(el("p", {}, ["We don't know of any outages in this ZIP code. If your power is out, ",
        el("a", { href: "/", text: "report it" }), "."]));
    } else {
      lines.push(el("p", { text: number(z.openOutages) + (z.openOutages === 1 ? " open outage" : " open outages") +
          ", affecting about " + number(z.customersAffected) + " customers." }));
      if (z.crewsAssigned > 0) lines.push(el("p", { text: "Crews are working on " + number(z.crewsAssigned) + " of them." }));
      if (z.awaitingConfirmation > 0) lines.push(el("p", { text: number(z.awaitingConfirmation) + " new reports are being confirmed." }));
      if (z.nextEtr) {
        var range = z.latestEtr && z.latestEtr !== z.nextEtr ? "between " + time(z.nextEtr) + " and " + time(z.latestEtr) : "by " + time(z.nextEtr);
        lines.push(el("p", {}, [el("strong", { text: "Estimated restoration: " + range + "." })]));
      }
    }
    return el("div", {}, [
      el("div", { className: "ticket-head" }, [el("h2", { text: "ZIP " + z.zip + " · " + z.area.name })])
    ].concat(lines));
  }

  function initStatus() {
    var result = $("status-result");
    var refresh = null;
    var forms = { ticket: $("ticket-form"), zip: $("zip-form") };
    forms.ticket.querySelector("button").setAttribute("data-busy", "Checking…");
    forms.zip.querySelector("button").setAttribute("data-busy", "Checking…");

    function lookup(kind, value, quiet) {
      var url = kind === "ticket" ? "/api/reports/" + encodeURIComponent(value.trim()) : "/api/zip/" + encodeURIComponent(value.trim());
      var done = quiet ? function () {} : busy(forms[kind].querySelector("button"), $("slow-note"));
      if (!quiet) {
        clear(result);
        result.appendChild(el("p", { className: "skeleton" }, [el("span", { className: "spinner dark", "aria-hidden": "true" }), "Looking up the latest information…"]));
        result.hidden = false;
      }
      return request("GET", url).then(function (r) {
        done();
        if (r.status === 200) {
          if (kind === "zip") setStorm(r.body.stormMode);
          clear(result);
          result.appendChild(kind === "ticket" ? ticketView(r.body) : zipView(r.body));
          result.appendChild(el("p", { className: "updated", text: "Updated " + new Date().toLocaleTimeString() + ". This page refreshes every 30 seconds." }));
          schedule(kind, value);
        } else if (r.status >= 400 && r.status < 500) {
          clear(result);
          result.appendChild(panel("error", "We couldn't find that", [el("p", { text: r.body.error || "Check what you entered and try again." })]));
        } else if (!quiet) {
          clear(result);
          result.appendChild(panel("error", "Status unavailable", [el("p", { text: LOOKUP_UNAVAILABLE })]));
        }
        if (!quiet) result.focus();
      }, function () {
        done();
        if (!quiet) {
          clear(result);
          result.appendChild(panel("error", "Status unavailable", [el("p", { text: LOOKUP_UNAVAILABLE })]));
          result.focus();
        }
      });
    }

    function schedule(kind, value) {
      clearTimeout(refresh);
      refresh = setTimeout(function () { lookup(kind, value, true); }, 30000);
    }

    Object.keys(forms).forEach(function (kind) {
      forms[kind].addEventListener("submit", function (ev) {
        ev.preventDefault();
        var input = forms[kind].querySelector("input");
        if (!input.value.trim()) { input.focus(); return; }
        clearTimeout(refresh);
        history.replaceState(null, "", "/status?" + (kind === "ticket" ? "ticket=" : "zip=") + encodeURIComponent(input.value.trim()));
        lookup(kind, input.value);
      });
    });

    var params = new URLSearchParams(location.search);
    if (params.get("ticket")) { $("ticket").value = params.get("ticket"); lookup("ticket", params.get("ticket")); }
    else if (params.get("zip")) { $("lookup-zip").value = params.get("zip"); lookup("zip", params.get("zip")); }
  }

  // ---- Service areas ------------------------------------------------------

  function initAreas() {
    var rows = $("area-rows");
    var notice = $("areas-notice");
    var lastGood = null;

    function cell(label, content, cls) {
      var td = el("td", { "data-label": label, className: cls || "" });
      td.appendChild(typeof content === "string" ? document.createTextNode(content) : content);
      return td;
    }

    function bar(pct) {
      // 2 % of an area's customers out fills the bar: a big storm, at utility scale.
      var fill = el("span", { className: "bar-fill" + (pct >= 1 ? " high" : "") });
      fill.style.width = Math.min(100, Math.max(pct > 0 ? 3 : 0, pct * 50)) + "%";
      return el("span", { className: "bar", title: pct + "% of customers" }, [
        el("span", { className: "bar-track" }, [fill]),
        el("span", { className: "bar-text", text: pct.toFixed(2) + "%" })
      ]);
    }

    function render(data) {
      setStorm(data.stormMode);
      $("t-customers").textContent = number(data.totals.customersAffected);
      $("t-open").textContent = number(data.totals.openOutages);
      $("t-crews").textContent = number(data.totals.crewsAssigned);
      $("t-pct").textContent = data.totals.pctAffected.toFixed(2) + "%";
      clear(rows);
      data.areas.forEach(function (a) {
        var tr = el("tr");
        tr.appendChild(cell("Service area", el("strong", { text: a.name })));
        tr.appendChild(cell("ZIP codes", a.zips, "nowrap"));
        tr.appendChild(cell("Customers served", number(a.customersServed), "num"));
        tr.appendChild(cell("Open outages", number(a.openOutages), "num"));
        tr.appendChild(cell("Customers out", number(a.customersAffected), "num"));
        tr.appendChild(cell("Share out", bar(a.pctAffected)));
        tr.appendChild(cell("Crews", number(a.crewsAssigned), "num"));
        tr.appendChild(cell("Next restoration", a.nextEtr ? time(a.nextEtr) : "—"));
        rows.appendChild(tr);
      });
      $("areas-updated").textContent = "Updated " + new Date(data.asOf).toLocaleTimeString() + ".";
    }

    function load() {
      request("GET", "/api/areas").then(function (r) {
        if (r.status !== 200) throw new Error("HTTP " + r.status);
        lastGood = new Date();
        notice.hidden = true;
        render(r.body);
      }).catch(function () {
        notice.textContent = LOOKUP_UNAVAILABLE + (lastGood ? " Showing information from " + lastGood.toLocaleTimeString() + "." : "");
        notice.hidden = false;
        if (!lastGood) {
          clear(rows);
          rows.appendChild(el("tr", {}, [el("td", { colspan: "8", className: "loading-row", text: "No outage information to show yet." })]));
        }
      }).finally(function () { setTimeout(load, 15000); });
    }
    load();
  }

  document.addEventListener("DOMContentLoaded", function () {
    var page = document.body.getAttribute("data-page");
    if (page === "report") initReport();
    else if (page === "status") initStatus();
    else if (page === "areas") initAreas();
  });
})();
