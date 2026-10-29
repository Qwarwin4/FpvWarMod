# 3D-модели

Дроны, «Корнет», грузовик и очки — настоящие 3D-модели с Sketchfab. Скрипт `tools/gen_meshes.py` их поворачивает,
снимает то, что мод рисует сам (боевые части, пропеллеры, колёса), упрощает сетку и ставит на место прежних
кубических моделей. Исходники в репозиторий не входят (лежат в `../models/fpv`).

| Что в моде | Модель | Автор | Лицензия |
|---|---|---|---|
| FPV-дрон, его пропеллер | [FPV kamikaze drone Low-poly](https://sketchfab.com/3d-models/87f5bbb5b08641b782bc084ddd4082a7) | Kvasovich | CC BY 4.0 |
| Mavic 3 | [Дрон Mavic 3 / Drone Mavic 3](https://sketchfab.com/3d-models/4cd812b26a5c4d8e90777db3ebf0db6e) | maks_modeller | CC BY 4.0 |
| Shahed-136, Герань-2 | [Shahed 136](https://sketchfab.com/3d-models/bc8754128c9d48c48baadeff1db8f0c7) | faintastic | CC BY 4.0 |
| Shahed-238, Герань-5 | [Shahed 238 Drone](https://sketchfab.com/3d-models/10b7f80a149247748d8912bd0f59d517) | Rudy27 | CC BY 4.0 |
| ПТРК «Корнет» | [9M133 Kornet / AT-14 Spriggan](https://sketchfab.com/3d-models/8b1d8e081c9744598b2ec4127d1c72df) | XEPCOH_552 | CC BY 4.0 |
| Пусковой грузовик (КамАЗ) | [KamAZ-5340](https://sketchfab.com/3d-models/326a0fc70fd1470085b85e2486ca32bc) | 42manako | CC BY 4.0 |
| FPV-очки | [Rodin FPV Goggles DJI](https://sketchfab.com/3d-models/56197e46626341acb8b7924494f9e8c9) | line-o | CC BY 4.0 |

Перекрашено: Герань-2 и Герань-5 — в чёрный, «Корнет» — из песочного в армейский зелёный, Mavic — в тёмно-серый.

# Звуки

Моторы, двигатели, пушка и пуски — настоящие записи с [freesound.org](https://freesound.org) под лицензией CC0
(общественное достояние). Скрипт `tools/real_sounds.py` их режет и закольцовывает; остальное синтезирует `gen_assets.py`.

| Звук | Запись | Автор |
|---|---|---|
| fpv_motor | [Tiny Hawk 2 short flight](https://freesound.org/s/677833/) | Sadiquecat |
| mavic_motor | [Dji Mavic Mini 2 take-off](https://freesound.org/s/683300/) | Sadiquecat |
| shahed_engine | [two-stroke Trabant engine 01](https://freesound.org/s/412819/) | blukotek |
| jet_engine | [Jet Constant Noise](https://freesound.org/s/437888/) | craigsmith |
| truck_engine | [Truck Engine Idle Loops](https://freesound.org/s/187564/) | qubodup |
| missile_motor | [Rocket Boost Engine Loop](https://freesound.org/s/146770/) | qubodup |
| cannon_30 | [Autocannon Three Shot Burst](https://freesound.org/s/854186/) | qubodup |
| atgm_launch | [Launching Anti-Tank Missiles](https://freesound.org/s/184274/) | qubodup |
| shahed_launch | [Rocket Launch](https://freesound.org/s/521377/) | Jarusca |
