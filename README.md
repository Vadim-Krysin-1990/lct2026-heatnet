# heatnet — сервис моделирования трасс подключения к тепловым сетям

Хакатон «Лидеры цифровой трансформации» 2026, задача города №2 (ДИТ Москвы).
Команда **Intelligence**: Вадим Крысин (капитан, Data Scientist), Анна Прохорова (backend).

Сервис принимает один GeoJSON конкурсной структуры (существующая тепловая сеть, камеры, источник,
точки подключения перспективных ОКС, пространственные ограничения) и автоматически строит до трёх
содержательно разных вариантов подключения всех ОКС: маршруты, точки врезки, новые камеры,
технические узлы, расходы, условные диаметры, проверку предельной длины, реконструкцию существующей
сети и камер, стоимость и ранжирование. Результат — один GeoJSON по техническому приложению §10.

## Стек (по ТЗ раздел 3)

Java 11 · Spring Boot 2.6.3 · Maven · PostgreSQL 14 · springdoc-openapi-ui 1.7.0 · JTS 1.19 · proj4j ·
docker-compose 1.29.2. Полностью офлайн, без UI.

## Запуск

```bash
docker-compose up --build -d
# Swagger UI:  http://localhost:8080/swagger-ui.html
```

Синхронный расчёт (файл → выходной GeoJSON одним запросом):

```bash
curl -F "file=@data/samples/contest_dataset.geojson" http://localhost:8080/api/process -o result.geojson
```

Асинхронный контур для больших файлов (до 3 ГБ, ТЗ 3.2):

```bash
curl -F "file=@input.geojson" http://localhost:8080/api/jobs          # → {"id": "...", "status": "QUEUED"}
curl http://localhost:8080/api/jobs/<id>                               # статус, время расчёта, лучший вариант
curl http://localhost:8080/api/jobs/<id>/result -o result.geojson      # выгрузка
curl http://localhost:8080/api/jobs/<id>/diagnostics                   # диагностика входных данных и расчёта
```

Правила и справочники (табл. 4.1, 4.2, 5.1, 8.2, 8.3, 9 техприложения) лежат в
`src/main/resources/rules/*.yml`; свою копию можно положить в каталог `HEATNET_RULES` и перечитать
через `POST /api/rules/reload` — без перекомпиляции.

## Сборка без Docker

```bash
mvn -q package -DskipTests
HEATNET_DB_URL=jdbc:postgresql://localhost:5432/heatnet java -jar target/heatnet.jar
```

Тесты: `mvn test` (сквозной прогон конкурсного набора пишет `target/contest_result.geojson`).

## Структура

| Пакет | Назначение |
|---|---|
| `ingest` | потоковый парсер GeoJSON (Jackson streaming), валидация и диагностика входа |
| `network` | топология существующей сети: цепочка к источнику по `upstream_object_id` или по геометрии |
| `routing` | поле препятствий по табл. 5.1, растровое окно, A* по 8 направлениям (повороты только 45°/90°), упрощение ломаной |
| `planner` | выход из ОКС по нормали, стратегии вариантов, сборка дерева новой сети |
| `hydraulics` | расходы по дереву, подбор ДУ, предельная длина, техузлы, врезки, камеры, реконструкция |
| `export` | выходной GeoJSON строго по ТП §10 |
| `jobs`, `api` | очередь расчётов в PostgreSQL, REST и Swagger |

Описание алгоритма, выходных данных и границ применения — в `docs/`.
