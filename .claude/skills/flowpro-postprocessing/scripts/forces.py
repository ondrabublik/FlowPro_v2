"""Analysis of the forces on bodies written by the Rigid2D dynamics into bodiesDynamic.txt.

Columns of bodiesDynamic.txt: t, then for every body x, y, alpha, Fx, Fy, M (moment about the
rotation centre). t is the solver (dimensionless) time, the forces are per unit depth (times zLength)
in solver units, i.e. related to pRef * lRef.

usage:
    python forces.py SIMDIR/bodiesDynamic.txt [--body 1] [--from T0] [--q Q --U U --L L] [--png forces.png]
    python forces.py ... --stagnation KAPPA PIN0 RHOIN0 POUT      # compressible models: q, U from the stagnation state
q = reference dynamic pressure in solver units (incompressible: 0.5, since pRef = rho*|VIn|^2),
U = reference velocity in solver units (incompressible: 1), L = reference length (diameter, chord).
Prints mean and amplitude of the force coefficients and the dominant frequency / Strouhal number.
"""
import argparse
import math

import numpy as np


def stagnation_reference(kappa, p0, rho0, p_out):
    """dynamic pressure and velocity of the undisturbed flow in solver units (pRef = p0, rhoRef = rho0)"""
    mach = math.sqrt(2 / (kappa - 1) * ((p0 / p_out) ** ((kappa - 1) / kappa) - 1))
    p = p_out / p0
    rho = (p_out / p0) ** (1 / kappa)
    u = mach * math.sqrt(kappa * p / rho)
    return 0.5 * rho * u * u, u, mach


def dominant_frequency(t, y):
    """frequency from the FFT of the (resampled, detrended) signal, refined by a parabola"""
    tt = np.linspace(t[0], t[-1], len(t))
    yy = np.interp(tt, t, y) - np.mean(y)
    yy *= np.hanning(len(yy))
    spec = np.abs(np.fft.rfft(yy, 8 * len(yy)))
    freq = np.fft.rfftfreq(8 * len(yy), tt[1] - tt[0])
    k = np.argmax(spec[1:]) + 1
    if 1 <= k < len(spec) - 1:
        a, b, c = spec[k - 1], spec[k], spec[k + 1]
        shift = 0.5 * (a - c) / (a - 2 * b + c) if (a - 2 * b + c) != 0 else 0
        return freq[k] + shift * (freq[1] - freq[0])
    return freq[k]


def zero_crossing_frequency(t, y):
    y = y - np.mean(y)
    idx = np.where((y[:-1] < 0) & (y[1:] >= 0))[0]
    if len(idx) < 2:
        return float("nan")
    tc = t[idx] - y[idx] * (t[idx + 1] - t[idx]) / (y[idx + 1] - y[idx])
    return (len(tc) - 1) / (tc[-1] - tc[0])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("--body", type=int, default=1)
    ap.add_argument("--from", dest="t0", type=float, default=None, help="start of the analysed interval")
    ap.add_argument("--q", type=float, default=None)
    ap.add_argument("--U", type=float, default=None)
    ap.add_argument("--L", type=float, default=1.0)
    ap.add_argument("--stagnation", nargs=4, type=float, metavar=("KAPPA", "PIN0", "RHOIN0", "POUT"))
    ap.add_argument("--png")
    a = ap.parse_args()

    d = np.loadtxt(a.file, ndmin=2)
    t = d[:, 0]
    c = 1 + 6 * (a.body - 1)
    x, y, alpha, fx, fy, m = (d[:, c + k] for k in range(6))
    # repeated times (Newton iterations / restarts) - keep the last record of each time
    _, last = np.unique(t[::-1], return_index=True)
    keep = np.sort(len(t) - 1 - last)
    t, x, y, alpha, fx, fy, m = (v[keep] for v in (t, x, y, alpha, fx, fy, m))
    # older FlowPro versions computed a last step cut by tEnd to dt ~ 1e-12 (t summed slightly below
    # tEnd = N dt) with nonsense forces; its record has a normal time, so it is recognised as an outlier
    if len(t) > 10:
        rest = np.hypot(fx[:-1], fy[:-1])
        spread = np.ptp(rest[len(rest) // 2:]) + 1e-12 * np.abs(rest).max()
        if abs(np.hypot(fx[-1], fy[-1]) - np.median(rest[len(rest) // 2:])) > 20 * spread:
            print(f"skipped the last record (t = {t[-1]:.6g}): outlier, probably a tiny step cut by tEnd")
            t, x, y, alpha, fx, fy, m = (v[:-1] for v in (t, x, y, alpha, fx, fy, m))

    q, U = a.q, a.U
    if a.stagnation:
        q, U, mach = stagnation_reference(*a.stagnation)
        print(f"undisturbed flow: M = {mach:.4f}, q = {q:.5g}, U = {U:.5g} (solver units)")
    if q is None:
        q = 0.5
    if U is None:
        U = 1.0
    t0 = a.t0 if a.t0 is not None else t[0] + 0.5 * (t[-1] - t[0])
    sel = t >= t0
    print(f"records {len(t)}, t = {t[0]:.4g} .. {t[-1]:.4g}, analysed t >= {t0:.4g} ({sel.sum()} records)")
    if sel.sum() < 2:
        raise SystemExit("not enough records in the analysed interval, change --from")
    norm = q * a.L
    for name, f in (("Cx (drag)", fx / norm), ("Cy (lift)", fy / norm), ("Cm", m / (norm * a.L))):
        fs = f[sel]
        print(f"{name:10s} mean {fs.mean():+.5f}, amplitude {(fs.max() - fs.min()) / 2:.5f}, "
              f"min {fs.min():+.5f}, max {fs.max():+.5f}")
    if sel.sum() > 20:
        fl = dominant_frequency(t[sel], fy[sel])
        fz = zero_crossing_frequency(t[sel], fy[sel])
        print(f"lift frequency: FFT {fl:.5g}, zero crossings {fz:.5g}  ->  St = f L / U = {fz * a.L / U:.4f}")
    if a.png:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, ax = plt.subplots(2, 1, figsize=(9, 5), sharex=True)
        ax[0].plot(t, fx / norm, label="Cx")
        ax[0].plot(t, fy / norm, label="Cy")
        ax[0].axvline(t0, color="k", lw=0.5, ls="--")
        ax[0].legend()
        ax[0].grid(alpha=0.3)
        ax[1].plot(t, x, label="x")
        ax[1].plot(t, y, label="y")
        ax[1].plot(t, alpha, label="alpha [rad]")
        ax[1].legend()
        ax[1].grid(alpha=0.3)
        ax[1].set_xlabel("t (solver units)")
        fig.tight_layout()
        fig.savefig(a.png, dpi=110)
        print(f"figure {a.png}")


if __name__ == "__main__":
    main()
