# Подключения и listConnections

Сервер загружает конфигурацию один раз при запуске.
`listConnections` возвращает текст — по строке на подключение: имя, описание и
подсказку оператора — и не обращается к Loki. Для чтения доступны
[queryLogs, countLogs, summarizeLogs, getLogContext, queryMetrics](queries.md) и [discoverLogs](discovery.md).
Наличие подключения в списке не подтверждает доступность endpoint.

По умолчанию файл — `~/.loki-mcp-server/connections.json`.
`LOKI_MCP_DATA_DIR` меняет каталог данных, логов и файла по умолчанию.
`LOKI_MCP_CONNECTIONS_FILE` задаёт отдельный путь к файлу; относительный путь
разрешается от рабочего каталога процесса. Эквивалентные Spring properties:
`loki-mcp.data-dir` и `loki-mcp.connections-file`.

Минимальная конфигурация:

```json
{
  "connections": {
    "local": {
      "description": "Локальный Loki",
      "url": "http://localhost:3100"
    }
  }
}
```

Полный пример — [examples/connections.json](../examples/connections.json).
Перед использованием задайте перечисленные там переменные окружения или удалите
подключение `secured`. Реальные конфигурации и credentials не коммитить.
Имена и описания являются публичными: не помещайте в них секреты.

Формат: объект `connections` с одним или несколькими подключениями.
Имена чувствительны к регистру, длина 1–64, допустимы ASCII-буквы, цифры,
точка, дефис и подчёркивание; первый символ — буква или цифра.
Описание необязательно, до 512 символов. `hint` — необязательная подсказка модели
до 1024 символов: какие метки есть на стенде, как выбирать сервис. `serviceLabels` —
необязательный список меток, по которым строка получает имя сервиса
(default `applicationName, service_name, service, app, container, job`).
Registry не подставляет подключение по умолчанию даже при единственной записи
и не обрезает пробелы в имени.

`url` обязателен: абсолютный HTTP/HTTPS URL, допускается префикс пути.
User info, query и fragment запрещены. Авторизация задаётся отдельным `auth`:

- Отсутствующий `auth` или `{"type":"NONE"}` — без credentials.
- `{"type":"BASIC","username":"reader","password":"${LOKI_PASSWORD}"}`.
- `{"type":"BEARER","token":"${LOKI_TOKEN}"}`.

Смешивание режимов и управляющие символы в credentials запрещены.
`tenant` необязателен, предназначен для `X-Scope-OrgID`; пустое значение и
управляющие символы запрещены. `${VARIABLE}` поддерживается в URL, username,
password, token и tenant. Подстановка однократная, без shell-вычислений и
значений по умолчанию; отсутствие переменной или некорректный placeholder — ошибка.
`timezone` по умолчанию `UTC`, значение проверяется через Java `ZoneId`.

Объект `limits` необязателен; каждое пропущенное поле получает свой default:

| Поле | Default | Допустимое значение |
|---|---:|---|
| `connectTimeoutMs` | 5000 | Положительное целое int |
| `requestTimeoutMs` | 30000 | Положительное целое int |
| `maxHttpResponseBytes` | 8388608 | Положительное целое int |
| `maxResponseBytes` | 65536 | Целое int, не меньше 1024 |
| `maxEntries` | 1000 | Положительное целое int |
| `maxIntervalSeconds` | 86400 | Положительное целое long |
| `maxMetricSeries` | 100 | Положительное целое int |
| `maxMetricPoints` | 10000 | Положительное целое int |

HTTP-клиент применяет auth/tenant, `connectTimeoutMs`, `requestTimeoutMs`
и `maxHttpResponseBytes` (см. [HTTP-клиент](http-client.md)); сервисы —
`maxEntries`, `maxIntervalSeconds`, `maxMetricSeries` и `maxMetricPoints`.
`maxResponseBytes` — предел текста ответа: [контракт](queries.md#предел-размера).
`listConnections` возвращает полный список в пределах 65536 байт либо ошибку.
Сам файл ограничен 1 MiB. Неизвестные поля запрещены.

Отсутствующий/пустой/невалидный файл, повторяющиеся JSON-ключи, неизвестные поля,
дробные или строковые числовые лимиты приводят к отказу запуска. Исходный JSON,
credentials, URL и исходные исключения парсера не включаются в диагностику.
Сообщение предлагает проверить конфигурацию по примеру. Изменения требуют перезапуска.

Ответ `listConnections`:

```
dev — DEV стенд asva2, Loki 2.6.1. Labels: applicationName, level, instance, pod
tst — TST стенд, Loki 3.5
```

Описание и подсказка опускаются, если не заданы. URL, credentials и лимиты не выводятся.

Ошибки — текст `Error <CODE>: <сообщение>` с `isError=true` (внутренний `ToolError`:
`code`, `message`, `retryable`). Базовые коды: `CONFIGURATION_ERROR`, `CONNECTION_REQUIRED`,
`INVALID_CONNECTION`, `UNKNOWN_CONNECTION`, `INTERNAL_ERROR`. Транспортные коды —
[HTTP-клиент](http-client.md). `LokiOperationException` переносит безопасный
`ToolError` между слоями; `Errors.from` превращает неожиданное исключение в фиксированную
внутреннюю ошибку без исходного текста. Ошибка загрузки завершает процесс до обслуживания
tools; у `listConnections` нет аргумента выбора подключения.
