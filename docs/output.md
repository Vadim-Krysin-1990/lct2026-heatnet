# Выходные данные

Редакция технического приложения от 21.09.2026, раздел 7.

Результат каждого режима — отдельный файл GeoJSON `FeatureCollection` в WGS 84. Варианты внутри файла
различаются `variant_id`. Идентификаторы и ссылочные поля соответствуют значениям `id`. Дополнительные
свойства допускаются и при проверке обязательной части игнорируются.

| `object_type` | Геометрия | Атрибуты |
|---|---|---|
| `heat_network` | LineString | `id`, `variant_id`, `start_node_id`, `end_node_id`, `flow_tph`, `diameter`, `length` (м, по горизонтальной проекции), `laying_method` (`base`/`special`), `depth_start`, `depth_end` (null в двумерном режиме), `cost` |
| `heat_chamber` | Point | `id`, `variant_id`, `diameter` (наибольший ДУ примыкающих участков), `cost` |
| `technical_node` | Point | `id`, `variant_id` |
| `variant_summary` | null | `id`, `variant_id`, `rank`, `construction_cost`, `chamber_construction_cost`, `existing_chamber_tie_in_count`, `existing_chamber_tie_in_cost`, `unconnected_penalty`, `calculated_cost`, `new_network_length`, `score`, `unconnected_oks_ids` |

Отдельных объектов места присоединения, реконструкции сети и реконструкции камер в выходных данных нет:
присоединение выполняется через тепловую камеру, а реконструкция в расчётной модели не выполняется.

Соглашения:
- `id` уникальны во всём файле: префикс `v<номер варианта>_` (`v1_seg_3`, `v1_ch_4`, `v1_node_2`,
  `summary_1`).
- `start_node_id` и `end_node_id` совпадают с геометрическими концами LineString и ссылаются на
  входную точку подключения (`oks_connection_point`), существующую тепловую камеру (её входной `id`),
  новую тепловую камеру или технический узел. Поворот без изменения параметров остаётся внутренней
  вершиной линии и отдельного узла не требует.
- Стоимость новой камеры включает присоединение к существующей сети. Врезка 5 000 000 ₽ начисляется
  только за каждый новый участок, заканчивающийся в существующей камере.
- `calculated_cost` = `construction_cost` + `unconnected_penalty`;
  `construction_cost` = участки + новые камеры + врезки в существующие камеры.
- `score` = 0,7 · (`calculated_cost` / 25 000 000) + 0,3 · (`new_network_length` / 100).

Диагностика (`GET /api/jobs/{id}/diagnostics`, заголовки `X-Compute-Millis`, `X-Diagnostics-Warnings`
у `/api/process`): ошибки входа, восстановленные значения, события расчёта (`TRACE_QUALITY`,
`LENGTH_LIMIT_UPSIZE`, `ROUTE_NOT_FOUND`, `GRID_BEARING`, `VARIANT`, `VARIANT_NOT_OFFERED`) и статистика.
