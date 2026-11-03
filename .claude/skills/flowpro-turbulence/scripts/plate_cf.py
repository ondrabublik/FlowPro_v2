"""Skin friction on a flat plate (wall y = 0, leading edge x = 0) from a FlowPro computation.

Export the velocity in the vertices of every element (one sub-element per element):
    java -jar FlowPro.jar postprocessing velocity -ftxt -d1 -g GEOM -s SIM -oout_d1
then
    python plate_cf.py SIMDIR/out_d1 --re 1e6 --U 0.2357 [--x 0.2 0.5 1 1.5] [--png cf.png]
The wall shear is mu * du/dy of the DG polynomial of the elements adjacent to the wall (difference of
the values on the upper and lower edge of the element divided by its height), i.e. the gradient used
in the viscous flux. Compressible models (pRef = pIn0, rhoRef = rhoIn0, lRef = 1, Re of the
undisturbed flow): c_f = 2 / (Re U) * du/dy, U = undisturbed velocity in solver units.
Incompressible models: c_f = 2 / Re * du/dy (U = 1).
Compared with the turbulent correlations 0.0576 Re_x^-0.2 (Schlichting) and 0.455 / ln^2(0.06 Re_x) (White)
and the laminar Blasius solution 0.664 Re_x^-0.5.
"""
import argparse
import os

import numpy as np


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out_dir")
    ap.add_argument("--re", type=float, required=True, help="Reynolds number per unit length (lRef = 1)")
    ap.add_argument("--U", type=float, default=1.0, help="undisturbed velocity in solver units")
    ap.add_argument("--x", type=float, nargs="*", default=[0.25, 0.5, 1.0, 1.5])
    ap.add_argument("--xmax", type=float, default=float("inf"), help="end of the plate")
    ap.add_argument("--png")
    a = ap.parse_args()

    xy = np.loadtxt(os.path.join(a.out_dir, "vertices.txt"))[:, :2]
    vel = np.loadtxt(os.path.join(a.out_dir, "velocity.txt"))[:, 0]
    elems = np.loadtxt(os.path.join(a.out_dir, "elements.txt"), dtype=int, ndmin=2)
    tol = 1e-12 + 1e-9 * np.ptp(xy[:, 1])
    xs, slopes, heights = [], [], []
    for e in elems:
        p = xy[e]
        bottom = np.abs(p[:, 1]) < tol
        if bottom.sum() != 2 or p[:, 0].min() < -tol or p[:, 0].max() > a.xmax + tol:
            continue
        top = ~bottom
        h = p[top, 1].mean()
        for i in np.where(bottom)[0]:
            j = np.where(top)[0][np.argmin(np.abs(p[top, 0] - p[i, 0]))]
            xs.append(p[i, 0])
            slopes.append((vel[e[j]] - vel[e[i]]) / (p[j, 1] - p[i, 1]))
            heights.append(h)
    xs, slopes = np.array(xs), np.array(slopes)
    order = np.argsort(xs)
    xs, slopes = xs[order], slopes[order]
    # the two values in a wall vertex (left/right element) are averaged
    ux, inv = np.unique(np.round(xs, 12), return_inverse=True)
    sl = np.bincount(inv, slopes) / np.bincount(inv)
    cf = 2 / (a.re * a.U) * sl
    rex = a.re * ux
    with np.errstate(divide="ignore", invalid="ignore"):
        schl = 0.0576 * rex ** -0.2
        white = 0.455 / np.log(0.06 * rex) ** 2
        blas = 0.664 * rex ** -0.5
    print(f"wall elements: {len(heights) // 2}, first cell height {np.min(heights):.3g}")
    print(f"{'x':>6} {'Re_x':>10} {'c_f':>10} {'Schlichting':>12} {'White':>10} {'Blasius':>10}  {'err/White':>9}")
    for x in a.x:
        k = np.argmin(np.abs(ux - x))
        print(f"{ux[k]:6.3f} {rex[k]:10.3g} {cf[k]:10.5f} {schl[k]:12.5f} {white[k]:10.5f} {blas[k]:10.5f}  "
              f"{(cf[k] - white[k]) / white[k]:+9.1%}")
    # y+ of the first cell
    k = np.argmin(np.abs(ux - 1.0))
    utau = a.U * np.sqrt(abs(cf[k]) / 2)
    print(f"y+ of the first cell at x = {ux[k]:.2f}: {np.min(heights) * a.re * utau / a.U:.2f}")
    if a.png:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, ax = plt.subplots(figsize=(7, 4))
        m = ux > 0
        ax.plot(ux[m], cf[m], "b.-", ms=3, label="FlowPro")
        ax.plot(ux[m], white[m], "k-", lw=1, label="White 0.455/ln²(0.06 Re_x)")
        ax.plot(ux[m], schl[m], "k--", lw=1, label="0.0576 Re_x^-0.2")
        ax.plot(ux[m], blas[m], "k:", lw=1, label="Blasius (laminar)")
        ax.set_ylim(0, 3 * np.nanmedian(white[m]))
        ax.set_xlabel("x")
        ax.set_ylabel("c_f")
        ax.legend()
        ax.grid(alpha=0.3)
        fig.tight_layout()
        fig.savefig(a.png, dpi=110)
        print(f"figure {a.png}")


if __name__ == "__main__":
    main()
