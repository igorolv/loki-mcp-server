# Обнаружение данных и нормализация строки

## discoverLogs(connection, selector, start, end, label)

Текстовый обзор того, что есть в логах, и готовый selector для следующего вызова.
`start`/`end` по умолчанию — последний час. С `label` — только значения одной метки
(см. ниже).

Без `selector`: имена меток из `/labels` (до 30, по алфавиту), значения каждой —
из `/label/<name>/values`. Выборка строк берётся по первой метке из `serviceLabels`
подключения, которая есть на стенде (`{applicationName=~".+"}`), иначе по первой метке.

С `selector` (только stream selector в фигурных скобках, значения в двойных кавычках,
без фильтров и pipelines): метки и значения из `/series` (первые 2000 потоков),
выборка — backward `query_range` по этому selector, до 20 строк.

```
Streams matching {namespace="dev"} in 2026-09-13 10:00:00–11:00:00 (+03:00): 84.
Labels:
  applicationName: nsi-backend, ssj-backend, ssj-ui-backend (+2 more)
  level: debug, error, info, warn
  pod: 84 distinct values (high cardinality, not listed)
Line format (20 newest lines sampled): JSON 19, plain text 1.
Levels seen: ERROR, INFO, WARN.
JSON fields (after | json): _timestamp, log_level, message, service_name, error_stack_trace, traceId (+3 more).
Example line: {"@timestamp":"2026-09-13T07:12:03.123Z","log":{"level":"ERROR"},...
Next: use countLogs or queryLogs with a selector like {namespace="dev", applicationName="nsi-backend"}; filter JSON fields with | json, e.g. | json | log_level=~"(?i)error"; filter text with |= "substring".
```

Правила:

- До 10 значений метки, остальное `(+N more)`; больше 20 различных значений —
  «high cardinality, not listed». Значения обрезаются до 60 символов.
- Имена JSON-полей печатаются так, как их видит `| json` в Loki: вложенные ключи
  соединяются `_`, прочие символы заменяются `_` (`log.level` → `log_level`,
  `@timestamp` → `_timestamp`). Порядок — по числу строк, где поле встретилось, до 30 имён.
- `Levels seen` — значения уровня из меток, structured metadata или строки в верхнем регистре.
- Пример — самая новая строка выборки, до 300 символов; при нехватке бюджета
  укорачивается до 75, затем убирается.
- Подсказка `Next:` добавляет первое значение первой найденной метки из `serviceLabels`,
  если selector её ещё не ограничивает; фильтр уровня предлагается по найденному полю
  (`log_level`, `level`, `severity`, `lvl`) или по метке `level`.
- Отсутствие метки или поля в выборке не доказывает их отсутствие в интервале;
  описание инструмента говорит об этом модели.
- Недоступный `/label/<name>/values` даёт `(values not available)`; ошибка выборки строк —
  `No lines sampled in this window; fields are unknown.`; ошибка `/series` или `/labels`
  возвращается как ошибка инструмента.

## discoverLogs(label=...)

Обзор показывает не больше 10 значений метки; чтобы получить все (например, все сервисы
стенда), модель передаёт `label="applicationName"`. Ответ — значения из
`/label/<name>/values` (с `query=<selector>`, если selector задан), по алфавиту, по одному
на строку, до 200; выборка строк и подсказка `Next:` не выполняются:

```
Values of applicationName in streams matching {namespace="dev"}, 2026-09-13 10:00:00–11:00:00 (+03:00) (dev): 37.
auth-service
nsi-backend
...
(+12 more; narrow with selector)
```

Значения обрезаются до 200 символов; при нехватке бюджета список укорачивается с конца,
счётчик `(+N more; narrow with selector)` остаётся честным. Пустой ответ говорит проверить
имя метки в обзоре без `label` или расширить окно. Неверное имя метки — ошибка аргумента.

## Нормализация строки

`EventNormalizer` даёт `View(format, level, service, message, traceId, stackTrace, jsonFields)`
для `queryLogs` и discovery. Разбирается только строка; метки и metadata не переопределяются ею.

| Поле | Источники по приоритету |
|---|---|
| level | метки `level`, `detected_level`, `severity`, `lvl` → structured metadata те же → JSON `log.level`, `level`, `severity`, `lvl`, `@l` → для plain text первое слово `TRACE/DEBUG/INFO/WARN/WARNING/ERROR/FATAL` в первых 120 символах |
| service | метки из `serviceLabels` подключения по порядку (default `applicationName, service_name, service, app, container, job`) → JSON `service.name`, `service`, `app`, `application`, `applicationName` |
| message | JSON `message`, `msg`, `@message`, `event`, `@m`, иначе строка целиком; для plain text с frames — первая строка |
| traceId | metadata → JSON → метки: `traceId`, `trace.id`, `trace_id`, `traceID`, `trace` |
| stackTrace | JSON `error.stack_trace`, `stack_trace`, `stacktrace`, `stackTrace`, `exception`, `throwable`; для plain text — остаток после первой строки, если есть `\n\tat ` |

JSON распознаётся только у строк, начинающихся с `{` и являющихся объектом; вложенные
объекты раскрываются до глубины 20, не больше 100 полей, строка не длиннее 256 KiB.
Ключи с точками (`"service.name"`) и вложенные объекты (`"service":{"name"}`) дают
один и тот же dotted path. Массивы не раскрываются. Всё остальное — plain text.
Уровень приводится к верхнему регистру. Значения не угадываются.

## Проверки

`gradlew.bat build --console=plain`: `EventNormalizerTest` (ECS, плоские ключи,
приоритет меток, plain text, лимиты), `DiscoveryServiceTest` (selector и без него,
капы значений, ошибки endpoint, бюджет, режим `label`), stdio smoke. `gradlew.bat integrationTest
--console=plain`: discovery на Loki 2.6.1/3.6.0 (3.x добавляет `service_name`
самостоятельно — тесты не предполагают точный набор меток).
