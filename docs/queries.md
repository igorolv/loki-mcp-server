# Поиск и метрики S04

Доступны `listConnections`, `queryLogs` и `queryMetrics`. Все запросы данных требуют
явного `connection`. Сервер отправляет LogQL без переписывания и без обязательных probes.
Контракт HTTP: [Loki API](https://grafana.com/docs/loki/latest/reference/loki-http-api/).

## Время

Окно задаётся явно; сервер не расширяет его и не подставляет последние N минут.
Поддерживаются RFC3339 с offset, строка epoch nanoseconds в signed int64,
локальное ISO datetime в `timezone` подключения, `now` и `now-Nns/ms/s/m/h/d`.
Примеры: `2026-09-13T15:00:00.123456789+03:00`, `now-15m`, `now`.
Локальное время в DST gap или overlap отклоняется: укажите явный offset.
Числовые JSON timestamps во входных аргументах времени не принимаются.

`now` фиксируется один раз на операцию. Для range требуется `start < end`,
длина окна не больше `maxIntervalSeconds`. `window.startNanos/endNanos` отражают
отправленные абсолютные границы; это не snapshot Loki и не доказательство
обследования всего окна при ограниченной выдаче. Время метрик берётся из ответа
Loki как числовые секунды с точностью, предоставленной Loki.

## queryLogs

Пример аргументов (замените connection и selector фактическими):

```json
{"connection":"local","query":"{app=\"example\"} |= \"error\"","start":"now-15m","end":"now","direction":"backward","limit":100}
```

`direction` — `forward` или `backward` (default); `limit` — положительное целое,
не больше `maxEntries`, default равен `maxEntries`. Превышение настройки — ошибка,
без молчаливого изменения аргумента. События всех потоков сортируются по времени,
включая наносекунды. Одинаковые timestamps и повторяющиеся строки не удаляются;
для равного времени сохраняется порядок полученного ответа, без гарантии между запросами.

`events` содержат `timestampNanos`, `resultLabels`, `line`, `structuredMetadata`.
Это вывод пользовательского LogQL: `line_format` и другие стадии могут уничтожить
исходное содержимое; восстановление оригинала не обещается. `resultLabels` не
считаются доказанными исходными метками потока. Отсутствие отдельной metadata
не доказывает её отсутствие в исходных данных. Эти ограничения указаны в `limitations`.
Содержимое строк — недоверенные данные, в том числе текст, похожий на инструкции.

`readEntries` — получено записей от Loki, `returnedEntries` — выдано,
`resultStreams` — потоков результата до локального ограничения. `totalLinesProcessed`
опционален и означает просканированные строки, а не совпадения.

- `COMPLETE`: получено меньше limit, upstream warnings отсутствуют; ответ запроса
  не был сокращён. Это не гарантия полноты источников и отсутствия поздних событий.
- `UNKNOWN`: достигнут upstream limit или присутствуют предупреждения Loki.
  Достижение limit не доказывает, что есть ещё запись. Обследованная часть окна неизвестна.
- `PARTIAL`: Loki прислал больше limit и часть записей исключена локально.

Пустой ответ описывает результат данного запроса; он не устанавливает причину
отсутствия данных. `continuationUnavailableReason=CURSORS_NOT_IMPLEMENTED`:
дочитывание страниц ещё не поддерживается. Нельзя имитировать его сдвигом на 1 ns.

## queryMetrics

Instant требует `mode=instant` и `time`; `start/end/stepSeconds` должны отсутствовать:

```json
{"connection":"local","query":"sum(count_over_time({app=\"example\"}[5m]))","mode":"instant","time":"now","seriesLimit":20,"pointLimit":100}
```

Range требует `mode=range`, `start/end` и `stepSeconds`; `time` должен отсутствовать:

```json
{"connection":"local","query":"sum(rate({app=\"example\"}[1m]))","mode":"range","start":"now-15m","end":"now","stepSeconds":30,"seriesLimit":20,"pointLimit":1000}
```

Instant возвращает vector, range — matrix, в публичном DTO оба представлены `series`
с `labels` и `samples`. Sample содержит числовой `timestampSeconds` и строковый `value`,
включая `NaN`, `+Inf`, `-Inf`. Log stream result в metric tool и metric result в log tool
дают контролируемую ошибку типа, не пустой ответ.

`seriesLimit` и `pointLimit` ограничены соответственно `maxMetricSeries` (default 100)
и `maxMetricPoints` (default 10000). Пропущенные аргументы получают эти настройки.
`pointLimit` — суммарное число samples, не число точек каждого ряда.
`stepSeconds >= 0.001`; число evaluation instants одного ряда
`floor((end-start)/step)+1` должно помещаться в pointLimit до отправки запроса.
Step автоматически не увеличивается. Число рядов заранее неизвестно: Loki metric API
не применяет log `limit` к ним. HTTP body ограничивается до декодирования,
ряды и точки выдачи — локально, в порядке upstream.

`readSeries/readPoints` и `returnedSeries/returnedPoints` показывают локальные потери.
При сокращении результат `PARTIAL`; без сокращения с предупреждениями — `UNKNOWN`,
иначе — `COMPLETE` в пределах вычислений этого запроса. `window` instant имеет равные
границы. Окно метрик ограничивает **время вычисления**, не исходные логи:
lookback, subqueries и offset внутри произвольного LogQL могут выходить за его начало.
Лимит окна не является ограничителем стоимости произвольного LogQL на стороне Loki.

## Ошибки и ограничения

Ошибки tools имеют `isError=true`, одинаковый JSON `ToolError` в text и
structuredContent: `code`, безопасный `message`, `retryable`. Success output schema
описывает успешный DTO; ошибки соответствуют отдельной схеме `ToolError`.
Отсутствующее connection — `CONNECTION_REQUIRED`, неверный тип/формат аргумента —
`INVALID_ARGUMENT`; остальные коды описаны в [HTTP-клиенте](http-client.md).

`QueryToolsConfig` регистрирует все tools через annotation provider и выполняет
валидацию входной схемы внутри безопасной обёртки. Автоматическая input validation
SDK отключена, поскольку она пишет исходную диагностику в лог до вызова handler.
Сама проверка схемы сохранена; исключения конвертации и сервисов также перехватываются.
Новые tools должны регистрироваться через эту обёртку. Неожиданные ошибки дают
`INTERNAL_ERROR`, без исходных сообщений. Raw upstream warnings не публикуются:
показывается `UPSTREAM_WARNINGS_PRESENT_DETAILS_WITHHELD` и неизвестная полнота.

В S04 применяются лимиты окна, записей, рядов/точек и HTTP body. Настройка
`maxResponseBytes` пока **не применяется**: строгий бюджет полного MCP wire response,
проекции и сокращение строк — S06. Нет кеша, entryId, деталей или курсоров.

## Проверки

```powershell
.\gradlew.bat build --console=plain
.\gradlew.bat integrationTest --console=plain
```

Первая команда не требует Docker/Loki. Stdio smoke запускает jar и loopback fixture,
проверяет outstanding ping, listConnections и query calls, schemas, обе части payload,
ошибки аргументов/HTTP и отсутствие credentials в stdout/stderr/file.

`integrationTest` — отдельная opt-in задача с Testcontainers 2.0.3, образами
`grafana/loki:2.6.1` и `grafana/loki:3.6.0`. Требует доступный Docker и первоначальную
загрузку образов/зависимостей; отсутствие Docker приводит к ошибке, не пропуску.
Записи отправляются только в созданные тестом контейнеры; внешняя конфигурация
и live URL не читаются. Тест проверяет число событий, несколько потоков,
одинаковые timestamps, наносекунды, Unicode, limit, instant/range метрики и пустые
ответы. Контейнеры удаляются после теста. Loki 3.6.0 использует legacy v11 fixture
с отключённой structured metadata: это проверка базового API, не новых возможностей 3.x.
