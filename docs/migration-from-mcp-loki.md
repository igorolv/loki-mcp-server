# Миграция с mcp-loki

`mcp-loki` (lexfrei) — один процесс на один Loki, настройка через переменные окружения,
ответы — сырой JSON Loki. Этот сервер — один процесс на несколько стендов из
`connections.json`, ответы — текст для небольшой модели. Ниже — что чему соответствует
и что придётся изменить в конфигурации клиента и в привычках модели.

## Конфигурация

| mcp-loki | loki-mcp-server |
|---|---|
| `LOKI_URL` | `connections.<имя>.url` (можно `${LOKI_DEV_URL}`) |
| `LOKI_USERNAME` / `LOKI_PASSWORD` | `auth: {"type":"BASIC","username":…,"password":"${…}"}` |
| `LOKI_TOKEN` | `auth: {"type":"BEARER","token":"${…}"}` |
| `LOKI_ORG_ID` | `tenant` |
| `MCP_HTTP_PORT` (SSE) | нет: только stdio |
| один сервер на стенд | один сервер, несколько подключений; у каждого инструмента параметр `connection` |
| — | `timezone`, `hint`, `serviceLabels`, `limits` — см. [connections.md](connections.md) |

Запись MCP-клиента меняется с `podman run … ghcr.io/lexfrei/mcp-loki` на
`java -jar loki-mcp-server.jar` с `LOKI_MCP_CONNECTIONS_FILE` (README, «Подключение к
MCP-клиенту»). Если стендов несколько, вместо нескольких серверов `loki-dev`, `loki-tst`
остаётся один `loki`, а стенд выбирается именем подключения.

## Инструменты

| mcp-loki | loki-mcp-server | Отличия |
|---|---|---|
| `loki_query` (log query) | `queryLogs(connection, query, start, end, limit, raw)` | строки печатаются в хронологическом порядке как `HH:mm:ss.SSS LEVEL service message` в timezone подключения; stack trace сжат; `raw=true` даёт исходную строку с метками потока. `direction` нет: всегда самые новые строки окна, старее — повтор с `end` из футера |
| `loki_query` (metric query) | `queryMetrics(connection, query, start, end, step)` | таблица по сериям; instant-режима нет |
| `loki_query` для подсчёта | `countLogs(connection, query, start, end, groupBy)` | метрическое выражение строит сервер; `groupBy="time"` даёт бакеты с отметкой всплеска |
| `loki_labels` (без `name`) | `discoverLogs(connection)` | имена меток вместе со значениями, форматом строк, JSON-полями и готовым selector |
| `loki_labels` (`name=app`) | `discoverLogs(connection, label="app")` | до 200 значений по алфавиту |
| `loki_series` | `discoverLogs(connection, selector=…)` | метки и значения потоков по selector; сами потоки не перечисляются |
| `loki_stats` | нет | `countLogs` даёт число строк; объём чанков не нужен для расследования |
| `loki_ready`, `loki_config` | нет | сервер не открывает `/ready` и `/config`; ошибка транспорта приходит текстом при первом вызове |
| — | `summarizeLogs(connection, query, start, end, sample)` | группы повторяющихся сообщений в выборке |
| — | `getLogContext(connection, selector, time, before, after)` | строки вокруг момента в потоке |

Форматы времени совместимы: `now`, относительное `1h`/`now-1h`, RFC3339; добавлены локальное
время в timezone подключения и epoch nanoseconds. Предел `limit` — `maxEntries` подключения
(по умолчанию 1000), окно — `maxIntervalSeconds` (24h).

## Prompts

`error_logs`, `rate_query`, `top_label_values` не переносились: их роль выполняют описания
инструментов и `instructions` сервера (сценарий `listConnections → discoverLogs → countLogs
→ summarizeLogs → queryLogs → getLogContext → raw`). `error_logs(app)` — это
`countLogs {applicationName="app", level="error"}` затем `queryLogs`; `top_label_values` —
`countLogs` с `groupBy="<label>"`; `rate_query` — `queryMetrics` с `sum(rate(...))`.

## Что меняется для модели

- Ответы — текст, а не JSON: не нужно разбирать `data.result[].values` и наносекунды.
- `count` в `mcp-loki` считал потоки; `countLogs` считает строки.
- Неполнота видна одной фразой (`newest 50 of more`, `Output limit reached`), продолжение —
  повтор с `end`, курсоров нет.
- Ошибка LogQL приходит текстом Loki (`Loki rejected the query: parse error …`), остальные
  ошибки — коротким кодом с советом; URL и credentials в них не попадают.
- Инструкции по стенду (`hint`) читаются из `listConnections`, а не из отдельного документа.

## Порядок перехода

1. Собрать jar (`gradlew bootJar`) и подготовить `connections.json` по
   [examples/connections.json](../examples/connections.json); URL и credentials — через
   переменные окружения.
2. Проверить стенд read-only прогоном: `python scripts/live_smoke/run_smoke.py --connection <имя>`.
3. Заменить запись `mcp-loki` в конфигурации клиента на `loki-mcp-server`.
4. Обновить проектные инструкции модели: вместо LogQL-рецептов под `loki_query` — имя
   подключения и сценарий из README «Как спрашивать». Изменения документов asva2 — отдельный шаг.
