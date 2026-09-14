# Loki MCP Server

Локальный MCP-сервер (stdio) для чтения логов из Grafana Loki агентом: обзор стенда →
подсчёт → сводка → строки → окружение события → полная строка. Семь инструментов, все ответы —
читаемый текст; рассчитан на небольшие модели (целевая — DeepSeek 4.1 Flash).
Только чтение: push, delete, `/config` и другие управляющие endpoint не открываются.

| Инструмент | Что делает |
|---|---|
| `listConnections` | Список стендов из конфигурации с подсказками оператора |
| `discoverLogs` | Метки и значения, формат строк, JSON-поля, готовый selector; `label="..."` — все значения одной метки |
| `countLogs` | Сколько строк подходит; `groupBy` по метке или `"time"` с отметкой всплеска |
| `queryLogs` | Строки в хронологическом порядке: `время LEVEL сервис сообщение`, stack trace сжат; `raw=true` — строка как есть с метками |
| `summarizeLogs` | Группы повторяющихся сообщений в выборке новых строк: число, первое/последнее время, пример; редкие — отдельно |
| `getLogContext` | N строк до и после момента в потоке, целевые строки отмечены `>>>` |
| `queryMetrics` | Метрический LogQL таблицей (продвинутое) |

Поддерживаются Loki 2.6.1 и 3.x. Контракт ответов — [docs/queries.md](docs/queries.md),
[docs/discovery.md](docs/discovery.md); подключения — [docs/connections.md](docs/connections.md).

## Сборка

Нужен JDK 21+ (используется Java 21 toolchain).

```powershell
.\gradlew.bat bootJar
```

Jar: `build/libs/loki-mcp-server.jar`. Linux/macOS — `./gradlew bootJar`.

## Конфигурация подключений

Сервер читает `~/.loki-mcp-server/connections.json` (или файл из переменной
`LOKI_MCP_CONNECTIONS_FILE`). Пример с подсказками для стендов asva2 —
[examples/connections.json](examples/connections.json); URL стендов там берутся из
переменных окружения `LOKI_DEV_URL` и `LOKI_TST_URL`. Минимум:

```json
{
  "connections": {
    "dev": {
      "description": "DEV stand, Loki 2.6.1",
      "hint": "Service = label applicationName. Whole environment: {namespace=\"dev\"}.",
      "url": "http://loki.example.internal",
      "timezone": "Europe/Moscow",
      "serviceLabels": ["applicationName", "container"]
    }
  }
}
```

`hint` — короткая карта стенда для модели (какие метки есть, как выбирать сервис,
известные ловушки); `serviceLabels` — по каким меткам называть сервис в строке.
Credentials задаются через `auth` (`BASIC` или `BEARER`) и `${ПЕРЕМЕННЫЕ}`, `tenant` уходит
в `X-Scope-OrgID`; ничего из этого не выводится в ответах и логах. Полный формат, defaults
и ошибки загрузки — [docs/connections.md](docs/connections.md).

### Выбор подключения

Каждый инструмент, кроме `listConnections`, требует явный `connection`; стенд по умолчанию
не подставляется даже при единственной записи. Модель выбирает имя из `listConnections`,
поэтому `description` и `hint` должны отвечать на вопрос «какой это стенд и как здесь
искать сервис»: имя метки сервиса, selector всего окружения, что делать, если метки
`level` нет. Для нескольких похожих стендов (DEV/TST/PROD одной системы) держите
подключения в одном файле с говорящими именами; для разных систем — отдельные файлы через
`LOKI_MCP_CONNECTIONS_FILE` и отдельные записи MCP-клиента.

### Лимиты и время

Объект `limits` подключения (все поля необязательны): `maxEntries` (1000) — предел `limit`
и `sample`; `maxIntervalSeconds` (86400) — самое длинное окно `start`–`end`;
`maxResponseBytes` (65536) — предел текста одного ответа, при превышении `queryLogs`
отбрасывает самые старые строки, `summarizeLogs` — редкие группы, и говорит об этом в
футере; `requestTimeoutMs` (30000) и `maxHttpResponseBytes` (8 MiB) — предел одного запроса
к Loki. Время: `now`, `now-15m` (`ns/ms/s/m/h/d`), RFC3339 с offset
(`2026-09-13T10:00:00+03:00`), локальное время в `timezone` подключения или epoch
nanoseconds; `getLogContext` дополнительно принимает время суток из строки страницы
(`10:12:03.123`). Ответы печатают время в `timezone` подключения.

