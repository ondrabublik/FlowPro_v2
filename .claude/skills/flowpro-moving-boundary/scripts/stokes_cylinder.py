"""Verification of a moving-mesh computation: cylinder oscillating in a viscous fluid at rest.

Exact (Stokes 1851) force per unit length on a cylinder of radius R with the velocity
V(t) = Re(V^ e^{i w t}), small amplitude:
    F^ = -i w pi rho R^2 V^ [1 + 4 K1(kR) / (kR K0(kR))],   k = sqrt(i w / nu)
i.e. added mass (in phase with -acceleration) and viscous damping (in phase with -velocity).

The motion of the FlowPro case is y(t) = A s(t) sin(w t) (Rigid2D yMotion, s = smooth start).
The force record of bodiesDynamic.txt (columns t x y alpha Fx Fy M) belongs to time t + dt
(see SKILL.md); Fy is fitted by a sin(w t) + b cos(w t) + c over t >= T0.

usage: python stokes_cylinder.py SIMDIR/bodiesDynamic.txt --A 0.05 --omega 0.5 --nu 1e-3 [--R 0.5 --rho 1 --from T0 --png f.png]
"""
import argparse
import math

import numpy as np
from scipy.special import kv


def exact(A, w, nu, R, rho):
    k = np.sqrt(1j * w / nu)
    z = k * R
    factor = 1 + 4 * kv(1, z) / (z * kv(0, z))
    V = A * w  # y = A sin(wt) -> V = A w cos(wt) = Re(A w e^{iwt})
    F = -1j * w * math.pi * rho * R**2 * V * factor  # F(t) = Re(F e^{iwt}) = Re(F) cos - Im(F) sin
    return F, factor


def domain_factor(w, R, R_out, c):
    """inviscid added-mass coefficient of the cylinder in a concentric domain R_out with a fixed
    pressure on the outer boundary (phi = 0), compressible fluid with the speed of sound c
    (acoustic standing waves; c = inf: incompressible value (R_out^2 - R^2) / (R_out^2 + R^2))"""
    from scipy.special import jv, yv, jvp, yvp
    k = w / c if math.isfinite(c) else 1e-8
    M = np.array([[jv(1, k * R_out), yv(1, k * R_out)], [k * jvp(1, k * R), k * yvp(1, k * R)]])
    B, C = np.linalg.solve(M, [0.0, 1.0])
    return -(B * jv(1, k * R) + C * yv(1, k * R)) / R


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("file")
    ap.add_argument("--A", type=float, required=True)
    ap.add_argument("--omega", type=float, required=True)
    ap.add_argument("--nu", type=float, required=True)
    ap.add_argument("--R", type=float, default=0.5)
    ap.add_argument("--rho", type=float, default=1.0)
    ap.add_argument("--Rout", type=float, default=float("inf"),
                    help="radius of the outer boundary with a fixed pressure (outlet -3)")
    ap.add_argument("--c", type=float, default=float("inf"),
                    help="speed of sound in solver units (compressible models: sqrt(kappa p / rho))")
    ap.add_argument("--from", dest="t0", type=float)
    ap.add_argument("--png")
    a = ap.parse_args()

    d = np.loadtxt(a.file, ndmin=2)
    dt = np.median(np.diff(d[:, 0]))
    t = d[:, 0] + dt  # the record belongs to the end of the time step
    y, fy = d[:, 2], d[:, 5]
    # older FlowPro versions: the last step cut by tEnd to dt ~ 1e-12 gives a nonsense force
    if len(fy) > 10 and abs(fy[-1]) > 20 * np.abs(fy[:-1]).max():
        print(f"skipped the last record (t = {t[-1]:.6g}): outlier, probably a tiny step cut by tEnd")
        t, y, fy = t[:-1], y[:-1], fy[:-1]
    t0 = a.t0 if a.t0 is not None else 2 * 2 * math.pi / a.omega
    sel = t >= t0
    if sel.sum() < 10:
        raise SystemExit(f"only {sel.sum()} records with t >= {t0:.4g} (last t = {t[-1]:.4g}), change --from")
    w = a.omega
    M = np.column_stack([np.sin(w * t[sel]), np.cos(w * t[sel]), np.ones(sel.sum())])
    (fs, fc, f0), *_ = np.linalg.lstsq(M, fy[sel], rcond=None)
    (ys, yc, _), *_ = np.linalg.lstsq(M, y[sel], rcond=None)
    F, factor = exact(a.A, w, a.nu, a.R, a.rho)
    if math.isfinite(a.Rout):
        # the inviscid added mass of the unbounded incompressible fluid (1) is replaced by that of the
        # finite (and compressible) domain, the viscous part of the Stokes solution is kept
        fd = domain_factor(w, a.R, a.Rout, a.c)
        print(f"domain correction: inviscid added-mass coefficient {fd:.4f} instead of 1 "
              f"(R_out = {a.Rout}, c = {a.c}, k R_out = {w / a.c * a.Rout:.3f})")
        factor = factor - 1 + fd
        F = -1j * w * math.pi * a.rho * a.R**2 * a.A * w * factor
    es, ec = -F.imag, F.real
    beta = (2 * a.R) ** 2 * w / (2 * math.pi * a.nu)
    print(f"Stokes number beta = D^2/(nu T) = {beta:.1f}, k R = {abs(np.sqrt(1j * w / a.nu)) * a.R:.2f}")
    print(f"motion fit: y = {ys:.5f} sin + {yc:.2e} cos   (prescribed amplitude {a.A})")
    print(f"exact:   Fy = {es:+.6f} sin(wt) {ec:+.6f} cos(wt)   (added mass coeff. {factor.real:.4f}, "
          f"damping coeff. {-factor.imag:.4f})")
    print(f"FlowPro: Fy = {fs:+.6f} sin(wt) {fc:+.6f} cos(wt)   (mean {f0:+.2e})")
    print(f"relative error: inertial (sin) part {abs(fs - es) / abs(es):.2%}, damping (cos) part {abs(fc - ec) / abs(ec):.2%}, "
          f"amplitude {abs(math.hypot(fs, fc) - abs(F)) / abs(F):.2%}, phase {math.degrees(math.atan2(fc, fs) - math.atan2(ec, es)):+.2f} deg")
    if a.png:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        tt = np.linspace(t[0], t[-1], 2000)
        fig, ax = plt.subplots(figsize=(9, 3.5))
        ax.plot(t, fy, "b.", ms=3, label="FlowPro Fy")
        ax.plot(tt, es * np.sin(w * tt) + ec * np.cos(w * tt), "k-", lw=1, label="Stokes (periodic state)")
        ax.axvline(t0, color="k", ls="--", lw=0.5)
        ax.set_xlabel("t")
        ax.legend()
        ax.grid(alpha=0.3)
        fig.tight_layout()
        fig.savefig(a.png, dpi=110)
        print(f"figure {a.png}")


if __name__ == "__main__":
    main()
