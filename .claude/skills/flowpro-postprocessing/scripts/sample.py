"""Samples FlowPro text results along a line or at points.

First export the results (in directory FlowPro):
    java -jar FlowPro.jar postprocessing density pressure velocity -ftxt -d2 -g GEOM -s SIM [-iITER] [-oDIR]
(-d2: values in the points of every element divided into 2 parts per edge, i.e. the DG polynomial;
-iITER takes snapshot ITER of the animation directory). Output: DIR/vertices.txt + DIR/<name>.txt.

usage:
    python sample.py OUTPUT_DIR --line X0 Y0 X1 Y1 [N] [--csv line.csv] [q1 q2 ...]
    python sample.py OUTPUT_DIR --points X Y [X Y ...] [q1 ...]
Vector quantities (velocity) are split into <name>_x, <name>_y (xvelocity for the 1st component of velocity).
Without quantity names all <name>.txt files of the directory except vertices/elements are used.
"""
import argparse
import os

import numpy as np
from scipy.interpolate import LinearNDInterpolator, NearestNDInterpolator


def load(out_dir, names):
    xy = np.loadtxt(os.path.join(out_dir, "vertices.txt"), ndmin=2)[:, :2]
    if not names:
        names = sorted(f[:-4] for f in os.listdir(out_dir)
                       if f.endswith(".txt") and f not in ("vertices.txt", "elements.txt"))
    cols = {}
    for n in names:
        v = np.loadtxt(os.path.join(out_dir, n + ".txt"), ndmin=2)
        if v.shape[1] == 1:
            cols[n] = v[:, 0]
        else:
            if n == "velocity":
                cols["xvelocity"] = v[:, 0]
                cols["yvelocity"] = v[:, 1]
            else:
                for k, c in zip("xyz", v.T):
                    cols[f"{n}_{k}"] = c
    return xy, cols


def interpolate(xy, cols, pts):
    # duplicate points (shared vertices of the subdivided elements) are averaged by the interpolator
    res = {}
    for n, v in cols.items():
        f = LinearNDInterpolator(xy, v)
        val = f(pts)
        bad = np.isnan(val)
        if bad.any():  # outside of the convex hull (e.g. on a curved boundary): nearest value
            val[bad] = NearestNDInterpolator(xy, v)(pts[bad])
        res[n] = val
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out_dir")
    ap.add_argument("names", nargs="*")
    ap.add_argument("--line", nargs="+", type=float, metavar="X0 Y0 X1 Y1 [N]")
    ap.add_argument("--points", nargs="+", type=float)
    ap.add_argument("--csv")
    a = ap.parse_args()
    xy, cols = load(a.out_dir, a.names)
    if a.line:
        x0, y0, x1, y1 = a.line[:4]
        n = int(a.line[4]) if len(a.line) > 4 else 200
        s = np.linspace(0, 1, n)
        pts = np.column_stack([x0 + s * (x1 - x0), y0 + s * (y1 - y0)])
        s = s * np.hypot(x1 - x0, y1 - y0)
    elif a.points:
        pts = np.array(a.points).reshape(-1, 2)
        s = np.arange(len(pts), dtype=float)
    else:
        ap.error("--line or --points is required")
    res = interpolate(xy, cols, pts)
    header = ["s", "x", "y"] + list(res)
    table = np.column_stack([s, pts] + [res[n] for n in res])
    if a.csv:
        np.savetxt(a.csv, table, delimiter=",", header=",".join(header), comments="")
        print(f"{len(table)} points written into {a.csv}")
        for n in res:
            print(f"{n}: min {np.nanmin(res[n]):.5g}, max {np.nanmax(res[n]):.5g}")
    else:
        print(",".join(header))
        for row in table:
            print(",".join(f"{v:.6g}" for v in row))


if __name__ == "__main__":
    main()
