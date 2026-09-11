# Git-Sync Confluence Plugin

Плагин для **Confluence Data Center 8.5**, который живьём рендерит Markdown-файлы из
**GitLab** прямо на страницах Confluence: макрос `git-sync` тянет файл по REST GitLab,
конвертирует Markdown в Storage Format и выкладывает его в макрорегион страницы.
Сопутствующие функции: веб-панель управления (подключения, экран настроек), REST API,
построчные комментарии (треды, resolve, удаление, email-уведомления),
**нативные комментарии GitLab** (DiffNotes на коммиты: комментарии из GitLab видны в
Confluence, комментарии из Confluence уходят как нативные комментарии GitLab, плюс
одноразовая миграция локальных `comments.json`), проксирование
вложений, диаграммы Mermaid/PlantUML через Kroki (с сообщением об ошибке при битой
диаграмме), `.html`-файлы как sandboxed iframe (bonus, backlog B-1),
контекстно-зависимые ссылки (FR10), журнал ошибок (NFR05).

Покрывает требования FR01–FR12, NFR01–NFR05 (backlog B-1..B-5, допущения
ASM-01..ASM-05); FR04 (SHOULD) — Accepted, не реализовано (раздел «Границы v1 /
принятые риски»).

## Состав

| Компонент | Где |
|---|---|
| Макрос `git-sync` (live-рендер Markdown → Storage, FR01/FR03/FR05) | `src/main/java/.../macro/` |
| REST API `/rest/gitsync/1.0/...` (connections, comments, attachments, settings, error-journal) | `src/main/java/.../{admin,comments,attachments,errors}/` |
| Веб-панель администратора (подключения, экран настроек, FR02) | `src/main/java/.../admin/AdminServlet.java`, `templates/` |
| Построчные комментарии (треды, resolve, удаление, якоря, FR09) | `src/main/java/.../comments/` |
| Нативные комментарии GitLab (DiffNotes на коммиты; чтение/письмо/миграция, FR09) | `src/main/java/.../comments/git/` |
| Email-уведомления (creator + @упоминания, FR09) | `src/main/java/.../mail/` |
| Ссылки: relative→GitLab/прокси, контекст (FR10), embedding-индекс | `src/main/java/.../links/` |
| Вложения (download-прокси к GitLab, FR07; inline HTML для B-1) | `src/main/java/.../attachments/` |
| Диаграммы Mermaid/PlantUML (Kroki, pre-flight ошибок, FR06/FR11) | `macro/KrokiRenderer.java` |

## Сборка

Требования: JDK 17, Maven.

```bash
mvn -o package -DskipTests
# артефакт: target/git-sync-confluence-1.0.0.jar
```

Особенность: `confluence-api-stubs` — отдельный модуль со stub'ами API Confluence,
он **не** входит в reactor корневого pom (зависимость берётся из `~/.m2` с
scope `provided`). Если вы меняли сами stub'ы:

```bash
cd confluence-api-stubs && mvn -o install -DskipTests && cd ..
mvn -o package -DskipTests
```

## Установка в Confluence

1. Соберите JAR (см. выше).
2. Confluence: **Manage apps → Upload app** → загрузите `target/git-sync-confluence-1.0.0.jar`.
3. Панель подключения: `/plugins/servlet/gitlab/sync/admin` — добавьте GitLab
   (base URL, project id, PAT), проверьте связь. Четыре экрана: список подключений,
   форма добавления/редактирования, **настройки** (таймауты `gitTimeoutMs`/`renderTimeoutMs`,
   лимит размера markdown-файла `maxMarkdownFileSizeBytes` — дефолт 5 МБ; REST
   `GET/PUT /rest/gitsync/1.0/settings`) и тест соединения со статусом.
4. На странице вставьте макрос `git-sync` (connection, file path, branch).

**Скоупы PAT GitLab**: необходимы `read_repository` + `read_api` + `read_user`.
Для **нативных комментариев GitLab** (чтение/создание DiffNotes, миграция) —
дополнительно скоуп `api` (на чтение достаточно `read_api`) и роль **Developer**
(access_level ≥ 30) на проекте: миграция без этого явным образом отклоняется с
сообщением о недостатке прав. Деградация без полного набора:

- без `read_api` — рендеринг работает, но относительные ссылки на `.md`-файлы
  **не переписываются** в blob-URL GitLab (`fetchProjectPath` → null, ссылки
  остаются как в файле);
- без `read_user` — проверка соединения (`GET /user`) невозможна.

Соединения хранятся в `{confluence.home}/secure/gitsync/connections.json`,
PAT зашифрован AES-256-GCM; ключ — JVM-аргумент `-Dgitsync.encryption.key=<base64>`,
при отсутствии — персистентный `{secure}/gitsync/key.bin`.

## REST API

