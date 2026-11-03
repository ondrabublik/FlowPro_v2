"""Summary (and optional plot) of residuum.txt of a FlowPro computation.

Columns: relative residuum (max over the equations), time, CPU time [ms], residua of the equations.
usage: python residuum.py SIMDIR [--png residuum.png]
"""
import argparse
import os

import numpy as np


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("sim_dir")
    ap.add_argument("--png")
    a = ap.parse_args()
    d = np.loadtxt(os.path.join(a.sim_dir, "residuum.txt"), ndmin=2)
    res, t, cpu = d[:, 0], d[:, 1], d[:, 2]
    n = len(res)
    print(f"steps {n}, t = {t[-1]:.6g}, CPU {cpu[-1] / 1000:.1f} s ({cpu[-1] / 1000 / max(n, 1):.3f} s/step)")
    print(f"residuum: last {res[-1]:.3e}, min {res.min():.3e}, last 10 steps mean {res[-10:].mean():.3e}")
    if d.shape[1] > 3:
        print("residua of the equations (last step): " + ", ".join(f"{v:.2e}" for v in d[-1, 3:]))
    if np.any(~np.isfinite(d)):
        print("WARNING: NaN/Inf in residuum.txt")
    if a.png:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        fig, ax = plt.subplots(figsize=(8, 4))
        ax.semilogy(res, label="max")
        for k in range(3, d.shape[1]):
            ax.semilogy(d[:, k], lw=0.7, label=f"eq {k - 3}")
        ax.set_xlabel("step")
        ax.legend()
        ax.grid(alpha=0.3)
        fig.tight_layout()
        fig.savefig(a.png, dpi=110)
        print(f"figure {a.png}")


if __name__ == "__main__":
    main()
