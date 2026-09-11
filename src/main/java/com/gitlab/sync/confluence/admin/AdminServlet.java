package com.gitlab.sync.confluence.admin;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Serves the self-contained Git-Sync admin panel (HTML + vanilla JS) at
 * {@code /plugins/servlet/gitlab/sync/admin}. The panel drives the same REST API
 * that the plugin exposes, so authorization is enforced there
 * (mutations are {@code @RolesAllowed("administrators")}); this servlet
 * only requires an authenticated user to serve the UI.
 */
public class AdminServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        String user = req.getRemoteUser();
        if (user == null || user.isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.setContentType("text/plain; charset=utf-8");
            resp.getWriter().print("401: authentication required");
            return;
        }
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/html; charset=utf-8");
        resp.getWriter().print(PAGE_HTML.replace("__USER__",
                user.replace("&", "&amp;").replace("<", "&lt;")));
    }

    private static final String PAGE_HTML = """
            <!DOCTYPE html>
            <html lang="ru">
            <head>
            <meta charset="utf-8">
            <title>Git-Sync — панель администратора</title>
            <style>
              body { font-family: -apple-system, "Segoe UI", Roboto, sans-serif; margin: 2em auto; max-width: 1100px; padding: 0 1em; color: #222; }
              h1 { font-size: 1.5em; } h2 { font-size: 1.2em; margin-top: 2em; }
              table { border-collapse: collapse; width: 100%; margin: 1em 0; }
              th, td { border: 1px solid #ddd; padding: 6px 10px; text-align: left; font-size: 0.9em; vertical-align: top; }
              th { background: #f5f6f7; }
              input, button { font: inherit; padding: 6px 10px; margin: 2px 0; }
              input { width: 26em; max-width: 100%; }
              button { cursor: pointer; background: #0052cc; color: #fff; border: 0; border-radius: 4px; }
              button.secondary { background: #6b778c; }
              button.danger { background: #cc0000; }
              .status { margin: 0.8em 0; padding: 0.6em 1em; border-radius: 4px; display: none; }
              .status.ok { display: block; background: #e3fcef; border: 1px solid #36b37e; }
              .status.err { display: block; background: #ffebe6; border: 1px solid #ff5630; }
              .muted { color: #6b778c; font-size: 0.85em; }
              .mono { font-family: ui-monospace, Menlo, monospace; font-size: 0.85em; word-break: break-all; }
              form { background: #f5f6f7; padding: 1em; border-radius: 6px; }
              label { display: block; margin-top: 0.6em; font-weight: 600; font-size: 0.9em; }
            </style>
            </head>
            <body>
            <h1>Git-Sync — панель администратора</h1>
            <p class="muted">Вход: __USER__. Панель управляет теми же REST-эндпоинтами, что и API
            (<span class="mono">/rest/gitsync/1.0</span>).</p>

            <div id="status" class="status"></div>

            <h2>Соединения с GitLab</h2>
            <table id="conns">
              <thead><tr><th>Имя</th><th>GitLab URL</th><th>Проект</th><th>Обновлено</th><th>Действия</th></tr></thead>
              <tbody><tr><td colspan="5">Загрузка…</td></tr></tbody>
            </table>

            <form id="conn-form" onsubmit="return saveConnection(event)">
              <h3 id="form-title">Новое соединение</h3>
              <input type="hidden" id="f-id" value="">
              <label>Имя (используется в параметре connection макроса)
                <input id="f-name" required>
              </label>
              <label>GitLab URL
                <input id="f-baseUrl" placeholder="http://localhost:8929" required>
              </label>
              <label>ID проекта
                <input id="f-projectId" type="number" min="1" required>
              </label>
              <label>PAT (при редактировании — оставьте пустым, чтобы сохранить текущий)
                <input id="f-pat" placeholder="glpat-…">
              </label>
              <p>
                <button type="submit">Сохранить</button>
                <button type="button" class="secondary" onclick="resetForm()">Сбросить</button>
              </p>
            </form>

            <h2>Настройки</h2>
            <form id="settings-form" onsubmit="return saveSettings(event)">
              <label>Таймаут запросов к GitLab, мс
                <input id="s-gitTimeoutMs" type="number" min="1" step="1" required>
              </label>
              <label>Таймаут рендера (проба Kroki, connect), мс — read-таймаут = 2× этого значения
                <input id="s-renderTimeoutMs" type="number" min="1" step="1" required>
              </label>
              <label>Лимит размера markdown-файла, байт (дефолт 5242880 = 5 МБ)
                <input id="s-maxMarkdownFileSizeBytes" type="number" min="1" step="1" required>
              </label>
              <p>
                <button type="submit">Сохранить</button>
              </p>
            </form>

            <h2>Журнал ошибок</h2>
            <p><button class="secondary" onclick="loadJournal()">Обновить</button></p>
            <table id="journal">
              <thead><tr><th>Время</th><th>Компонент</th><th>Контекст</th><th>Сообщение</th></tr></thead>
              <tbody><tr><td colspan="4">Загрузка…</td></tr></tbody>
            </table>

            <script>
            const API = '/rest/gitsync/1.0';

            function showStatus(msg, ok) {
              const el = document.getElementById('status');
              el.textContent = msg;
              el.className = 'status ' + (ok ? 'ok' : 'err');
              setTimeout(() => { el.className = 'status'; }, 6000);
            }

            async function api(path, opts) {
              const r = await fetch(API + path, Object.assign({
                headers: {'Content-Type': 'application/json'},
                credentials: 'same-origin'
              }, opts || {}));
              let body = null;
              try { body = await r.json(); } catch (e) { /* non-JSON */ }
              if (!r.ok) {
                const msg = body && (body.error || body.message) ? (body.error || body.message)
                        : (r.status + ' ' + (body || ''));
                throw new Error(typeof msg === 'string' ? msg : JSON.stringify(msg));
              }
              return body;
            }

            function fmtDate(ts) {
              if (!ts) return '—';
              const d = new Date(ts);
              return d.toLocaleString();
            }

            async function loadConnections() {
              const tbody = document.querySelector('#conns tbody');
              try {
                const conns = await api('/connections');
                if (!conns.length) {
                  tbody.innerHTML = '<tr><td colspan="5">Соединений нет — создайте первое в форме ниже.</td></tr>';
                  return;
                }
                tbody.innerHTML = '';
                for (const c of conns) {
                  const tr = document.createElement('tr');
                  const nameTd = document.createElement('td');
                  nameTd.textContent = c.name;
                  const urlTd = document.createElement('td');
                  urlTd.textContent = c.baseUrl;
                  const projTd = document.createElement('td');
                  projTd.textContent = c.projectId;
                  const updTd = document.createElement('td');
                  updTd.textContent = fmtDate(c.updatedAt);
                  const actTd = document.createElement('td');
                  const bTest = document.createElement('button');
                  bTest.className = 'secondary';
                  bTest.textContent = 'Тест';
                  bTest.onclick = () => testConn(c.id);
                  const bMigrate = document.createElement('button');
                  bMigrate.className = 'secondary';
                  bMigrate.title = 'Одноразовый перенос локальных линейных комментариев в нативные комментарии GitLab';
                  bMigrate.textContent = 'Мигрировать комментарии';
                  bMigrate.onclick = () => migrateConn(c);
                  const bEdit = document.createElement('button');
                  bEdit.className = 'secondary';
                  bEdit.textContent = 'Редактировать';
                  bEdit.onclick = () => editConn(c);
                  const bDel = document.createElement('button');
                  bDel.className = 'danger';
                  bDel.textContent = 'Удалить';
                  bDel.onclick = () => delConn(c);
                  actTd.append(bTest, ' ', bMigrate, ' ', bEdit, ' ', bDel);
                  tr.append(nameTd, urlTd, projTd, updTd, actTd);
                  tbody.appendChild(tr);
                }
              } catch (e) {
                tbody.innerHTML = '<tr><td colspan="5">Ошибка: ' + e.message + '</td></tr>';
              }
            }

            async function testConn(id) {
              try {
                const r = await api('/connections/' + id + '/test', {method: 'POST'});
                let msg = r.valid
                    ? 'Соединение работает: ' + (r.message || '')
                    : 'Соединение НЕ работает: ' + (r.message || '');
                if (r.valid) {
                  msg += r.writeAccess
                      ? ' · права: Developer (комментарии разрешены)'
                      : ' · ⚠ ' + (r.writeError || 'недостаточно прав для комментариев');
                }
                showStatus(msg, !!r.valid && r.writeAccess !== false);
              } catch (e) { showStatus('Тест не выполнен: ' + e.message, false); }
            }

            async function migrateConn(c) {
              if (!confirm('Перенести локальные линейные комментарии соединения «'
                  + c.name + '» в нативные комментарии GitLab?\\n\\n'
                  + 'Операция одноразовая и идемпотентная; перенесённые записи '
                  + 'будут удалены из локального хранилища.')) return;
              try {
                const r = await api('/connections/' + c.id + '/migrate-comments', {method: 'POST'});
                if (r.error) { showStatus('Миграция: ' + r.error, false); return; }
                let msg = 'Миграция: перенесено ' + (r.migrated || 0)
                    + ', пропущено ' + (r.skipped || 0)
                    + ', ошибок ' + (r.failed || 0)
                    + ' (страниц с макросом: ' + (r.pagesWithMacro || 0) + ')';
                const errs = (r.errors || []).slice(0, 5);
                if (errs.length) msg += '\\n' + errs.join('\\n');
                showStatus(msg, (r.failed || 0) === 0);
              } catch (e) { showStatus('Миграция не выполнена: ' + e.message, false); }
            }

            function editConn(c) {
              document.getElementById('form-title').textContent = 'Редактирование: ' + c.name;
              document.getElementById('f-id').value = c.id;
              document.getElementById('f-name').value = c.name;
              document.getElementById('f-baseUrl').value = c.baseUrl;
              document.getElementById('f-projectId').value = c.projectId;
              document.getElementById('f-pat').value = '';
              document.getElementById('conn-form').scrollIntoView({behavior: 'smooth'});
            }

            function resetForm() {
              document.getElementById('form-title').textContent = 'Новое соединение';
              document.getElementById('conn-form').reset();
              document.getElementById('f-id').value = '';
            }

            async function saveConnection(ev) {
              ev.preventDefault();
              const id = document.getElementById('f-id').value;
              const payload = {
                name: document.getElementById('f-name').value.trim(),
                baseUrl: document.getElementById('f-baseUrl').value.trim(),
                projectId: parseInt(document.getElementById('f-projectId').value, 10),
                pat: document.getElementById('f-pat').value.trim()
              };
              if (!payload.pat) delete payload.pat;
              try {
                if (id) {
                  await api('/connections/' + id, {method: 'PUT', body: JSON.stringify(payload)});
                  showStatus('Соединение «' + payload.name + '» обновлено', true);
                } else {
                  await api('/connections', {method: 'POST', body: JSON.stringify(payload)});
                  showStatus('Соединение «' + payload.name + '» создано', true);
                }
                resetForm();
                loadConnections();
              } catch (e) { showStatus('Не удалось сохранить: ' + e.message, false); }
              return false;
            }

            async function delConn(c) {
              if (!confirm('Удалить соединение «' + c.name + '»?')) return;
              try {
                await api('/connections/' + c.id, {method: 'DELETE'});
                showStatus('Соединение «' + c.name + '» удалено', true);
                loadConnections();
              } catch (e) { showStatus('Не удалось удалить: ' + e.message, false); }
            }

            async function loadSettings() {
              try {
                const s = await api('/settings');
                document.getElementById('s-gitTimeoutMs').value = s.gitTimeoutMs;
                document.getElementById('s-renderTimeoutMs').value = s.renderTimeoutMs;
                document.getElementById('s-maxMarkdownFileSizeBytes').value = s.maxMarkdownFileSizeBytes;
              } catch (e) { showStatus('Не удалось загрузить настройки: ' + e.message, false); }
            }

            async function saveSettings(ev) {
              ev.preventDefault();
              const payload = {
                gitTimeoutMs: parseInt(document.getElementById('s-gitTimeoutMs').value, 10),
                renderTimeoutMs: parseInt(document.getElementById('s-renderTimeoutMs').value, 10),
                maxMarkdownFileSizeBytes: parseInt(document.getElementById('s-maxMarkdownFileSizeBytes').value, 10)
              };
              for (const [key, value] of Object.entries(payload)) {
                if (!Number.isInteger(value) || value <= 0) {
                  showStatus('Некорректное значение: ' + key + ' (нужно целое > 0)', false);
                  return false;
                }
              }
              try {
                const s = await api('/settings', {method: 'PUT', body: JSON.stringify(payload)});
                showStatus('Настройки сохранены: git ' + s.gitTimeoutMs + ' мс / '
                    + 'рендер ' + s.renderTimeoutMs + ' мс / '
                    + 'лимит ' + s.maxMarkdownFileSizeBytes + ' байт', true);
              } catch (e) { showStatus('Не удалось сохранить настройки: ' + e.message, false); }
              return false;
            }

            async function loadJournal() {
              const tbody = document.querySelector('#journal tbody');
              try {
                const items = await api('/error-journal');
                if (!items.length) {
                  tbody.innerHTML = '<tr><td colspan="4">Ошибок нет.</td></tr>';
                  return;
                }
                tbody.innerHTML = '';
                for (const it of items) {
                  const tr = document.createElement('tr');
                  const t = document.createElement('td');
                  t.textContent = fmtDate(it.timestamp || it.time);
                  const comp = document.createElement('td');
                  comp.textContent = it.source || it.component || it.type || '—';
                  const ctx = document.createElement('td');
                  ctx.textContent = it.context || '';
                  const msg = document.createElement('td');
                  msg.textContent = it.message || it.error || '';
                  tr.append(t, comp, ctx, msg);
                  tbody.appendChild(tr);
                }
              } catch (e) {
                tbody.innerHTML = '<tr><td colspan="4">Ошибка: ' + e.message + '</td></tr>';
              }
            }

            loadConnections();
            loadSettings();
            loadJournal();
            </script>
            </body>
            </html>
            """;
}