### Совместимость с Loki

Проверено контейнерными тестами на Loki 2.6.1 и 3.6.0 и на живых стендах 2.6.1 (k8s)
и 3.5 (docker). Используются только `query_range`, `query`, `labels`, `label/<name>/values`
и `series`. На Loki 2.6.1 пустой результат `/series` и `/label/<name>/values` без поля
`data` принимается как пустой список; на 3.x `detected_level` из structured metadata
считается уровнем строки (`unknown` — отсутствием уровня). Loki patterns API не нужен:
`summarizeLogs` группирует локально.

## Подключение к MCP-клиенту

Claude Code:

```bash
claude mcp add --scope user loki -e LOKI_MCP_CONNECTIONS_FILE=C:/path/connections.json -- java -jar C:/path/loki-mcp-server.jar
```

Codex (`~/.codex/config.toml`):

```toml
[mcp_servers.loki]
command = "java"
args = ["-jar", "C:/path/loki-mcp-server.jar"]
env = { LOKI_MCP_CONNECTIONS_FILE = "C:/path/connections.json" }
```

Любой другой клиент — команда `java -jar loki-mcp-server.jar`, транспорт stdio.
stdout занят JSON-RPC; собственные логи сервера пишутся в stderr и в
`~/.loki-mcp-server/logs`. В логе — подключения при старте, строка на каждый вызов
инструмента (аргументы, результат, размер, время) и на каждый запрос к Loki (path,
параметры, статус, время); URL, credentials и содержимое строк логов туда не попадают.

## Как спрашивать

Называйте стенд, сервис и период: «покажи ошибки ssj-ui-backend на DEV за час»,
«стенд DEV не работает, разберись», «какие значения у метки applicationName на TST».
Типичный ход расследования, который сервер подсказывает модели через `instructions`:
`listConnections → discoverLogs → countLogs → summarizeLogs → queryLogs → getLogContext → queryLogs raw=true`.

## Формат ошибок

Ошибка — текст `Error <CODE>: <что не так и что сделать>` с `isError=true`, никогда не
исключение транспорта. Примеры:

| Код | Когда | Что делать |
|---|---|---|
| `CONNECTION_REQUIRED`, `UNKNOWN_CONNECTION` | нет или неверное имя `connection` | взять имя из `listConnections` |
| `INVALID_ARGUMENT` | непонятное время, `limit` вне предела, окно длиннее `maxIntervalSeconds`, метрический запрос в `queryLogs`, pipeline в `getLogContext` | текст ошибки называет допустимый формат или другой инструмент |
| `UPSTREAM_BAD_REQUEST` | Loki отверг LogQL | текст Loki передаётся как есть (`Loki rejected the query: parse error at line 1, col 23: …`) — по нему исправляется запрос |
| `UPSTREAM_TIMEOUT`, `UPSTREAM_UNAVAILABLE`, `UPSTREAM_RATE_LIMITED` | стенд не ответил за `requestTimeoutMs`, 5xx, 429 | сузить окно или запрос, повторить позже |
| `UPSTREAM_UNAUTHORIZED`, `UPSTREAM_FORBIDDEN`, `ENDPOINT_UNAVAILABLE` | 401/403/404 от Loki или ingress | проверить `auth`/`tenant`; 404 на одном endpoint не означает недоступность остальных |
| `RESPONSE_BUDGET_EXCEEDED` | даже минимальный ответ не помещается в `maxResponseBytes` | сузить запрос или поднять лимит подключения |

