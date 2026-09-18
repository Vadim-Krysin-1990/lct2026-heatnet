# Выходные данные

Один файл GeoJSON `FeatureCollection` в WGS 84 (CRS84). Все варианты — в одном массиве `features`,
различаются `variant_id`. У каждого типа только собственный набор атрибутов (ТП §10). Точки
подключения ОКС повторно не передаются.

| `object_type` | Геометрия | Атрибуты |
|---|---|---|
| `heat_network` | LineString | `id`, `variant_id`, `start_node_id` (узел ближе к источнику), `end_node_id`, `flow_tph`, `diameter`, `length` (м), `laying_method` (`base`/`special`), `depth_start`, `depth_end` (null в двумерной задаче), `cost` |
| `tie_in` | Point | `id`, `variant_id`, `existing_object_id`, `existing_object_type` (`heat_network`/`heat_chamber`), `existing_diameter`, `required_diameter`, `cost` (5 000 000) |
| `heat_network_reconstruction` | LineString (фактическая часть существующего участка) | `id`, `variant_id`, `existing_object_id`, `existing_flow_tph`, `added_flow_tph`, `calculated_flow_tph`, `existing_diameter`, `required_diameter`, `length`, `cost` |
| `heat_chamber` | Point | `id`, `variant_id`, `diameter` (max ДУ примыкающих), `cost` |
| `heat_chamber_reconstruction` | Point | `id`, `variant_id`, `existing_object_id`, `existing_diameter`, `required_diameter`, `cost` |
| `technical_node` | Point | `id`, `variant_id` |
| `variant_summary` | null | `id`, `variant_id`, `rank`, `construction_cost`, `chamber_construction_cost`, `tie_in_cost`, `reconstruction_cost`, `chamber_reconstruction_cost`, `unconnected_penalty`, `calculated_cost`, `new_network_length`, `reconstruction_length`, `length`, `score`, `unconnected_oks_ids` |

Соглашения:
- `id` уникальны во всём файле: префикс `v<номер варианта>_` (`v1_seg_3`, `v1_tie_1_2`, `v1_ch_4`,
  `v1_node_2`, `v1_recon_1`, `summary_1`).
- `start_node_id`/`end_node_id` ссылаются на `tie_in`, `heat_chamber`, `technical_node` или на `id`
  входной точки подключения `oks_connection_point` (для конечного участка к зданию).
- Несколько ветвей, входящих в одну точку врезки, — несколько объектов `tie_in` с общей геометрией
  (`v1_tie_1_1`, `v1_tie_1_2`): каждая независимая врезка стоит 5 млн (Q&A 16.09).
- `cost` округлена до рубля, длины — до 0,01 м, `score` — до 0,001.
- Сводка `calculated_cost` = сумма пяти составляющих + штраф; `length` = новая сеть + реконструкция.

Диагностика (`GET /api/jobs/{id}/diagnostics`, заголовки `X-Compute-Millis`, `X-Diagnostics-Warnings`
у `/api/process`): ошибки входа (`INVALID_GEOMETRY`, `MISSING_DIAMETER`, `NO_SOURCE` …),
предупреждения о восстановленных значениях (`MISSING_FLOW`, `TOPOLOGY_FROM_GEOMETRY`,
`CHAMBER_DIAMETER_DERIVED`), события расчёта (`ROUTE_NOT_FOUND`, `LENGTH_LIMIT_UPSIZE`,
`LENGTH_LIMIT_EXCEEDED`, `EXIT_NEAR_NEIGHBOUR`, `VARIANT`, `VARIANT_DUPLICATE`) и статистика.
