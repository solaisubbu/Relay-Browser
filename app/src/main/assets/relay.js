(function () {
  window.__relaySnap = function () {
    document.querySelectorAll('[data-relay-id]').forEach(function (e) { e.removeAttribute('data-relay-id'); });
    var SEL = 'a[href],button,input:not([type=hidden]),textarea,select,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=option],[role=checkbox],[role=combobox],[contenteditable=true],[onclick]';
    function vis(e) { var r = e.getBoundingClientRect(); var s = getComputedStyle(e); return r.width > 0 && r.height > 0 && s.visibility !== 'hidden' && s.display !== 'none'; }
    function lab(e) {
      var t = (e.innerText || '').trim();
      if (!t) t = e.getAttribute('aria-label') || e.placeholder || e.title || (e.type === 'password' ? '' : e.value) || e.name || '';
      if (e.id) { var l = document.querySelector('label[for="' + CSS.escape(e.id) + '"]'); if (l) t = l.innerText.trim() + (t ? ' | ' + t : ''); }
      return String(t).replace(/\s+/g, ' ').slice(0, 80);
    }
    var items = [], n = 0, all = document.querySelectorAll(SEL);
    for (var k = 0; k < all.length && n < 250; k++) {
      var e = all[k];
      if (!vis(e)) continue;
      e.setAttribute('data-relay-id', n);
      var r = e.getBoundingClientRect();
      var d = '[' + n + '] ' + e.tagName.toLowerCase();
      if (e.tagName === 'INPUT') d += ' type=' + (e.type || 'text');
      d += ' "' + lab(e) + '"';
      if (e.tagName === 'INPUT' || e.tagName === 'TEXTAREA') {
        if (e.type === 'password') d += ' (password field)';
        else if (e.type === 'checkbox' || e.type === 'radio') d += e.checked ? ' checked' : ' unchecked';
        else if (e.value) d += ' value="' + String(e.value).slice(0, 40) + '"';
      }
      if (e.tagName === 'SELECT') d += ' options: ' + Array.prototype.slice.call(e.options, 0, 15).map(function (o) { return o.text.trim(); }).join(' / ');
      if (e.tagName === 'A') d += ' -> ' + (e.getAttribute('href') || '').slice(0, 60);
      if (r.bottom < 0 || r.top > innerHeight) d += ' (offscreen)';
      items.push(d); n++;
    }
    return {
      url: location.href, title: document.title, elements: items.join('\n'),
      text: (document.body ? document.body.innerText : '').replace(/\n{3,}/g, '\n\n').slice(0, 6000),
      scroll: Math.round(scrollY) + ' of ' + Math.max(0, Math.round(document.documentElement.scrollHeight - innerHeight))
    };
  };

  window.__relayAct = function (a) {
    var el = document.querySelector('[data-relay-id="' + a.id + '"]');
    if (!el) return { ok: false, msg: 'element ' + a.id + ' not found' };
    el.scrollIntoView({ block: 'center' });
    if (a.action === 'click') { el.click(); return { ok: true, msg: 'clicked' }; }
    if (a.action === 'type') {
      if (el.type === 'password') return { ok: false, msg: 'password field: ask the user to type it' };
      el.focus();
      if (el.isContentEditable) el.innerText = a.text;
      else {
        var p = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        Object.getOwnPropertyDescriptor(p, 'value').set.call(el, a.text);
      }
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return { ok: true, msg: 'typed' };
    }
    if (a.action === 'select') {
      var want = String(a.text || '').toLowerCase(), opts = Array.prototype.slice.call(el.options || []);
      var o = opts.find(function (o) { return o.text.trim().toLowerCase() === want; }) || opts.find(function (o) { return o.text.toLowerCase().indexOf(want) >= 0; });
      if (!o) return { ok: false, msg: 'no option "' + a.text + '"' };
      el.value = o.value; el.dispatchEvent(new Event('change', { bubbles: true }));
      return { ok: true, msg: 'selected ' + o.text.trim() };
    }
    return { ok: false, msg: 'unknown action' };
  };
})();
