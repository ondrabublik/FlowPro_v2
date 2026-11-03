"""flat plate x in [0, 2], upstream symmetry x in [-0.5, 0], height 1; block mesh refined to the wall"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # geo2d.py
from geo2d import Geometry
out = sys.argv[1]; ny = int(sys.argv[2]) if len(sys.argv) > 2 else 40
y1 = float(sys.argv[3]) if len(sys.argv) > 3 else 5e-5
os.makedirs(out, exist_ok=True)
# grading gy = last/first cell for height H=1 and first cell y1 (bisection on the ratio)
def first_cell(g, n, H=1.0):
    r = g ** (1.0 / (n - 1)); return H * (r - 1) / (r**n - 1) if r != 1 else H / n
lo, hi = 1.0, 1e8
for _ in range(200):
    mid = (lo * hi) ** 0.5
    lo, hi = (mid, hi) if first_cell(mid, ny) > y1 else (lo, mid)
gy = lo
print(f"ny = {ny}, gy = {gy:.4g}, first cell {first_cell(gy, ny):.3g}")
g = Geometry(block_triangles=False)
v = [g.vertex(-0.5, 0), g.vertex(0, 0), g.vertex(2, 0), g.vertex(2, 1), g.vertex(0, 1), g.vertex(-0.5, 1)]
# faces: 0 bottom, 1 right, 2 top, 3 left
g.block([v[0], v[1], v[4], v[5]], 12, ny, 0.1, gy, types=(-4, 0, -4, -2), ale=(0, 0, 0, 0))
g.block([v[1], v[2], v[3], v[4]], 50, ny, 20, gy, types=(-1, -3, -4, 0), ale=(0, 0, 0, 0))
g.save(os.path.join(out, "geometry.geo2d"))
