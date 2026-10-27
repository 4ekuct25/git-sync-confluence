/* Git-Sync: autocomplete for the macro dialog fields (connection, path, ref).
 *
 * Served on every page (atl.general) together with comments.js. No-op unless
 * the legacy macro browser dialog (#macro-browser-dialog) opens for the
 * git-sync macro — the dialog is created on demand by the editor, so the
 * script watches the DOM for it and wires the three text inputs:
 *
 *   #macro-param-connection  ->  GET /rest/gitsync/1.0/suggest/connection
 *   #macro-param-ref         ->  GET /rest/gitsync/1.0/suggest/ref
 *   #macro-param-path        ->  GET /rest/gitsync/1.0/suggest/path
 *
 * The dropdown is a single fixed-position list appended to <body> (the
 * dialog content area has fixed-height scroll panes that would clip an
 * in-flow list). One list at a time; it closes on blur, outside click,
 * scroll, resize and Escape.
 */
(function () {
  'use strict';

  if (window.__gitsyncSuggestLoaded) {
    return;
  }
  window.__gitsyncSuggestLoaded = true;

  var REST = '/rest/gitsync/1.0/suggest';
  var DEBOUNCE_MS = 220;
  var MAX_ITEMS = 20;
  var FIELDS = { connection: 1, path: 1, ref: 1 };

  // Single active dropdown: {input, list, items, active}
  var state = null;

  function fieldVal(id) {
    var e = document.getElementById(id);
    return e ? e.value.trim() : '';
  }

  function hide() {
    if (!state) {
      return;
    }
    if (state.list && state.list.parentNode) {
      state.list.parentNode.removeChild(state.list);
    }
    state = null;
  }

  document.addEventListener('mousedown', function (e) {
    if (!state) {
      return;
    }
    if (state.list && state.list.contains(e.target)) {
      return;
    }
    if (e.target === state.input) {
      return;
    }
    hide();
  }, true);
  window.addEventListener('scroll', function (e) {
    // Only a window/document scroll moves the fixed-positioned input out
    // from under the list. Inner panes (the dialog's Preview, macro list)
    // must not close it.
    if (!e || !e.target || e.target === window || e.target === document) {
      hide();
    }
  }, true);
  window.addEventListener('resize', hide);

  function setActive(i) {
    if (!state || !state.list) {
      return;
    }
    state.active = i;
    var kids = state.list.children;
    for (var j = 0; j < kids.length; j++) {
      kids[j].className = 'gitsync-suggest-item' + (j === i ? ' gitsync-suggest-active' : '');
    }
    if (i >= 0 && kids[i] && kids[i].scrollIntoView) {
      kids[i].scrollIntoView({ block: 'nearest' });
    }
  }

  function pick(i) {
    if (!state || i < 0 || i >= state.items.length) {
      return;
    }
    var input = state.input;
    input.value = state.items[i];
    hide();
    input.dispatchEvent(new Event('change', { bubbles: true }));
  }

  function render(input, cands) {
    hide();
    if (!cands.length) {
      return;
    }
    var items = cands.slice(0, MAX_ITEMS);
    var list = document.createElement('div');
    list.className = 'gitsync-suggest';
    items.forEach(function (text, i) {
      var d = document.createElement('div');
      d.className = 'gitsync-suggest-item';
      d.textContent = text;
      d.addEventListener('mousedown', function (e) {
        e.preventDefault();
        pick(i);
      });
      d.addEventListener('mouseenter', function () {
        setActive(i);
      });
      list.appendChild(d);
    });
    state = { input: input, list: list, items: items, active: -1 };
    document.body.appendChild(list);
    var r = input.getBoundingClientRect();
    list.style.left = Math.max(0, r.left) + 'px';
    list.style.top = (r.bottom + 2) + 'px';
    list.style.width = Math.max(r.width, 240) + 'px';
  }

  function wire(input) {
    if (input.__gitsyncWired) {
      return;
    }
    input.__gitsyncWired = true;
    var field = input.id ? input.id.replace(/^macro-param-/, '') : '';
    if (!FIELDS[field]) {
      return;
    }
    var timer = null;

    input.addEventListener('input', function () {
      clearTimeout(timer);
      timer = setTimeout(function () {
        var q = input.value.trim();
        var url;
        if (field === 'connection') {
          url = REST + '/connection?q=' + encodeURIComponent(q);
        } else {
          var conn = fieldVal('macro-param-connection');
          if (!conn) {
            hide();
            return;
          }
          if (field === 'ref') {
            url = REST + '/ref?connection=' + encodeURIComponent(conn)
                + '&q=' + encodeURIComponent(q);
          } else {
            url = REST + '/path?connection=' + encodeURIComponent(conn)
                + '&ref=' + encodeURIComponent(fieldVal('macro-param-ref'))
                + '&q=' + encodeURIComponent(q);
          }
        }
        fetch(url, { credentials: 'same-origin' })
          .then(function (r) {
            if (!r.ok) {
              throw new Error('http ' + r.status);
            }
            return r.json();
          })
          .then(function (data) {
            var cands = [];
            if (Array.isArray(data)) {
              cands = data;
            } else if (field === 'ref') {
              cands = (data.branches || []).concat(data.tags || []);
            } else {
              cands = data.items || [];
            }
            var seen = {};
            var out = [];
            cands.forEach(function (c) {
              if (typeof c === 'string' && c && !seen[c]) {
                seen[c] = 1;
                out.push(c);
              }
            });
            render(input, out);
          })
          .catch(function () {
            hide();
          });
      }, DEBOUNCE_MS);
    });

    input.addEventListener('keydown', function (e) {
      if (!state || state.input !== input || !state.list) {
        return;
      }
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setActive((state.active + 1) % state.items.length);
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setActive((state.active - 1 + state.items.length) % state.items.length);
      } else if (e.key === 'Enter' && state.active >= 0) {
        e.preventDefault();
        pick(state.active);
      } else if (e.key === 'Escape') {
        hide();
      }
    });

    input.addEventListener('blur', function () {
      var s = state;
      setTimeout(function () {
        if (state === s) {
          hide();
        }
      }, 150);
    });
  }

  function maybeEnhance() {
    var dlg = document.getElementById('macro-browser-dialog');
    if (!dlg) {
      return;
    }
    var nameEl = dlg.querySelector('input.macro-name');
    if (!nameEl || nameEl.value !== 'git-sync') {
      return;
    }
    dlg.querySelectorAll('input.macro-param-input').forEach(wire);
  }

  function start() {
    var observer = new MutationObserver(maybeEnhance);
    observer.observe(document.body, { childList: true, subtree: true });
    maybeEnhance();
  }

  if (document.body) {
    start();
  } else {
    document.addEventListener('DOMContentLoaded', start);
  }
})();
