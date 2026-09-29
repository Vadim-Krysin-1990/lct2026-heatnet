"""Рисует датасет и результат расчёта в UTM 37N: здания с буфером, ограничения, сеть, трассы по вариантам.
Использование: plot_result.py <dataset.geojson> <result.geojson> <out.png> [variant_id]"""
import json, sys, math
from shapely.geometry import shape
from shapely.ops import transform
import pyproj
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt
tr = pyproj.Transformer.from_crs("EPSG:4326", "EPSG:32637", always_xy=True).transform
def load(p):
    d = json.load(open(p)); out = []
    for f in d["features"]:
        g = transform(tr, shape(f["geometry"])) if f.get("geometry") else None
        out.append((f["properties"], g))
    return out
ds = load(sys.argv[1]); res = load(sys.argv[2]); png = sys.argv[3]; want = sys.argv[4] if len(sys.argv) > 4 else "1"
fig, ax = plt.subplots(figsize=(18, 15), dpi=110)
for p, g in ds:
    t = p.get("object_type")
    if t == "restriction":
        rt = p.get("restriction_type")
        col = {"oks": "#c8c8c8", "water": "#9ecae1", "railway": "#f0c070"}.get(rt, "#e0b0e0")
        for poly in getattr(g, "geoms", [g]):
            ax.fill(*poly.exterior.xy, color=col, alpha=0.9, lw=0.3, ec="#888")
            if rt == "oks":
                b = poly.buffer(5.0)
                ax.plot(*b.exterior.xy, color="#e0e0e0", lw=0.4, ls=":")
        if rt == "oks": ax.annotate(str(p["id"]), (g.centroid.x, g.centroid.y), fontsize=4, color="#555", ha="center")
for p, g in ds:
    t = p.get("object_type")
    if t == "heat_network":
        ax.plot(*g.xy, color="#d62728", lw=2.2)
    elif t == "heat_chamber":
        ax.plot(g.x, g.y, "s", color="#d62728", ms=6)
    elif t == "source":
        ax.plot(g.x, g.y, "*", color="#000", ms=16)
    elif t == "oks_connection_point":
        ax.plot(g.x, g.y, "o", color="#2ca02c", ms=7, mec="k")
        ax.annotate(f'{p["id"]} ({p["flow_tph"]})', (g.x+4, g.y+4), fontsize=7, color="#2ca02c", weight="bold")
summary = None
for p, g in res:
    if p.get("variant_id") != want: continue
    t = p["object_type"]
    if t == "heat_network":
        col = "#ff7f0e" if p["laying_method"] == "special" else "#1f77b4"
        ax.plot(*g.xy, color=col, lw=2.0 if p["diameter"] >= 150 else 1.4)
        ax.annotate(f'ДУ{p["diameter"]}', (g.centroid.x, g.centroid.y), fontsize=6.5, color=col,
                    ha="center", va="center",
                    bbox=dict(boxstyle="round,pad=0.12", fc="white", ec="none", alpha=.75))
    elif t == "heat_chamber":
        ax.plot(g.x, g.y, "D", color="#1f77b4", ms=6, mec="k")
    elif t == "tie_in":
        ax.plot(g.x, g.y, "^", color="#9467bd", ms=10, mec="k")
    elif t == "technical_node":
        ax.plot(g.x, g.y, ".", color="#000", ms=6)
    elif t == "heat_network_reconstruction":
        ax.plot(*g.xy, color="#e377c2", lw=5, alpha=0.5)
    elif t == "variant_summary":
        summary = p
# Картинка идёт в презентацию: ни осей, ни сетки, ни рамки, ни заголовка — подпись к карте
# делается в самом слайде, иначе она дублируется и мельчает.
ax.set_aspect("equal")
ax.set_axis_off()
for side in ax.spines.values():
    side.set_visible(False)
plt.tight_layout(); plt.savefig(png, bbox_inches="tight", facecolor="white"); print("saved", png)
