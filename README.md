# Loki MCP Server

Локальный MCP-сервер (stdio) для чтения логов из Grafana Loki агентом: обзор стенда →
подсчёт → строки → окружение события → полная строка. Шесть инструментов, все ответы —
читаемый текст; рассчитан на небольшие модели (DeepSeek Flash / Haiku-класс).
Только чтение: push, delete, `/config` и другие управляющие endpoint не открываются.

| Инструмент | Что делает |
|---|---|
| `listConnections` | Список стендов из конфигурации с подсказками оператора |
| `discoverLogs` | Метки и значения, формат строк, JSON-поля, готовый selector; `label="..."` — все значения одной метки |
| `countLogs` | Сколько строк подходит; `groupBy` по метке или `"time"` с отметкой всплеска |
| `queryLogs` | Строки в хронологическом порядке: `время LEVEL сервис сообщение`, stack trace сжат; `raw=true` — строка как есть с метками |
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
Credentials задаются через `auth` и `${ПЕРЕМЕННЫЕ}` и не выводятся в ответах.

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
`listConnections → discoverLogs → countLogs → queryLogs → getLogContext → queryLogs raw=true`.

## Разработка

```powershell
.\gradlew.bat build              # unit-тесты и stdio smoke на собранном jar
.\gradlew.bat integrationTest    # Loki 2.6.1 и 3.6.0 в Testcontainers (нужен Docker)
```

План и журнал работ — [plan.md](plan.md), правила для агентов — [AGENTS.md](AGENTS.md).
