# HTTP-клиент S03

`LokiHttpClient` — внутренний транспортный слой. В S04 поверх него добавлены
[queryLogs и queryMetrics](queries.md); `listConnections` не использует HTTP.
Клиент создаётся Spring как bean; создание не выполняет сетевых запросов.

Контракт транспорта основан на [Loki HTTP API](https://grafana.com/docs/loki/latest/reference/loki-http-api/).
Доступны только GET-запросы к фиксированным endpoint:

| Метод Java | Endpoint | Обязательные аргументы |
|---|---|---|
| `queryRange` | `/loki/api/v1/query_range` | connection, query, start/end, limit, direction |
| `queryInstant` | `/loki/api/v1/query` | connection, query, time |
| `labels` | `/loki/api/v1/labels` | connection, start/end |
| `labelValues` | `/loki/api/v1/label/<name>/values` | connection, label, start/end |
| `series` | `/loki/api/v1/series` | connection, selectors, start/end |

В `queryRange` необязательный `stepSeconds` задаётся положительным `BigDecimal`.
У `labels`/`labelValues` необязательный selector передаётся как `query`.
`series` отправляет каждый selector отдельным `match[]`.
Имя label проверяется по базовому синтаксису `[a-zA-Z_][a-zA-Z0-9_]*`.
Произвольного URL/path, write endpoints и чтения `/config` нет.

Время принимается как абсолютный `Instant` и отправляется строкой epoch nanoseconds
в диапазоне signed int64, без округления. Интервал требует `start < end`.
Относительное время и timezone обрабатываются прикладным слоем S04.
Клиент не рассчитывает `now` и не подставляет временные границы самостоятельно.
LogQL кодируется как единый query parameter; его содержимое не исполняется локально.
Префикс пути из базового URL сохраняется, завершающие `/` нормализуются.

Для каждого registry name лениво создаётся отдельный Java 21 `HttpClient`.
Basic передаёт Base64 от UTF-8 `username:password`, Bearer — отдельный Authorization,
tenant — `X-Scope-OrgID`. Redirects отключены, cookies и authenticator не настроены.
Вызовы одного клиента могут выполняться одновременно; транспорт не изменяет registry
и не блокирует другие подключения после ошибки. При закрытии bean клиенты получают
`shutdownNow`; последующие вызовы завершаются контролируемой отменой.

Применяются `connectTimeoutMs`, `requestTimeoutMs`, `maxHttpResponseBytes`:

- Connect timeout ограничивает установку нового соединения, включая TLS handshake.
- Request timeout ограничивает ожидание полного ответа, включая body после заголовков.
  При таймауте или прерывании чтение отменяется; interrupt flag сохраняется.
- BodySubscriber считает фактически полученные байты и отменяет чтение до копирования
  порции, превышающей бюджет. Проверяется также заявленный Content-Length.
  Chunked-ответы проходят тот же контроль. Лимит относится к body, а не суммарной
  памяти JVM, заголовкам или будущему MCP-ответу.
- Запрашиваются JSON и `Accept-Encoding: identity`. Неожиданное сжатие отклоняется,
  чтобы не принимать неограниченно распакованный body. Поддержки gzip пока нет.

`maxEntries`, `maxIntervalSeconds` и `maxResponseBytes` в транспортном слое не
применяются: проверка пользовательских лимитов и фактического окна относится к S04,
бюджет всего MCP-ответа реализован в S06 декоратором stdio-транспорта.
`queryRange` передаёт явно полученный `limit`
без молчаливого изменения. Сервис обязан проверить его относительно настроек.
Прикладных повторов запросов, обязательных probes и проверки версии нет.

## Декодирование

`LokiResponses` содержит transport records; они не являются output schemas MCP.
Поддерживаются `streams`, `vector`, `matrix`, списки labels/values и series.
Неизвестный resultType или неверная форма известных данных — ошибка, а не пустая выборка.
Новые неизвестные поля объектов допускаются. Повторяющиеся JSON-ключи и trailing JSON
запрещены. Пустые массивы результата допустимы.

Для логов сохраняются порядок upstream, каждый повтор, исходная строка, строковый
nanosecond timestamp и явно переданная плоская string-to-string metadata в третьем
элементе tuple. Клиент не сортирует и не дедуплицирует события. Отсутствующая отдельная
metadata даёт пустую map: это не доказательство отсутствия metadata у исходной записи.

Метки `LogStream.labels` — метки **результата**. Loki может добавлять к ним
structured metadata и поля pipeline. Нельзя считать их доказанным исходным stream scope
или восстанавливать происхождение по именам. Это ограничение описано в
[документации structured metadata](https://grafana.com/docs/loki/latest/get-started/labels/structured-metadata/).
Неподдерживаемая вложенная форма третьего элемента отклоняется, а не теряется молча.

Metric timestamps должны быть JSON-числами в секундах и разбираются в `BigDecimal`.
Metric values сохраняются строками, включая `NaN`, `+Inf`, `-Inf` и экспоненциальную
запись. Строковые log timestamps и числовое время метрик намеренно не взаимозаменяемы.

`QueryStats.totalLinesProcessed` — nullable число обработанных Loki строк;
оно не является количеством совпадений или событий. Из остальных stats пока ничего
не публикуется. Отсутствующий показатель остаётся неизвестным. `warnings` сохраняются
как данные upstream; будущие сервисы должны учитывать их при оценке полноты и бюджете,
не использовать как инструкции и не писать их в диагностические логи.
S04 публикует только факт наличия warnings без исходного текста и ставит UNKNOWN
(либо PARTIAL при локальном сокращении).

## Ошибки

Ошибки используют существующий `ToolError` через `LokiOperationException`.
В сообщения не включаются URL, LogQL, HTTP body, credentials, tenant или исходная
цепочка исключений. Неуспешный HTTP status обрабатывается сразу по заголовкам:
error body не сохраняется и не разбирается, поэтому HTML/plain text/JSON от ingress
не попадают в диагностику. HTTP 200 с `status:error` даёт `UPSTREAM_QUERY_ERROR`
без исходных `error`/`errorType`.

| Условие | Code | retryable |
|---|---|---|
| Неверные локальные аргументы | `INVALID_ARGUMENT` | false |
| HTTP 400 | `UPSTREAM_BAD_REQUEST` | false |
| HTTP 401 / 403 | `UPSTREAM_UNAUTHORIZED` / `UPSTREAM_FORBIDDEN` | false |
| HTTP 404 | `ENDPOINT_UNAVAILABLE` | false |
| HTTP 429 | `UPSTREAM_RATE_LIMITED` | true |
| HTTP 408 / 504, timeout | `UPSTREAM_TIMEOUT` | true |
| Остальные HTTP 5xx | `UPSTREAM_UNAVAILABLE` | true |
| Другой status, включая redirects | `UPSTREAM_HTTP_ERROR` | false |
| Сетевая/TLS ошибка | `UPSTREAM_CONNECTION_ERROR` | true |
| Превышение body budget | `UPSTREAM_RESPONSE_TOO_LARGE` | false |
| Неверный JSON/shape/encoding | `UPSTREAM_INVALID_RESPONSE` | false |
| JSON `status:error` при HTTP 200 | `UPSTREAM_QUERY_ERROR` | false |
| Прерывание / вызов после close | `OPERATION_CANCELLED` | false |

`retryable` — подсказка, автоматического повторения в клиенте нет.
404 относится только к вызванному endpoint: клиент не помечает всё подключение
недоступным. MCP-обёртка этих ошибок проверена с data tools в S04.

## Проверки

```powershell
.\gradlew.bat test --tests 'ru.it_spectrum.ai.loki.mcp.client.*' --console=plain
.\gradlew.bat build --console=plain
```

`LokiHttpClientTest` использует только loopback HTTP server и локальный TCP socket
для зависшего TLS handshake. HTTP listener существует исключительно в тестах.
`LokiResponseDecoderTest` проверяет JSON fixtures без сети. Новых зависимостей нет.

Проверены URL/параметры/заголовки, точность времени, stream/metric/metadata DTO,
пустые и неверные ответы, auth/tenant isolation, ошибки, запрет redirects,
байтовые бюджеты с Content-Length и chunked, Unicode, таймауты заголовков/body/TLS,
отмена и отсутствие секретов в exceptions.
Контейнерная совместимость Loki 2.6.1 и 3.6.0 проверена в S04 отдельной задачей
`integrationTest` (см. [queries.md](queries.md)); live-проверки не запускались.
S05 расширяет эту задачу проверками `/series` и [discoverLogs](discovery.md),
включая метки/значения, ограниченную выборку и смешанные JSON/ECS/plain text.
