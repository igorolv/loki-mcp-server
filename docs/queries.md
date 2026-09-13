# Чтение логов, подсчёт и метрики

Контракт S09: все инструменты возвращают один текстовый `content`. Output schemas,
`structuredContent`, курсоры и проекция полей S06/S08 удалены. Целевой потребитель —
модель класса DeepSeek Flash / Haiku, которая видит только описания инструментов,
`instructions` сервера и текст ответа.

Общие параметры: `connection` обязателен (имя из `listConnections`); `start`
по умолчанию `now-1h`, `end` — `now`. Форматы времени: `now`, `now-15m` (ns/ms/s/m/h/d,
допустима краткая форма `15m`), RFC3339 с offset, локальное время в timezone подключения
или epoch nanoseconds. Окно ограничено `maxIntervalSeconds` подключения.

## queryLogs(connection, query, start, end, limit = 50, raw = false)

Один backward-запрос `query_range` с `limit` (не больше `maxEntries`). Строки печатаются
в хронологическом порядке:

```
{app="backend"} |= "ERROR" — dev, 2026-09-13 10:00:00–11:00:00 (+03:00), newest 50 of more:
10:12:03.123 ERROR backend  Connection refused to nsi-backend:8080 [trace=4f2a1b3c4d5e6f70…]
    java.net.ConnectException: Connection refused
    at java.base/sun.nio.ch.Net.connect0(Native Method)
    ... (37 frames skipped)
    Caused by: java.io.IOException: inner
    at x.Y.z(Y.java:9)
Shown 50 newest lines; oldest shown 2026-09-13T10:12:03.123+03:00. Older: repeat with end="2026-09-13T10:12:03.124+03:00". Too many lines? Narrow the query (add a filter or level) or use countLogs.
```