| Метод | Путь | Назначение |
|---|---|---|
| GET | `/rest/gitsync/1.0/connections` | список подключений (без секретов) |
| GET/POST | `/rest/gitsync/1.0/comments` | построчные комментарии (создание/список); для подключений с нативными комментариями GitLab маршрутизирует в GitLab (composite-id, см. «Нативные комментарии GitLab») |
| PUT/DELETE | `/rest/gitsync/1.0/comments/{id}` | редактирование/resolve/удаление комментария (локального или GitLab по composite-id) |
| POST | `/rest/gitsync/1.0/connections/{id}/migrate-comments` | одноразовая идемпотентная миграция локальных `comments.json` в нативные комментарии GitLab (требует Developer) |
| GET/PUT | `/rest/gitsync/1.0/settings` | настройки плагина (NFR01/FR02): `gitTimeoutMs`, `renderTimeoutMs`, `maxMarkdownFileSizeBytes` — JSON в `{confluence.home}/secure/gitsync/settings.json` |
| GET | `/rest/gitsync/1.0/attachments/download` | прокси скачивания вложения из GitLab (page-scoped: обязателен `page` — id страницы, рендер которой ссылается на файл) |
| GET | `/rest/gitsync/1.0/error-journal` | журнал ошибок рендера (NFR05) |

## Нативные комментарии GitLab (DiffNotes на коммиты, FR09)

Построчные комментарии могут жить **нативно в GitLab** (DiffNotes — «File
discussion» на коммитах), а не только локально в `comments.json`:

- **Из GitLab → Confluence.** Комментарий, оставленный в GitLab на diff коммита
  файла, рендерится в Confluence как построчный комментарий (автор, текст, якорь
  к блоку). Свежесть: контент файла перечитывается при изменении HEAD
  (`currentContent(knownHeadSha)`), якорь пере-привязывается.
- **Из Confluence → GitLab.** Комментарий, созданный в UI Confluence, создаётся как
  нативный комментарий GitLab (DiffNote на том же блоке); ответ привязывается к
  дискуссии треда.
- **Маршрутизация.** REST-эндпоинты комментариев прозрачно работают и с локальными
  комментариями, и с GitLab-комментариями: git-комментарии адресуются composite-id
  (код подключения + путь + ref + id заметки), локальные — обычным id.
- **Одноразовая миграция.** `POST /rest/gitsync/1.0/connections/{id}/migrate-comments`
  (кнопка в админ-панели) переносит существующие локальные `comments.json` в GitLab:
  идемпотентно (уже перенесённые — `migratedTo` — пропускаются), копирует
  resolved-состояние как локальный маркер, после успеха удаляет запись из локального
  хранилища. Требует роль Developer на проекте.

**Отклонения (GitLab CE 18.8, приняты):**
- **Видимость на странице коммита, не на странице файла.** DiffNotes рендерятся на
  странице коммита; показ коммит-страницы в CE за WIP-флагом
  `rapid_diffs_on_commit_show` — чтобы DiffNotes были видны в UI, флаг нужно
  включить.
- **Нет нативного resolve.** GitLab не даёт «resolve» для DiffNotes на коммиты,
  поэтому resolved-состояние хранится как локальный маркер в Confluence.
- **Нет per-file blob discussion UI.** В CE 18.8 дискуссии по конкретному
  blob-файлу (вне diff коммита) не поддерживаются — якорь всегда на коммит.

## Отклонения от рекомендаций (обоснованные)

Рекомендованные решения допустимо отклонять с обоснованием. Принятые отклонения:

1. **Диаграммы — внешний `<img src="http(s)://kroki/...">`, а не data-URI.**
   SVG внутри data-URI раздувает storage-страницу и замедляет рендер; внешняя
   ссылка на self-hosted Kroki легче. Корректность гарантируется **server-side
   pre-flight до вставки**: битая диаграмма показывает бокс ошибки с номером
   строки (FR06/FR11), а не битую картинку.
2. **Комментарии и подключения — файлы secure-каталога Confluence**
   (`{confluence.home}/secure/gitsync/*.json`), а не ActiveObjects.
   Rationale: самодостаточность плагина, отсутствие миграций БД, простота
   переноса между инсталляциями; no-write-принцип (FR03) соблюдается
   тривиально.

## Границы v1 / принятые риски

- **FR04 (кэш файла/рендера + single-flight, SHOULD) — Accepted, не
  реализовано.** Свежесть контента (FR03, pull-on-open) важнее кэша: TTL-кэш
  сделал бы сценарий «вставил диаграмму → сохранил → открыл — рендер»
  неопределённым (страница могла бы показать устаревший рендер без диаграммы).
  При ошибке кэш не «выручает» устаревшей копией — по FR08 ошибка показывается
  как есть.
- **B-2..B-5** (миграция конфигурации при обновлении, нативные макросы в
  markdown, уведомление читателей при обновлении контента, scheduler) — вне
  v1, backlog.

## Лицензии зависимостей

Бандлятся в .jar — только MIT / BSD / Apache-2.0. Зависимости в `provided`-scope не бандлятся — берутся из рантайма Confluence:

| Зависимость | Версия | Лицензия |
|---|---|---|
| com.vladsch.flexmark (core + ext) | 0.64.8 | BSD-2-Clause |
| org.jsoup:jsoup | 1.17.2 | MIT |
| com.fasterxml.jackson.core (core/databind/annotations) | 2.17.0 | Apache-2.0 |
| org.slf4j:slf4j-api (provided) | 1.7.36 | MIT |
| jakarta.ws.rs-api (provided) | 3.1.0 | EPL-2.0 |
| jakarta.annotation-api (provided) | 2.1.1 | EPL-2.0 |
| jakarta.inject-api (provided) | 2.0.1 | Apache-2.0 |
