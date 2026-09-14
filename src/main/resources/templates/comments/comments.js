/**
 * Git-Sync line comments — frontend.
 *
 * Usage (injected by the git-sync macro after rendering):
 *   initGitSyncComments(pageId, renderedHtml);
 *
 * `renderedHtml` is the HTML string (or an HTMLElement) the macro produced.
 * Block elements — <p>, <li>, <h1>-<h6>, <td> — are indexed in document
 * order; that index is the line anchor. The function wraps the content in
 * a container, inserts a comment icon in the right margin of each block
 * that has comments, and wires up a thread popup with an add-comment form
 * and per-comment resolve/reopen buttons. All UI text is in Russian.
 * Anchoring is by TEXT, not position: if the anchor fragment was deleted
 * from the file, the comment is not lost — it is listed in a standalone
 * "Фрагмент не найден" block (FR09), and the stored lineAnchor is never
 * mutated server-side.
 */
(function (global) {
    'use strict';

    var REST_BASE = '/rest/gitsync/1.0/comments';

    var BLOCK_SELECTOR = 'p, li, h1, h2, h3, h4, h5, h6, td';

    var CSS =
        '.gs-comment-root{position:relative;padding-right:34px;}' +
        '.gs-comment-line{position:relative;}' +
        /* Table-cell lines: the icon sits INSIDE the cell (right edge of the
           cell, in its padding zone) — right:-30px would spill the icon onto
           the neighbouring column, because cells have no right gutter. The
           popup is anchored to the cell (td.gs-comment-cell) and opens just
           below it, extending right where there is room. The line reserves
           the icon's width, otherwise in a narrow cell the text runs under
           the absolutely-positioned icon. */
        '.gs-comment-line.gs-cell-line{' +
            'padding-right:22px;' +
        '}' +
        '.gs-comment-line.gs-cell-line .gs-comment-icon{' +
            'right:0;' +
        '}' +
        'td.gs-comment-cell{position:relative;}' +
        'td.gs-comment-cell .gs-thread-popup{' +
            'left:0;right:auto;top:100%;' +
        '}' +
        '.gs-comment-icon{' +
            'position:absolute;right:-30px;top:50%;transform:translateY(-50%);' +
            'cursor:pointer;font-size:14px;line-height:1;' +
            'padding:2px 1px;border-radius:4px;user-select:none;' +
            'opacity:.65;transition:opacity .15s,background .15s;' +
        '}' +
        '.gs-comment-icon:hover{opacity:1;background:rgba(0,0,0,.08);}' +
        '.gs-comment-icon.gs-resolved{opacity:.35;}' +
        '.gs-comment-icon .gs-count{' +
            'display:inline-block;min-width:12px;padding:0 2px;margin-left:1px;' +
            'font-size:10px;color:#fff;background:#e5484d;border-radius:8px;' +
            'vertical-align:super;line-height:12px;text-align:center;' +
        '}' +
        '.gs-thread-popup{' +
            'position:absolute;right:34px;z-index:1000;max-width:360px;width:340px;' +
            'background:#fff;border:1px solid #ccc;border-radius:6px;' +
            'box-shadow:0 4px 16px rgba(0,0,0,.25);font-size:13px;' +
        '}' +
        '.gs-thread-header{' +
            'padding:8px 12px;border-bottom:1px solid #eee;display:flex;' +
            'justify-content:space-between;align-items:center;' +
        '}' +
        '.gs-thread-header span{font-weight:600;color:#333;}' +
        '.gs-thread-close{cursor:pointer;border:none;background:none;font-size:15px;color:#777;padding:0 2px;}' +
        '.gs-thread-body{padding:8px 12px;max-height:260px;overflow-y:auto;}' +
        '.gs-thread-item{padding:6px 0;border-bottom:1px solid #f0f0f0;}' +
        '.gs-thread-item:last-child{border-bottom:none;}' +
        '.gs-thread-meta{color:#777;font-size:11px;}' +
        '.gs-thread-author{font-weight:600;color:#333;}' +
        '.gs-thread-text{white-space:pre-wrap;word-break:break-word;color:#222;margin-top:2px;}' +
        '.gs-thread-item.gs-is-resolved{opacity:.7;}' +
        '.gs-thread-item.gs-is-resolved .gs-thread-author::after{' +
            'content:" · решено";color:#2e8b57;font-weight:400;font-size:11px;' +
        '}' +
        '.gs-thread-quoted{' +
            'font-size:11px;color:#777;border-left:3px solid #ddd;' +
            'padding:2px 8px;margin:4px 0;white-space:nowrap;overflow:hidden;' +
            'text-overflow:ellipsis;max-width:320px;' +
        '}' +
        '.gs-thread-form{padding:8px 12px;border-top:1px solid #eee;}' +
        '.gs-thread-form textarea{' +
            'width:100%;box-sizing:border-box;min-height:52px;resize:vertical;' +
            'border:1px solid #ccc;border-radius:4px;padding:6px;font-size:13px;' +
        '}' +
        '.gs-thread-actions{margin-top:6px;display:flex;gap:6px;justify-content:flex-end;}' +
        '.gs-thread-actions button{' +
            'border:1px solid #ccc;border-radius:4px;padding:3px 10px;' +
            'font-size:12px;cursor:pointer;background:#f5f5f5;' +
        '}' +
        '.gs-thread-actions button:hover{background:#eaeaea;}' +
        '.gs-btn-primary{background:#1264a3!important;color:#fff!important;border-color:#1264a3!important;}' +
        '.gs-btn-primary:hover{background:#0e4f80!important;}' +
        '.gs-thread-item.gs-is-reply{margin-left:14px;border-left:2px solid #e0e0e0;padding-left:8px;}' +
        '.gs-reply-to{color:#888;font-size:11px;margin-top:1px;}' +
        '.gs-reply-hint{font-size:11px;color:#555;background:#f0f6ff;border:1px solid #cde0f7;' +
            'border-radius:4px;padding:3px 8px;margin:4px 0;display:none;}' +
        '.gs-thread-error{color:#e5484d;font-size:12px;padding:4px 0;display:none;}' +
        '.gs-orphan-block{' +
            'margin-top:16px;padding:10px 14px;border:1px dashed #e5484d;' +
            'border-radius:6px;background:#fff5f5;font-size:13px;' +
        '}' +
        '.gs-orphan-header{font-weight:600;color:#b00020;margin-bottom:6px;}' +
        '.gs-orphan-item{padding:6px 0;border-bottom:1px solid #f3d1d3;}' +
        '.gs-orphan-item:last-child{border-bottom:none;}' +
        '.gs-orphan-meta{color:#777;font-size:11px;}' +
        '.gs-orphan-quote{' +
            'font-size:11px;color:#999;font-style:italic;margin-top:2px;' +
            'white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:320px;' +
        '}';

    var cssInjected = false;
    var openPopup = null;

    function injectCss() {
        if (cssInjected || !global.document) return;
        var style = global.document.createElement('style');
        style.setAttribute('data-gs-comments', 'css');
        style.textContent = CSS;
        global.document.head.appendChild(style);
        cssInjected = true;
    }

    function csrfToken() {
        var m = global.document.cookie.match(/(?:^|;\s*)atlassian\.token=([^;]+)/);
        return m ? decodeURIComponent(m[1]) : '';
    }

    function restHeaders() {
        var h = { 'Content-Type': 'application/json', 'Accept': 'application/json' };
        var token = csrfToken();
        if (token) h['atlassian-token'] = token;
        return h;
    }

    function restRequest(method, path, payload) {
        return fetch(REST_BASE + path, {
            method: method,
            headers: restHeaders(),
            body: payload === undefined ? undefined : JSON.stringify(payload),
            credentials: 'same-origin'
        }).then(function (r) {
            if (r.status === 204) return null;
            return r.json().then(function (data) {
                if (!r.ok) {
                    var msg = (data && data.error) || ('HTTP ' + r.status);
                    throw new Error(msg);
                }
                return data;
            });
        });
    }

    /**
     * Git-native comment routing (FR09 git mode). The macro embeds
     * data-connection-id / data-source-path / data-ref on its wrapper div;
     * when present, the frontend passes them with every comment request
     * and the REST layer stores/reads comments as native GitLab commit
     * discussions instead of the local comments.json.
     */
    function srcFromEl(el) {
        var cid = el.getAttribute('data-connection-id');
        var p = el.getAttribute('data-source-path');
        var r = el.getAttribute('data-ref');
        if (!cid || !p) return null;
        return { connectionId: cid, path: p, ref: r || 'main' };
    }

    function srcToParams(src) {
        if (!src) return '';
        return '&connectionId=' + encodeURIComponent(src.connectionId)
            + '&path=' + encodeURIComponent(src.path)
            + '&ref=' + encodeURIComponent(src.ref);
    }

    function srcToPayload(src) {
        var out = {};
        if (src) {
            out.connectionId = src.connectionId;
            out.path = src.path;
            out.ref = src.ref;
        }
        return out;
    }

    function fetchComments(pageId, src) {
        return restRequest('GET', '?pageId=' + encodeURIComponent(pageId) + srcToParams(src));
    }

    function snippet(text, max) {
        if (!text) return '';
        text = text.replace(/\s+/g, ' ').trim();
        return text.length > max ? text.substring(0, max - 1) + '…' : text;
    }

    function plainTextOf(el) {
        return el ? (el.textContent || '').replace(/\s+/g, ' ').trim() : '';
    }

    function formatDate(ts) {
        try {
            return new Date(ts).toLocaleString('ru-RU', {
                day: '2-digit', month: '2-digit', year: 'numeric',
                hour: '2-digit', minute: '2-digit'
            });
        } catch (e) {
            return String(ts);
        }
    }

    function commentsForAnchor(comments, lineAnchor) {
        return comments
            .filter(function (c) { return c.lineAnchor === lineAnchor; })
            .sort(function (a, b) { return a.createdAt - b.createdAt; });
    }

    function openCountOf(comments, lineAnchor) {
        return comments
            .filter(function (c) { return c.lineAnchor === lineAnchor && !c.resolved; })
            .length;
    }

    // ---------------------------------------------------------------------
    // Requirement R9 / FR09 — a comment is anchored to its TEXT, not to a
    // block position. When the Git file is updated the same block may move
    // to a different index; reanchorComments() remaps each stored comment:
    //   (a) text intact — exact normalized match elsewhere (closest to the
    //       stored index wins among duplicates) -> reanchored to the moved
    //       block;
    //   (b) text changed but similarity >= 0.8 -> reanchored (the copy is
    //       flagged `reanchored`);
    //   (c) fragment deleted or fully rewritten (no exact match, no
    //       similarity >= 0.8, empty fragment text, or the anchored block
    //       no longer exists) -> ORPHAN: the comment is NOT pinned to the
    //       stored index; it is listed in the standalone "Фрагмент не
    //       найден" block of the page's comment area. The `orphan` flag is
    //       client-side only — the stored lineAnchor on the server is never
    //       mutated.
    // ---------------------------------------------------------------------

    function normText(s) {
        return (s || '').replace(/\s+/g, ' ').trim();
    }

    function levenshtein(a, b) {
        if (a === b) return 0;
        if (!a.length) return b.length;
        if (!b.length) return a.length;
        var prev = [], cur = [];
        for (var j = 0; j <= b.length; j++) prev[j] = j;
        for (var i = 1; i <= a.length; i++) {
            cur[0] = i;
            for (var k = 1; k <= b.length; k++) {
                var cost = (a.charAt(i - 1) === b.charAt(k - 1)) ? 0 : 1;
                cur[k] = Math.min(prev[k] + 1, cur[k - 1] + 1, prev[k - 1] + cost);
            }
            var tmp = prev; prev = cur; cur = tmp;
        }
        return prev[b.length];
    }

    function textSimilarity(a, b) {
        var na = normText(a), nb = normText(b);
        if (!na || !nb) return 0;
        if (na === nb) return 1;
        if (na.indexOf(nb) >= 0 || nb.indexOf(na) >= 0) return 0.9;
        var d = levenshtein(na, nb);
        var max = Math.max(na.length, nb.length);
        return 1 - d / max;
    }

    /**
     * @param {Array}  comments    stored comments (lineAnchor = position when written)
     * @param {string[]} blockTexts plain text of the current blocks, by index
     * @returns {Array} shallow copies: lineAnchor remapped to the matching
     *   block (branches a/b above), or the `orphan` flag set with
     *   lineAnchor = null (branch c — "фрагмент не найден") so that no
     *   block icon is attached to such a comment.
     */
    function reanchorComments(comments, blockTexts) {
        var SIMILARITY_THRESHOLD = 0.8;
        function markOrphan(copy) {
            copy.orphan = true;
            copy.lineAnchor = null;
            return copy;
        }
        return comments.map(function (c) {
            var copy = {};
            for (var k in c) if (Object.prototype.hasOwnProperty.call(c, k)) copy[k] = c[k];
            var stored = normText(c.lineText);
            // (c-1) no fragment text to match against, or the anchored block
            //       no longer exists (deleted — the index is out of range):
            //       "фрагмент не найден".
            if (!stored || c.lineAnchor < 0 || c.lineAnchor >= blockTexts.length) {
                return markOrphan(copy);
            }
            if (normText(blockTexts[c.lineAnchor]) === stored) return copy;
            // (a) exact normalized match elsewhere (closest to the stored
            //     index wins among duplicates).
            var exacts = [];
            var best = -1, bestScore = SIMILARITY_THRESHOLD;
            for (var j = 0; j < blockTexts.length; j++) {
                var nb = normText(blockTexts[j]);
                if (!nb) continue;
                if (nb === stored) {
                    exacts.push(j);
                    continue;
                }
                // (b) text changed, but still similar enough.
                var score = textSimilarity(c.lineText, blockTexts[j]);
                if (score > bestScore) { bestScore = score; best = j; }
            }
            if (exacts.length > 0) {
                var target = exacts[0];
                for (var e = 1; e < exacts.length; e++) {
                    if (Math.abs(exacts[e] - c.lineAnchor) < Math.abs(target - c.lineAnchor)) {
                        target = exacts[e];
                    }
                }
                copy.lineAnchor = target;
                return copy;
            }
            if (best >= 0) {
                copy.lineAnchor = best;
                copy.reanchored = true;
                return copy;
            }
            // (c-2) fragment deleted or fully rewritten: do NOT pin the
            //       comment to the stored index — it is listed in the
            //       "Фрагмент не найден" block (server data untouched).
            return markOrphan(copy);
        });
    }

    /**
     * Build the icon + thread popup for one line.
     *
     * @param {number} pageId
     * @param {object|null} src    git-native routing triple (or null)
     * @param {number} lineAnchor
     * @param {string} lineText   plain text of the block (for the quoted snippet)
     * @param {Array}  comments   all comments for the page (any anchor)
     * @returns {{icon: HTMLElement, popup: HTMLElement, wrapper: HTMLElement|null}}
     */
    function buildLine(pageId, src, lineAnchor, lineText, comments) {
        var anchorComments = commentsForAnchor(comments, lineAnchor);
        var openCount = openCountOf(comments, lineAnchor);
        var allResolved = anchorComments.length > 0 && openCount === 0;

        var icon = global.document.createElement('span');
        icon.className = 'gs-comment-icon' + (allResolved ? ' gs-resolved' : '');
        icon.title = anchorComments.length === 0 ? 'Добавить комментарий к строке'
                : (allResolved ? 'Все комментарии решены' : 'Комментарии к этой строке');
        icon.textContent = '💬';
        if (openCount > 0) {
            var count = global.document.createElement('span');
            count.className = 'gs-count';
            count.textContent = String(openCount);
            icon.appendChild(count);
        }

        var popup = global.document.createElement('div');
        popup.className = 'gs-thread-popup';
        popup.style.display = 'none';

        icon.addEventListener('click', function (e) {
            e.stopPropagation();
            togglePopup(popup, icon);
        });

        renderThread(popup, pageId, src, lineAnchor, lineText, comments);
        return { icon: icon, popup: popup };
    }

    function togglePopup(popup, icon) {
        if (popup.style.display === 'block') {
            closePopup(popup);
        } else {
            if (openPopup && openPopup !== popup) closePopup(openPopup);
            openPopup = popup;
            popup.style.display = 'block';
            // Keep the popup within the vertical bounds of its wrapper.
            // Cell lines are exempt: their popup is anchored to the <td>
            // and the CSS (top:100%) places it below the whole cell — an
            // inline top clamped to the content line would cover the cell.
            var line = icon.closest('.gs-comment-line');
            if (line && !line.classList.contains('gs-cell-line')) {
                var lineH = line.offsetHeight || 20;
                var maxTop = lineH - Math.min(popup.offsetHeight || 100, 400) - 8;
                var top = Math.max(2, Math.min(20, maxTop));
                popup.style.top = top + 'px';
            }
        }
    }

    function closePopup(popup) {
        if (!popup) return;
        popup.style.display = 'none';
        if (openPopup === popup) openPopup = null;
    }

    function renderThread(popup, pageId, src, lineAnchor, lineText, comments) {
        var doc = global.document;
        popup.innerHTML = '';

        var header = doc.createElement('div');
        header.className = 'gs-thread-header';
        var title = doc.createElement('span');
        title.textContent = 'Комментарии к строке';
        var closeBtn = doc.createElement('button');
        closeBtn.className = 'gs-thread-close';
        closeBtn.type = 'button';
        closeBtn.title = 'Закрыть';
        closeBtn.textContent = '×';
        closeBtn.addEventListener('click', function (e) {
            e.stopPropagation();
            closePopup(popup);
        });
        header.appendChild(title);
        header.appendChild(closeBtn);
        popup.appendChild(header);

        var quoted = doc.createElement('div');
        quoted.className = 'gs-thread-quoted';
        quoted.title = lineText || '';
        quoted.textContent = snippet(lineText, 120);
        if (quoted.textContent) popup.appendChild(quoted);

        var body = doc.createElement('div');
        body.className = 'gs-thread-body';
        popup.appendChild(body);

        var list = commentsForAnchor(comments, lineAnchor);

        if (list.length === 0) {
            var empty = doc.createElement('div');
            empty.style.color = '#777';
            empty.textContent = 'Пока нет комментариев';
            body.appendChild(empty);
        }

        list.forEach(function (c) {
            var parent = null;
            if (c.parentId) {
                for (var pi = 0; pi < comments.length; pi++) {
                    if (comments[pi].id === c.parentId) { parent = comments[pi]; break; }
                }
            }
            var item = doc.createElement('div');
            item.className = 'gs-thread-item'
                + (c.resolved ? ' gs-is-resolved' : '')
                + (c.parentId ? ' gs-is-reply' : '');
            item.setAttribute('data-id', c.id);

            if (c.parentId) {
                var replyTo = doc.createElement('div');
                replyTo.className = 'gs-reply-to';
                replyTo.textContent = '\u21a9 ' + (parent ? ('ответ на ' + (parent.author || '—')) : 'ответ в треде');
                item.appendChild(replyTo);
            }

            var meta = doc.createElement('div');
            meta.className = 'gs-thread-meta';
            var author = doc.createElement('span');
            author.className = 'gs-thread-author';
            author.textContent = c.author || '—';
            meta.appendChild(author);
            meta.appendChild(doc.createTextNode(' · ' + formatDate(c.createdAt)));
            item.appendChild(meta);

            var text = doc.createElement('div');
            text.className = 'gs-thread-text';
            text.textContent = c.body;
            item.appendChild(text);

            var replyBtn = doc.createElement('button');
            replyBtn.type = 'button';
            replyBtn.style.cssText =
                'margin-top:4px;margin-right:6px;font-size:11px;border:1px solid #ccc;border-radius:4px;' +
                'padding:2px 8px;background:#f5f5f5;cursor:pointer;';
            replyBtn.textContent = '\u21a9 Ответить';
            replyBtn.addEventListener('click', function () {
                popup.__gsReplyTo = { id: c.id, author: c.author || '—' };
                var hint = popup.querySelector('.gs-reply-hint');
                if (hint) {
                    hint.textContent = 'Ответ для ' + (c.author || '—') + ' (сброс — «Отмена»)';
                    hint.style.display = 'block';
                }
            });
            item.appendChild(replyBtn);

            var deleteBtn = doc.createElement('button');
            deleteBtn.type = 'button';
            deleteBtn.style.cssText =
                'margin-top:4px;margin-right:6px;font-size:11px;border:1px solid #ccc;border-radius:4px;' +
                'padding:2px 8px;background:#f5f5f5;color:#b00020;cursor:pointer;';
            deleteBtn.textContent = 'Удалить';
            deleteBtn.addEventListener('click', function () {
                if (confirm('Удалить комментарий?')) {
                    deleteComment(pageId, src, c.id, popup, lineAnchor, lineText);
                }
            });
            item.appendChild(deleteBtn);

            var actionBtn = doc.createElement('button');
            actionBtn.type = 'button';
            actionBtn.style.cssText =
                'margin-top:4px;font-size:11px;border:1px solid #ccc;border-radius:4px;' +
                'padding:2px 8px;background:#f5f5f5;cursor:pointer;';
            if (c.resolved) {
                actionBtn.textContent = 'Открыть снова';
                actionBtn.addEventListener('click', function () {
                    resolveComment(pageId, src, c.id, false, popup, lineAnchor, lineText);
                });
            } else {
                actionBtn.textContent = 'Решить';
                actionBtn.addEventListener('click', function () {
                    resolveComment(pageId, src, c.id, true, popup, lineAnchor, lineText);
                });
            }
            item.appendChild(actionBtn);
            body.appendChild(item);
        });

        var form = doc.createElement('div');
        form.className = 'gs-thread-form';

        var replyHint = doc.createElement('div');
        replyHint.className = 'gs-reply-hint';
        form.appendChild(replyHint);

        var textarea = doc.createElement('textarea');
        textarea.placeholder = 'Написать комментарий…';
        form.appendChild(textarea);

        var err = doc.createElement('div');
        err.className = 'gs-thread-error';
        form.appendChild(err);

        var actions = doc.createElement('div');
        actions.className = 'gs-thread-actions';

        var cancelBtn = doc.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.textContent = 'Отмена';
        cancelBtn.addEventListener('click', function () {
            textarea.value = '';
            err.style.display = 'none';
            popup.__gsReplyTo = null;
            replyHint.style.display = 'none';
        });

        var submitBtn = doc.createElement('button');
        submitBtn.type = 'button';
        submitBtn.className = 'gs-btn-primary';
        submitBtn.textContent = 'Добавить комментарий';
        submitBtn.addEventListener('click', function () {
            addComment(pageId, src, lineAnchor, lineText, textarea.value, popup, lineAnchor, lineText);
        });

        textarea.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                e.preventDefault();
                submitBtn.click();
            }
        });

        actions.appendChild(cancelBtn);
        actions.appendChild(submitBtn);
        form.appendChild(actions);
        popup.appendChild(form);
    }

    function addComment(pageId, src, lineAnchor, lineText, text, popup, anchor, quotedText) {
        text = (text || '').trim();
        if (!text) return;
        var form = popup.querySelector('.gs-thread-form');
        var err = form ? form.querySelector('.gs-thread-error') : null;
        if (err) { err.style.display = 'none'; err.textContent = ''; }

        var payload = srcToPayload(src);
        payload.pageId = pageId;
        payload.lineAnchor = lineAnchor;
        payload.lineText = quotedText || lineText || '';
        payload.body = text;
        payload.parentId = (popup && popup.__gsReplyTo) ? popup.__gsReplyTo.id : null;

        restRequest('POST', '', payload).then(function () {
            return fetchComments(pageId, src);
        }).then(function (fresh) {
            if (popup) popup.__gsReplyTo = null;
            if (form) form.querySelector('textarea').value = '';
            rebindThread(popup, pageId, src, anchor, quotedText, fresh);
            refreshIconState(popup, anchor, fresh);
        }).catch(function (e) {
            if (err) {
                err.textContent = 'Не удалось добавить комментарий: ' + e.message;
                err.style.display = 'block';
            }
        });
    }

    function resolveComment(pageId, src, commentId, resolved, popup, anchor, quotedText) {
        var payload = srcToPayload(src);
        payload.resolved = resolved;
        restRequest('PUT', '/' + encodeURIComponent(commentId), payload)
            .then(function () {
                return fetchComments(pageId, src);
            })
            .then(function (fresh) {
                rebindThread(popup, pageId, src, anchor, quotedText, fresh);
                refreshIconState(popup, anchor, fresh);
            })
            .catch(function (e) {
                var err = popup.querySelector('.gs-thread-error');
                if (err) {
                    err.textContent = 'Не удалось обновить комментарий: ' + e.message;
                    err.style.display = 'block';
                }
            });
    }

    function deleteComment(pageId, src, commentId, popup, anchor, quotedText) {
        var payload = srcToPayload(src);
        payload.pageId = pageId;
        restRequest('DELETE', '/' + encodeURIComponent(commentId), payload)
            .then(function () {
                return fetchComments(pageId, src);
            })
            .then(function (fresh) {
                rebindThread(popup, pageId, src, anchor, quotedText, fresh);
                refreshIconState(popup, anchor, fresh);
            })
            .catch(function (e) {
                var err = popup.querySelector('.gs-thread-error');
                if (err) {
                    err.textContent = 'Не удалось удалить комментарий: ' + e.message;
                    err.style.display = 'block';
                }
            });
    }

    /** Re-render an open popup's thread with fresh data, keeping its position. */
    function rebindThread(popup, pageId, src, lineAnchor, lineText, fresh) {
        var savedTop = popup.style.top;
        renderThread(popup, pageId, src, lineAnchor, lineText, fresh);
        if (savedTop) popup.style.top = savedTop;
    }

    /** Refresh the icon (count/opacity) attached to this popup's line. */
    function refreshIconState(popup, lineAnchor, fresh) {
        var wrapper = popup.closest('.gs-comment-line');
        if (!wrapper) return;
        var icon = wrapper.querySelector(':scope > .gs-comment-icon');
        if (!icon) return;

        var count = openCountOf(fresh, lineAnchor);
        var anyComments = fresh.some(function (c) { return c.lineAnchor === lineAnchor; });
        var allResolved = anyComments && count === 0;

        icon.className = 'gs-comment-icon' + (allResolved ? ' gs-resolved' : '');
        var oldCount = icon.querySelector('.gs-count');
        if (oldCount) oldCount.remove();
        if (count > 0) {
            var badge = global.document.createElement('span');
            badge.className = 'gs-count';
            badge.textContent = String(count);
            icon.appendChild(badge);
        }
        icon.title = allResolved ? 'Все комментарии решены' : 'Комментарии к этой строке';
    }

    /**
     * FR09: the standalone "Фрагмент не найден" block — the list of comments
     * whose anchor fragment was deleted from (or fully rewritten in) the
     * file. Rendered in the page's comment area, not pinned to any document
     * block: each entry shows the comment's text, author and time (plus the
     * stored fragment text as a quote for context).
     *
     * @param {Array} orphans  reanchored comment copies flagged `orphan`
     * @returns {HTMLElement}
     */
    function buildOrphanBlock(orphans) {
        var doc = global.document;
        var block = doc.createElement('div');
        block.className = 'gs-orphan-block';
        block.setAttribute('data-gs-orphan-block', '1');

        var header = doc.createElement('div');
        header.className = 'gs-orphan-header';
        header.textContent = 'Фрагмент не найден';
        block.appendChild(header);

        orphans
            .slice()
            .sort(function (a, b) { return a.createdAt - b.createdAt; })
            .forEach(function (c) {
                var item = doc.createElement('div');
                item.className = 'gs-orphan-item';
                if (c.id != null) item.setAttribute('data-id', String(c.id));

                var meta = doc.createElement('div');
                meta.className = 'gs-orphan-meta';
                var author = doc.createElement('span');
                author.className = 'gs-thread-author';
                author.textContent = c.author || '—';
                meta.appendChild(author);
                meta.appendChild(doc.createTextNode(' · ' + formatDate(c.createdAt)));
                item.appendChild(meta);

                var q = snippet(c.lineText, 120);
                if (q) {
                    var quote = doc.createElement('div');
                    quote.className = 'gs-orphan-quote';
                    quote.title = c.lineText || '';
                    quote.textContent = 'фрагмент: ' + q;
                    item.appendChild(quote);
                }

                var text = doc.createElement('div');
                text.className = 'gs-thread-text';
                text.textContent = c.body;
                item.appendChild(text);

                block.appendChild(item);
            });
        return block;
    }

    /**
     * Annotate rendered content with comment icons and thread popups.
     *
     * @param {number|string} pageId      Confluence page id
     * @param {string|HTMLElement} renderedHtml  HTML from the git-sync macro
     * @param {object|null} [src]         git-native routing triple (or null
     *                                    for the legacy local storage)
     * @returns {HTMLElement} the annotated container (adopted in place of the content)
     */
    function initGitSyncComments(pageId, renderedHtml, src) {
        var doc = global.document;
        if (!doc) return null;
        injectCss();

        // Accept either an HTML string or a live element.
        var content;
        if (typeof renderedHtml === 'object' && renderedHtml && renderedHtml.nodeType === 1) {
            content = renderedHtml;
        } else {
            var host = doc.createElement('div');
            host.innerHTML = String(renderedHtml || '');
            content = host;
        }

        // When the passed element IS the macro wrapper (.git-sync-content),
        // reuse it as the comment root: dropping it would lose the
        // data-page-id/data-connection-id/data-source-path attributes and
        // the .git-sync-content hook that the plugin CSS (gitsync.css)
        // scopes its page styling to. A string/foreign element still gets
        // a fresh gs-comment-root.
        var root;
        if (content.classList && content.classList.contains('git-sync-content')) {
            root = content;
            root.classList.add('gs-comment-root');
        } else {
            root = doc.createElement('div');
            root.className = 'gs-comment-root';
            while (content.firstChild) {
                root.appendChild(content.firstChild);
            }
        }
        root.setAttribute('data-page', String(pageId));

        // Close any open popup when clicking outside of it.
        doc.addEventListener('click', function (e) {
            if (openPopup && !openPopup.contains(e.target)) {
                closePopup(openPopup);
            }
        });

        fetchComments(pageId, src).then(function (stored) {
            var blocks = root.querySelectorAll(BLOCK_SELECTOR);

            // Requirement R9: remap stored comments to the block whose TEXT
            // matches (the file may have been updated and blocks may have
            // moved) before anything renders icons or popups.
            var blockTexts = [];
            Array.prototype.forEach.call(blocks, function (b) {
                blockTexts.push(plainTextOf(b));
            });
            var comments = reanchorComments(stored, blockTexts);

            // Every block gets the comment affordance — not only blocks that
            // already have comments: otherwise the first comment on a line
            // can never be created from the UI.
            Array.prototype.forEach.call(blocks, function (block, i) {
                var wrapper = doc.createElement('div');
                // A <td> block must not receive a wrapper <div> inside the
                // <tr> (invalid table DOM breaks the layout); wrap the cell
                // content inside the <td> instead. gs-cell-line: cells have
                // no 34px right gutter of their own, so the icon must sit
                // INSIDE the cell (right:0), not 30px past its edge where
                // it would spill onto the neighbouring column.
                var isCell = !!(block.parentNode && block.parentNode.tagName === 'TR');
                wrapper.className = isCell ? 'gs-comment-line gs-cell-line' : 'gs-comment-line';
                if (isCell) {
                    while (block.firstChild) {
                        wrapper.appendChild(block.firstChild);
                    }
                    block.appendChild(wrapper);
                } else {
                    block.parentNode.insertBefore(wrapper, block);
                    wrapper.appendChild(block);
                }

                var lineText = plainTextOf(block);
                var built = buildLine(pageId, src, i, lineText, comments);
                wrapper.appendChild(built.icon);
                if (isCell) {
                    // Popup is anchored to the CELL (not the content wrapper):
                    // it opens just below the whole cell — the wrapper's
                    // height is only the content line, so a wrapper-anchored
                    // popup would cover the cell itself.
                    block.classList.add('gs-comment-cell');
                    block.appendChild(built.popup);
                } else {
                    wrapper.appendChild(built.popup);
                }
            });

            // FR09: comments whose fragment was deleted from / fully
            // rewritten in the file are not pinned to any block — list them
            // in the standalone "Фрагмент не найден" block.
            var orphans = comments.filter(function (c) { return c.orphan === true; });
            if (orphans.length > 0) {
                root.appendChild(buildOrphanBlock(orphans));
            }
        }).catch(function (e) {
            if (console && console.warn) {
                console.warn('git-sync comments: не удалось загрузить комментарии', e);
            }
        });

        // Adopt root in place of the original content (a no-op when the
        // wrapper was reused — it is already in place).
        if (root !== content) {
            if (content.parentNode) {
                content.parentNode.insertBefore(root, content);
                content.parentNode.removeChild(content);
            } else {
                doc.body.appendChild(root);
            }
        }
        return root;
    }

    global.initGitSyncComments = initGitSyncComments;

    // Auto-init bootstrap: the script is delivered as a web resource on
    // every page; when it finds a .git-sync-content div (produced by the
    // git-sync macro, carrying data-page-id) it annotates that content in
    // place. No-op on pages without the macro.
    // Fallback page-id source: Confluence injects <meta name="ajs-page-id">
    // into the page header and AJS exposes the same value. Covers renders
    // where the macro could not resolve the id server-side.
    function pageIdFromPageMeta() {
        try {
            if (global.AJS && global.AJS.params && global.AJS.params.pageId) {
                return String(global.AJS.params.pageId);
            }
            var meta = global.document.querySelector('meta[name="ajs-page-id"]');
            if (meta) {
                var c = meta.getAttribute('content');
                if (c) return String(c);
            }
        } catch (e) { }
        return '';
    }

    function autoInit() {
        var doc = global.document;
        if (!doc || !doc.querySelectorAll) return;
        var nodes = doc.querySelectorAll('.git-sync-content[data-page-id]');
        Array.prototype.forEach.call(nodes, function (el) {
            if (el.getAttribute('data-gs-init')) return;
            var pageId = el.getAttribute('data-page-id');
            if (!pageId) pageId = pageIdFromPageMeta();
            if (!pageId) return;
            el.setAttribute('data-gs-init', '1');
            // data-connection-id (git-sync macro) switches the comment
            // storage to native GitLab commit discussions.
            initGitSyncComments(pageId, el, srcFromEl(el));
        });
    }
    if (global.document && global.document.readyState === 'loading') {
        global.document.addEventListener('DOMContentLoaded', autoInit);
    } else if (global.document) {
        autoInit();
    }

})(typeof window !== 'undefined' ? window : this);
