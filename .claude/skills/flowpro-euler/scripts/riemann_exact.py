"""Exact solution of the Riemann problem of the 1D Euler equations (ideal gas, Toro, chapter 4).

usage as a script (compares with a FlowPro line sample, see flowpro-postprocessing/scripts/sample.py):
    python riemann_exact.py --left 1 0 1 --right 0.125 0 0.1 --x0 0.5 --t 0.2 [--kappa 1.4]
        [--compare line.csv]      # columns s, x, y, density, ... -> L1 errors of density
states are (rho, u, p).
"""
import argparse
import math

import numpy as np


def _f(p, rho, pk, ak, g):
    if p > pk:  # shock
        A, B = 2 / ((g + 1) * rho), (g - 1) / (g + 1) * pk
        return (p - pk) * math.sqrt(A / (p + B))
    return 2 * ak / (g - 1) * ((p / pk) ** ((g - 1) / (2 * g)) - 1)  # rarefaction


def star_state(L, R, g):
    rl, ul, pl = L
    rr, ur, pr = R
    al, ar = math.sqrt(g * pl / rl), math.sqrt(g * pr / rr)
    lo, hi = 1e-12, 10 * max(pl, pr) + 10
    fun = lambda p: _f(p, rl, pl, al, g) + _f(p, rr, pr, ar, g) + ur - ul
    for _ in range(200):
        mid = 0.5 * (lo + hi)
        lo, hi = (mid, hi) if fun(mid) < 0 else (lo, mid)
    ps = 0.5 * (lo + hi)
    us = 0.5 * (ul + ur) + 0.5 * (_f(ps, rr, pr, ar, g) - _f(ps, rl, pl, al, g))
    return ps, us


def sample(L, R, x, x0, t, g=1.4):
    """returns rho, u, p at positions x (array)"""
    ps, us = star_state(L, R, g)
    out = np.zeros((3, len(x)))
    for i, xi in enumerate(np.asarray(x, float)):
        s = (xi - x0) / t
        if s <= us:  # left of the contact
            r, u, p = L
            a = math.sqrt(g * p / r)
            if ps > p:  # left shock
                rs = r * ((ps / p + (g - 1) / (g + 1)) / ((g - 1) / (g + 1) * ps / p + 1))
                S = u - a * math.sqrt((g + 1) / (2 * g) * ps / p + (g - 1) / (2 * g))
                out[:, i] = (r, u, p) if s < S else (rs, us, ps)
            else:  # left rarefaction
                rs = r * (ps / p) ** (1 / g)
                ash = a * (ps / p) ** ((g - 1) / (2 * g))
                if s < u - a:
                    out[:, i] = (r, u, p)
                elif s > us - ash:
                    out[:, i] = (rs, us, ps)
                else:
                    uf = 2 / (g + 1) * (a + (g - 1) / 2 * u + s)
                    rf = r * (2 / (g + 1) + (g - 1) / ((g + 1) * a) * (u - s)) ** (2 / (g - 1))
                    out[:, i] = (rf, uf, p * (rf / r) ** g)
        else:  # right of the contact
            r, u, p = R
            a = math.sqrt(g * p / r)
            if ps > p:  # right shock
                rs = r * ((ps / p + (g - 1) / (g + 1)) / ((g - 1) / (g + 1) * ps / p + 1))
                S = u + a * math.sqrt((g + 1) / (2 * g) * ps / p + (g - 1) / (2 * g))
                out[:, i] = (r, u, p) if s > S else (rs, us, ps)
            else:  # right rarefaction
                rs = r * (ps / p) ** (1 / g)
                ash = a * (ps / p) ** ((g - 1) / (2 * g))
                if s > u + a:
                    out[:, i] = (r, u, p)
                elif s < us + ash:
                    out[:, i] = (rs, us, ps)
                else:
                    uf = 2 / (g + 1) * (-a + (g - 1) / 2 * u + s)
                    rf = r * (2 / (g + 1) - (g - 1) / ((g + 1) * a) * (u - s)) ** (2 / (g - 1))
                    out[:, i] = (rf, uf, p * (rf / r) ** g)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--left", nargs=3, type=float, required=True, metavar=("RHO", "U", "P"))
    ap.add_argument("--right", nargs=3, type=float, required=True, metavar=("RHO", "U", "P"))
    ap.add_argument("--x0", type=float, default=0.5)
    ap.add_argument("--t", type=float, required=True)
    ap.add_argument("--kappa", type=float, default=1.4)
    ap.add_argument("--compare", help="CSV from sample.py with columns s,x,y,<quantities>")
    a = ap.parse_args()
    ps, us = star_state(a.left, a.right, a.kappa)
    print(f"star state: p* = {ps:.6g}, u* = {us:.6g}")
    if a.compare:
        data = np.genfromtxt(a.compare, delimiter=",", names=True)
        x = data["x"]
        ex = sample(a.left, a.right, x, a.x0, a.t, a.kappa)
        for k, name in enumerate(("density", "xvelocity", "pressure")):
            if name in data.dtype.names:
                err = np.nanmean(np.abs(data[name] - ex[k]))
                print(f"{name}: L1 error (mean |num - exact|) = {err:.4g}")
        rho = data["density"] if "density" in data.dtype.names else None
        if rho is not None:
            print(f"density: min {np.nanmin(rho):.4g}, max {np.nanmax(rho):.4g} (exact {ex[0].min():.4g}..{ex[0].max():.4g})")


if __name__ == "__main__":
    main()