Строка: `HH:mm:ss.SSS LEVEL service  message [trace=…]`, время — в timezone подключения.
Дата указана в заголовке; при смене дня внутри страницы вставляется строка `--- yyyy-MM-dd ---`.
Правила извлечения полей — [discovery.md](discovery.md#нормализация-строки). Отсутствующее
значение печатается как `-`; строка не скрывается.

Stack trace (`error.stack_trace`, `stack_trace`, `stacktrace`, `exception` или
многострочный plain text с `at `): первая строка исключения, до 5 frames,
`... (N frames skipped)`, каждый `Caused by:`/`Suppressed:` с одним frame;
строки `... N more` опускаются. Сообщение обрезается до 400 code points с `…`;
переводы строк заменяются пробелами. `raw=true` печатает `HH:mm:ss.SSS {метки потока}  <строка Loki>`
без разбора, предел 4 000 code points — это путь к полной исходной строке. Метки печатаются
по алфавиту в виде `{app="x", pod="y"}` (S10): в режиме «покажи всё» модель видит pod/instance,
которые обычный режим прячет за именем сервиса.

Заголовок: `newest N of more:` если Loki вернул ровно `limit` строк, `all N lines:` если
меньше, `no matching lines.` если ноль. Футер: `Shown all N matching lines.`,
либо подсказка с готовым `end`. Значение `end` — самый старый показанный timestamp,
округлённый **вверх** до миллисекунды: Loki принимает `end` исключающим, поэтому
граница перечитывается (возможен один повтор), но строки с тем же миллисекундным
timestamp не теряются. Курсоров и хранения между вызовами нет; snapshot не обещается.
При пустом результате футер предлагает расширить окно, проверить метки через `discoverLogs`
или упростить фильтр.

## getLogContext(connection, selector, time, before = 20, after = 20)

Строки одного stream selector вокруг момента: `before` строк до него включительно и `after`
после. Контекст считается по числу строк, а не по времени, поэтому не зависит от плотности
потока. Два запроса `query_range`: backward с `end` = конец момента и `limit = before + 1`
(одна строка — сама цель), forward с `start` = конец момента и `limit = after`. Окно каждого
запроса — `maxIntervalSeconds` подключения в соответствующую сторону.

`selector` — только stream selector (та же проверка, что в `discoverLogs`); `|=` и `| json`
отклоняются с объяснением: строки-продолжения stack trace без фильтруемого текста должны быть
видны. `time` — любой формат `start`/`end` плюс голое время суток `10:12:03.123`, `10:12:03`,
`10:12` из строки страницы: оно берётся в timezone подключения как ближайшее такое время в
прошлом (сегодня, иначе вчера). Момент имеет точность текста: `10:12:03.123` покрывает
миллисекунду, `10:12:03` — секунду, RFC3339 без дробной части — секунду, epoch nanoseconds —
наносекунду. Строки внутри момента отмечены `>>>`; если таких нет, на их месте строка
`>>> (no line at exactly this time in {...}; lines before and after it follow)`, а лишняя
строка из запасного слота не показывается.

```
Context in {app="backend"} around 2026-09-13 10:12:03.123 (+03:00) — dev, lines: 20 before, 1 at that time, 20 after:
10:11:58.001 INFO  backend  Handling request [trace=4f2a1b3c4d5e6f70…]
...
>>> 10:12:03.123 ERROR backend  Connection refused to nsi-backend:8080 [trace=4f2a1b3c4d5e6f70…]
    java.net.ConnectException: Connection refused
10:12:03.130 -     backend  	at java.base/sun.nio.ch.Net.connect0(Native Method)
...
Earlier: repeat with time="2026-09-13T10:11:58.001+03:00", after=0. Later: repeat with time="2026-09-13T10:12:09.870+03:00", before=0. Full original line: queryLogs with raw=true and a narrow filter.
```

Футер: если строк до/после меньше запрошенного — `No earlier/later lines within 24h ...`
(это предел окна, не доказательство отсутствия вообще); иначе готовые `time` для
продолжения в каждую сторону. Если все `before + 1` строк попали в момент (например,
секунда точности в нагруженном сервисе) — совет передать время с миллисекундами или
сузить selector. При нехватке бюджета строки отбрасываются с более длинной стороны,
отмеченные строки не отбрасываются: `Output limit reached: showing N before and M after of K fetched lines.`

## countLogs(connection, query, start, end, groupBy)

Сервер сам строит metric LogQL; `query` — обычный log query, начинающийся с `{`.

- Без `groupBy`: instant query `sum(count_over_time(<query> [<окно>]))` на `end`:
  `1 523 lines match {app="backend"} |= "ERROR" in 2026-09-13 10:00:00–11:00:00 (+03:00) (dev).`
- `groupBy="<label>"`: `sum by (<label>) (count_over_time(...))`, таблица по убыванию,
  до 50 значений, пустая метка — `(none)`.
- `groupBy="time"`: range query с «круглым» шагом не меньше `окно/12` (1s … 1d: 1m, 2m, 5m,
  15m, 30m, 1h, 2h…), бакеты выровнены по часам — оценки идут по кратным шагу от epoch,
  как их выравнивает сам Loki при split by interval (запрос на другие моменты давал бы
  удвоенные бакеты; найдено на живом DEV, S11). Крайние бакеты могут выходить за окно,
  поэтому заголовок называет выровненный интервал. Каждая строка — начало бакета.
  Отметка `<- spike` ставится, когда бакет ≥ 5 и ≥ 3 × медиана бакетов (при нулевой
  медиане — 3 × среднее). Это простое правило, не анализ.

Метрический вход (`sum(...)`, `rate(...)`) отклоняется с советом использовать `queryMetrics`.

## queryMetrics(connection, query, start, end, step)

Всегда range query. `step` — `30s`, `5m`, `1h`; по умолчанию ближайший «круглый» шаг
не меньше `окно/20` (1s … 1d). Число оценок на ряд не должно превышать `maxMetricPoints`.

```
sum by (level) (rate({app="backend"}[5m])) — dev, 2026-09-13 10:00:00–11:00:00 (+03:00), step 5m, 2 series:
{level="error"}
  09-13 10:00  0.5
  09-13 10:05  0.7
{level="warn"}
  09-13 10:00  0.1
Output trimmed to 2 series / 40 points (connection limits). Aggregate with sum by (...) or use a larger step.
```

Значения печатаются строками Loki (`NaN`, `+Inf` сохраняются). Время точек —
`HH:mm:ss` при шаге меньше минуты, иначе `MM-dd HH:mm`. Log query на входе отклоняется
с советом использовать `queryLogs`/`countLogs`; instant-режима нет.

## Ошибки

Ошибка — текст `Error <CODE>: <что не так и что сделать>` с `isError=true`.
HTTP 400 и `status=error` от Loki передаются как `Loki rejected the query: <текст Loki>`
(до 400 символов, без управляющих символов): по нему модель чинит LogQL.
Остальные статусы (401/403/404/429/5xx) и тексты upstream по-прежнему скрываются.
Ошибки аргументов могут повторять значение аргумента модели (например, непонятное время),
но никогда — credentials, URL или тексты других статусов.

## Предел размера

`maxResponseBytes` подключения минус 512 байт на JSON-RPC envelope — бюджет текста.
`queryLogs` отбрасывает самые старые строки, пока страница не поместится, и пишет
`Output limit reached: showing N newest of M fetched lines.`; если не помещается даже одна
строка — `Error RESPONSE_BUDGET_EXCEEDED`. `discoverLogs` сначала укорачивает пример,
затем убирает его. Обёртка `QueryToolsConfig` дополнительно проверяет фактический размер
текста как последнюю защиту.

## Проверки

`gradlew.bat build --console=plain` — unit-тесты формата строки, сжатия stack trace,
футера, бюджета, countLogs/queryMetrics, getLogContext (два запроса, отметка, точность
времени, обрезка вокруг цели) и stdio smoke на реальном jar
(`instructions`, tools/list без output schema, 16 outstanding вызовов с разными бюджетами,
ошибки без секретов, текст ошибки Loki 400). `gradlew.bat integrationTest --console=plain`
— Loki 2.6.1/3.6.0: страница, продолжение по `end`, raw с метками, контекст
(строки в момент, точное время, момент без строк, отказ pipeline), count/groupBy/time,
метрики, ошибка парсера, discovery и значения метки.
