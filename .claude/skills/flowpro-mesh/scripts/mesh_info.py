"""Summary of a FlowPro mesh directory: element counts, boundary types, ALE tags, cell sizes, quality.

usage: python mesh_info.py simulations/NAME/mesh
"""
import collections
import math
import os
import sys


def rows(path):
    with open(path) as f:
        return [line.split() for line in f if line.strip()]


def main(mesh):
    pts = [tuple(map(float, r[:2])) for r in rows(os.path.join(mesh, "vertices.txt"))]
    elems = [list(map(int, r)) for r in rows(os.path.join(mesh, "elements.txt"))]
    types = collections.Counter(int(r[0]) for r in rows(os.path.join(mesh, "elementType.txt")))
    print(f"vertices {len(pts)}, elements {len(elems)}, element types {dict(types)}")

    bt = rows(os.path.join(mesh, "boundaryType.txt"))
    per_type = collections.defaultdict(lambda: [0, 0.0])
    for t, a, b in bt:
        (xa, ya), (xb, yb) = pts[int(a)], pts[int(b)]
        per_type[int(t)][0] += 1
        per_type[int(t)][1] += math.hypot(xb - xa, yb - ya)
    for t in sorted(per_type):
        n, length = per_type[t]
        print(f"boundary type {t:3d}: {n:6d} faces, length {length:.6g}, mean face {length / n:.3g}")

    ale_path = os.path.join(mesh, "boundaryTypeALE.txt")
    if os.path.isfile(ale_path):
        tags = collections.Counter(int(r[0]) for r in rows(ale_path))
        print(f"ALE tags (boundaryTypeALE.txt): {dict(sorted(tags.items()))}  (1 = fixed, k >= 2 = body k-1)")

    # quality of triangles / quads: minimum angle and size
    min_angles, sizes, bad = [], [], 0
    for e in elems:
        p = [pts[i] for i in e]
        n = len(p)
        area = 0.5 * sum(p[i][0] * p[(i + 1) % n][1] - p[(i + 1) % n][0] * p[i][1] for i in range(n))
        if area <= 0:
            bad += 1
        sizes.append(math.sqrt(abs(area)))
        angs = []
        for i in range(n):
            ax, ay = p[i - 1][0] - p[i][0], p[i - 1][1] - p[i][1]
            bx, by = p[(i + 1) % n][0] - p[i][0], p[(i + 1) % n][1] - p[i][1]
            c = (ax * bx + ay * by) / (math.hypot(ax, ay) * math.hypot(bx, by))
            angs.append(math.degrees(math.acos(max(-1, min(1, c)))))
        min_angles.append(min(angs))
    min_angles.sort()
    print(f"min angle: min {min_angles[0]:.1f} deg, 1% quantile {min_angles[len(min_angles) // 100]:.1f}, "
          f"median {min_angles[len(min_angles) // 2]:.1f}")
    print(f"sqrt(area): min {min(sizes):.3g}, max {max(sizes):.3g}; non-positive orientation: {bad}")
    derived = [f for f in ("wallDistance.txt", "neighborsALE.txt", "blendingFunctions.txt") if os.path.isfile(os.path.join(mesh, f))]
    if derived:
        print("derived files (delete them after changing the mesh): " + ", ".join(derived))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
