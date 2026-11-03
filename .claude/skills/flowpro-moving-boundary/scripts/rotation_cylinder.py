"""Verification of the free motion (Rigid2D dynamics): cylinder on a torsional spring in a viscous fluid at rest.

The body equation I alpha'' + B alpha' + K alpha = M (M = fluid torque, column 7 of bodiesDynamic.txt)
is checked with the recorded alpha and M, and the free decaying oscillation after tKick is compared with
the exact eigenvalue s of a cylinder rotating in an unbounded viscous fluid:
    I s^2 + B s + K + Z(s) s = 0,   Z(s) = 2 pi mu R^2 (2 + z K0(z) / K1(z)),   z = R sqrt(s / nu)
(viscous torque T = -Z(s) alpha' of the mode alpha ~ e^{s t}).

usage: python rotation_cylinder.py SIMDIR/bodiesDynamic.txt --I 0.2 --K 0.05 --nu 0.01 --tKick 5 [--B 0 --R 0.5 --rho 1 --png f.png]
"""
import argparse
import math

import numpy as np
from scipy.optimize import curve_fit, fsolve
from scipy.special import kv


def eigenvalue(I, B, K, mu, nu, R):
    def f(v):
        s = complex(v[0], v[1])
        z = R * np.sqrt(s / nu)
        Z = 2 * math.pi * mu * R**2 * (2 + z * kv(0, z) / kv(1, z))
        r = I * s * s + B * s + K + Z * s
        return [r.real, r.imag]
    w0 = math.sqrt(K / I)
    re, im = fsolve(f, [-0.01 * w0, w0])
    return complex(re, im)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("--I", type=float, required=True, help="moment of inertia (solver units)")
    ap.add_argument("--K", type=float, required=True, help="torsional stiffness")
    ap.add_argument("--B", type=float, default=0.0, help="structural damping")
    ap.add_argument("--nu", type=float, required=True)
    ap.add_argument("--R", type=float, default=0.5)
    ap.add_argument("--rho", type=float, default=1.0)
    ap.add_argument("--tKick", type=float, required=True)
    ap.add_argument("--from", dest="t0", type=float, help="start of the fit (default tKick + 1 period)")
    ap.add_argument("--png")
    a = ap.parse_args()

    d = np.loadtxt(a.file, ndmin=2)
    _, last = np.unique(d[::-1, 0], return_index=True)  # repeated times after a restart: keep the last
    d = d[np.sort(len(d) - 1 - last)]
    dt = np.median(np.diff(d[:, 0]))
    t = d[:, 0] + dt  # the position (and approximately the force) belongs to the end of the step
    alpha, m = d[:, 3], d[:, 6]

    # body equation with the recorded torque (central differences, free motion only)
    sel = (t[1:-1] > a.tKick + 3 * dt)
    acc = (alpha[2:] - 2 * alpha[1:-1] + alpha[:-2]) / dt**2
    vel = (alpha[2:] - alpha[:-2]) / (2 * dt)
    lhs = a.I * acc + a.B * vel + a.K * alpha[1:-1]
    res = lhs[sel] - m[1:-1][sel]
    print(f"body equation I a'' + B a' + K a = M: max |residual| / max |K alpha| = "
          f"{np.abs(res).max() / np.abs(a.K * alpha[1:-1][sel]).max():.2e}, "
          f"max |M| / max |K alpha| = {np.abs(m[1:-1][sel]).max() / np.abs(a.K * alpha[1:-1][sel]).max():.2e}")

    s = eigenvalue(a.I, a.B, a.K, a.rho * a.nu, a.nu, a.R)
    w0 = math.sqrt(a.K / a.I)
    t0 = a.t0 if a.t0 is not None else a.tKick + 2 * math.pi / w0
    fit = t >= t0
    model = lambda tt, A, sig, w, ph: A * np.exp(sig * (tt - t0)) * np.cos(w * (tt - t0) + ph)
    p0 = [np.abs(alpha[fit]).max(), s.real, s.imag, 0.0]
    (A, sig, w, ph), _ = curve_fit(model, t[fit], alpha[fit], p0=p0)
    print(f"undamped omega0 = {w0:.5f}, exact eigenvalue s = {s.real:+.5f} {s.imag:+.5f} i")
    print(f"FlowPro fit (t >= {t0:.4g}): decay rate {sig:+.5f}, frequency {w:.5f}")
    print(f"relative error: decay rate {abs(sig - s.real) / abs(s.real):.2%}, frequency {abs(w - s.imag) / s.imag:.2%}")
    if a.png:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        tt = np.linspace(t0, t[-1], 1000)
        fig, ax = plt.subplots(figsize=(9, 3.5))
        ax.plot(t, alpha, "b.", ms=3, label="FlowPro alpha")
        ax.plot(tt, A * np.exp(s.real * (tt - t0)) * np.cos(s.imag * (tt - t0) + ph), "k-", lw=1,
                label="exact eigenmode (amplitude, phase fitted)")
        ax.set_xlabel("t")
        ax.legend()
        ax.grid(alpha=0.3)
        fig.tight_layout()
        fig.savefig(a.png, dpi=110)
        print(f"figure {a.png}")


if __name__ == "__main__":
    main()
