/* Ask, drawn by the home page. The words and the AI come from the on-TV app (TVOS.askRun); this file is only the look and the remote.
   Opened by the Ask quick button or the remote's microphone button (the launcher calls AskUI.open(true) to listen at once). */
(function () {
  var TV = window.TVOS || null;
  var t = function (k, v) { return window.I18N ? window.I18N.t(k, v) : k; };
  var root, thread, cards, input, bar, hintEl, subEl;
  var isOpen = false, listening = false, busy = false, seq = 0, activeReq = 0, pos = { r: 1, c: 1 };
  var rows = [[], []];   // row 0: suggestion cards, row 1: mic, field, send

  function available() { return !!(TV && TV.askRun && TV.canOpenAsk && TV.canOpenAsk()); }
  function el(tag, cls, html) { var e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; }
  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function clean(s) { return String(s || "").replace(/\*\*/g, "").replace(/^[-•*]\s+/gm, "").trim(); }

  var MIC = '<svg viewBox="0 0 24 24"><rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/></svg>';

  function build() {
    if (root) return;
    root = el("div", "ask"); root.id = "ask"; root.setAttribute("aria-hidden", "true");
    root.innerHTML = '<div class="ask-scrim"></div><div class="ask-panel">' +
      '<div class="ask-head"><span class="ask-title"></span><span class="ask-sub" id="ask-sub"></span></div>' +
      '<div class="ask-thread" id="ask-thread" aria-live="polite"></div><div class="ask-cards" id="ask-cards"></div>' +
      '<div class="ask-bar"><button class="ask-mic" id="ask-mic" aria-label="Speak">' + MIC + '</button>' +
      '<div class="ask-field" id="ask-field"><input id="ask-input" type="text" autocomplete="off" autocorrect="off" spellcheck="false" enterkeyhint="send"></div>' +
      '<button class="ask-send" id="ask-send"></button></div>' +
      '<div class="ask-hint"><span class="ask-wave"><i></i><i></i><i></i><i></i><i></i></span><span id="ask-hint"></span></div></div>';
    document.body.appendChild(root);
    thread = root.querySelector("#ask-thread"); cards = root.querySelector("#ask-cards");
    input = root.querySelector("#ask-input"); hintEl = root.querySelector("#ask-hint"); subEl = root.querySelector("#ask-sub");
    root.querySelector(".ask-title").textContent = t("ask_title");
    root.querySelector("#ask-send").textContent = t("ask_send");
    input.placeholder = t("ask_placeholder");
    rows[1] = [root.querySelector("#ask-mic"), root.querySelector("#ask-field"), root.querySelector("#ask-send")];
    root.querySelector("#ask-mic").onclick = function () { listen(); };
    root.querySelector("#ask-send").onclick = function () { send(); };
    input.addEventListener("keydown", function (e) { if (e.key === "Enter") { e.preventDefault(); send(); } });
  }

  function paint() {
    [].forEach.call(root.querySelectorAll(".focus"), function (n) { n.classList.remove("focus"); });
    var r = rows[pos.r]; if (!r || !r.length) { pos.r = 1; r = rows[1]; }
    pos.c = Math.max(0, Math.min(pos.c, r.length - 1));
    var f = r[pos.c]; if (f) f.classList.add("focus");
    if (pos.r === 1 && pos.c === 1) { try { input.focus(); } catch (e) {} } else { try { input.blur(); } catch (e) {} }
  }

  function setHint(msg) { hintEl.textContent = msg || ""; }

  function bubble(cls, text) {
    var b = el("div", "ask-msg " + cls); b.textContent = text; thread.appendChild(b);
    while (thread.children.length > 4) thread.removeChild(thread.firstChild);
    return b;
  }

  function open(listenNow) {
    if (!available()) { if (TV && TV.openAsk) TV.openAsk(); return; }
    build(); isOpen = true;
    root.classList.add("on"); root.setAttribute("aria-hidden", "false");
    thread.innerHTML = ""; cards.innerHTML = ""; rows[0] = []; busy = false; listening = false; root.classList.remove("listening");
    input.value = ""; pos = { r: 1, c: 0 };
    subEl.textContent = t("ask_sub");
    setHint(TV.askListen ? t("ask_hint_mic") : t("ask_hint_type"));
    paint();
    if (listenNow) setTimeout(listen, 450);
  }

  function close() {
    if (!isOpen) return;
    if (listening) { try { TV.askStopListening && TV.askStopListening(); } catch (e) {} }
    isOpen = false; listening = false; activeReq = 0;
    root.classList.remove("on", "listening"); root.setAttribute("aria-hidden", "true");
    try { input.blur(); } catch (e) {}
  }

  function listen() {
    if (busy || listening) return;
    var ok = false; try { ok = TV.askListen && TV.askListen(++seq); } catch (e) {}
    if (!ok) { setHint(t("ask_hint_type")); pos = { r: 1, c: 1 }; paint(); return; }
    listening = true; root.classList.add("listening"); setHint(t("ask_listening"));
  }

  function send() {
    var text = (input.value || "").trim();
    if (!text || busy) return;
    busy = true; input.value = ""; try { input.blur(); } catch (e) {}
    cards.innerHTML = ""; rows[0] = []; setHint("");
    bubble("me", text);
    var pending = el("div", "ask-msg ai"); pending.innerHTML = '<span class="ask-dots"><i></i><i></i><i></i></span>'; thread.appendChild(pending);
    pending.id = "ask-pending";
    activeReq = ++seq;
    try { TV.askRun(activeReq, text); } catch (e) { result(activeReq, "", String(e && e.message || e)); }
  }

  function dropPending() { var p = root && root.querySelector("#ask-pending"); if (p && p.parentNode) p.parentNode.removeChild(p); }

  function result(id, json, error) {
    if (!isOpen || id !== activeReq) return;
    busy = false; dropPending();
    if (error) { bubble("ai err", error); pos = { r: 1, c: 1 }; paint(); return; }
    var out = {}; try { out = JSON.parse(json); } catch (e) {}
    var opts = (out.options || []).filter(function (o) { return o && o.play; }).slice(0, 4);
    var reply = clean(out.reply) || (opts.length ? t("ask_ideas") : ""); if (reply) bubble("ai", reply);
    rows[0] = opts.map(function (o, i) {
      var parts = String(o.label || o.text || "").split(" · ");
      var c = el("div", "ask-card", '<div class="play">▶</div><b>' + esc(parts[0]) + "</b>" + (parts[1] ? "<span>" + esc(parts[1]) + "</span>" : ""));
      c.style.animationDelay = (i * 80 + 120) + "ms";
      c.onclick = function () { start(o); };
      cards.appendChild(c); return c;
    });
    pos = rows[0].length ? { r: 0, c: 0 } : { r: 1, c: 1 };
    setHint(rows[0].length ? t("ask_hint_cards") : "");
    paint();
  }

  function start(o) {
    if (busy) return; busy = true; setHint(t("ask_starting"));
    try { TV.askPlay(JSON.stringify(o.play)); } catch (e) { played(String(e && e.message || e)); }
  }

  function played(err) {
    busy = false;
    if (err) { setHint(""); bubble("ai err", err); return; }
    setTimeout(close, 500);
  }

  // words as they are spoken, and the loudness (0 to about 12), which drives the wave
  function partial(text) { if (isOpen && listening) input.value = text || ""; }
  function level(v) {
    if (!isOpen || !listening) return;
    var n = Math.max(0.15, Math.min(1, (parseFloat(v) + 2) / 12));
    [].forEach.call(root.querySelectorAll(".ask-wave i"), function (b, i) { b.style.height = Math.round((0.3 + n * (0.6 + 0.4 * Math.sin(i * 1.7 + Date.now() / 90))) * 1.5 * 16) + "px"; b.style.animation = "none"; });
  }

  function heard(id, text) {
    if (!isOpen) return;
    listening = false; root.classList.remove("listening");
    [].forEach.call(root.querySelectorAll(".ask-wave i"), function (b) { b.style.height = ""; b.style.animation = ""; });
    text = (text || "").trim();
    if (!text) { setHint(t("ask_nothing_heard")); pos = { r: 1, c: 0 }; paint(); return; }
    input.value = text; send();
  }

  function key(e) {
    if (!isOpen) return;
    var k = e.key, code = e.keyCode;
    var typing = document.activeElement === input;
    if (typing && (k === "ArrowLeft" || k === "ArrowRight" || k === "Backspace" || (k && k.length === 1))) return;   // typing: the field keeps its keys
    var handled = true;
    if (k === "ArrowLeft") pos.c = Math.max(0, pos.c - 1);
    else if (k === "ArrowRight") pos.c = Math.min(rows[pos.r].length - 1, pos.c + 1);
    else if (k === "ArrowUp") { if (pos.r === 1 && rows[0].length) { pos.r = 0; pos.c = Math.min(pos.c, rows[0].length - 1); } else handled = true; }
    else if (k === "ArrowDown") { if (pos.r === 0) { pos.r = 1; pos.c = 1; } }
    else if (k === "Enter" || code === 13 || k === " ") {
      var f = rows[pos.r][pos.c];
      if (pos.r === 1 && pos.c === 1) { if (input.value.trim()) send(); else try { input.focus(); } catch (x) {} }
      else if (f && f.click) f.click();
    } else if (k === "Escape" || k === "BrowserBack" || code === 4) { close(); }
    else handled = false;
    if (handled) { e.preventDefault(); e.stopImmediatePropagation(); if (isOpen) paint(); }
  }
  window.addEventListener("keydown", key, true);

  // the native BACK and Home presses reach the page through tv.back and tv.home
  function wrap() {
    if (!window.tv || window.tv.__askWrapped) return;
    window.tv.__askWrapped = true;
    ["back", "home"].forEach(function (n) { var o = window.tv[n]; window.tv[n] = function () { if (isOpen) { close(); return; } return o && o.apply(this, arguments); }; });
  }
  wrap(); setTimeout(wrap, 0); window.addEventListener("load", wrap);

  window.AskUI = { open: open, close: close, available: available, result: result, played: played, heard: heard, partial: partial, level: level, isOpen: function () { return isOpen; } };
})();