URL, credentials, tenant и тексты Loki кроме ошибок запроса в сообщения не попадают.
Полный список кодов — [docs/queries.md](docs/queries.md#ошибки), [docs/http-client.md](docs/http-client.md).

## Troubleshooting

- **Сервер не стартует / клиент показывает «failed».** Смотрите stderr клиента или
  `~/.loki-mcp-server/logs/loki-mcp-server.log`: невалидный `connections.json` (лишнее поле,
  дубликат ключа, незаданная `${ПЕРЕМЕННАЯ}`) завершает запуск с сообщением без исходного
  текста и credentials. Нужна Java 21+ в `PATH` клиента.
- **`ENDPOINT_UNAVAILABLE` (404).** Ingress может закрывать часть путей Loki (на DEV asva2
  наружу открыты только read-пути `query`, `query_range`, `labels`, `label/<name>/values`,
  `series`; `/config`, `tail`, `push` отвечают 404). Сервер ходит только по read-путям, так что
  404 на живом стенде обычно означает неверный префикс пути в `url` или закрытый ingress для
  одного endpoint; остальные инструменты при этом продолжают работать.
- **`UPSTREAM_TIMEOUT`.** Фильтр по structured metadata или `| json` на сутках нагруженного
  стенда может не уложиться в `requestTimeoutMs`: сузьте окно (`start="now-3h"`), добавьте
  метку в selector или уменьшите `limit`/`sample`.
- **Пустой ответ.** `no matching lines` в последний час — не доказательство, что ошибок
  нет: тихие стенды (TST asva2) требуют `start="now-6h"`/`"now-24h"`; проверьте метки через
  `discoverLogs` — например, у сервиса может не быть метки `level`, если он не
  перезапускался после изменения логирования, тогда фильтруйте по тексту (`|= "ERROR"`) или
  по `detected_level` на Loki 3.x.
- **Метка отсутствует у сервиса.** `applicationName` ≠ имя контейнера, часть потоков идёт
  без неё; `hint` подключения должен называть запасные метки (`instance`, `container`), а
  `discoverLogs` с `label="applicationName"` покажет, кто её имеет.
- **Строки без уровня (`-` во втором столбце).** Продолжения stack trace сервисов с
  построчным логированием не имеют `level`; они не скрываются, а `getLogContext` по selector
  без `level` показывает их вокруг ошибки.
- **Ответ обрезан.** `Output limit reached` в футере означает предел `maxResponseBytes`:
  сузьте запрос, используйте `countLogs`/`summarizeLogs` или поднимите лимит подключения.
- **Что именно ушло в Loki.** В логе сервера — строка на каждый запрос с path и параметрами
  (`GET /loki/api/v1/query_range {start=…, query=…} -> 200, 1586 bytes, 397 ms`) и на каждый
  вызов инструмента; host, auth и содержимое строк туда не пишутся.

## Миграция с mcp-loki

Соответствие инструментов и настроек — [docs/migration-from-mcp-loki.md](docs/migration-from-mcp-loki.md).

## Разработка

```powershell
.\gradlew.bat build              # unit-тесты и stdio smoke на собранном jar
.\gradlew.bat integrationTest    # Loki 2.6.1 и 3.6.0 в Testcontainers (нужен Docker)
python scripts/live_smoke/run_smoke.py --connection dev   # read-only прогон jar на живом стенде
```

Live smoke берёт профили из [examples/connections.json](examples/connections.json)
(URL — из `LOKI_DEV_URL`/`LOKI_TST_URL`), запускает jar по stdio как настоящий клиент и
проходит `listConnections → discoverLogs → countLogs → queryLogs → summarizeLogs →
getLogContext → raw`, плюс отказ pipeline, ошибку парсера Loki и `queryMetrics`; selector
берётся из ответа `discoverLogs`, поэтому скрипт не зависит от конкретного стенда.
`--verbose` печатает ответы целиком, `--window now-24h` расширяет окно. Ничего не пишет в Loki.

CI ([.github/workflows/build.yml](.github/workflows/build.yml)): `gradlew build` и
`integrationTest` на каждый push/PR, на тег `v*` — GitHub Release с jar.
Правила для участников — [CONTRIBUTING.md](CONTRIBUTING.md), безопасность — [SECURITY.md](SECURITY.md).

План и журнал работ — [plan.md](plan.md), правила для агентов — [AGENTS.md](AGENTS.md).
