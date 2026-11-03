"""Moves text results of a moving-mesh computation to the deformed mesh.

`postprocessing` without -iITER places the final solution of a moving-mesh computation on the
undeformed mesh (mesh/vertices.txt), although the solution belongs to the deformed mesh saved in
SIMDIR/PXY.txt. This script replaces the coordinates of OUT_DIR/vertices.txt by the deformed ones
(the original file is kept as vertices_undeformed.txt). It works for output whose points are mesh
vertices: -ftxt without -d, or with -d1. For -dN, N > 1, use animation snapshots (-iITER).

usage: python remap_deformed.py SIMDIR/OUT_DIR SIMDIR MESHDIR
"""
import os
import shutil
import sys

import numpy as np
from scipy.spatial import cKDTree


def main(out_dir, sim_dir, mesh_dir):
    v0 = np.loadtxt(os.path.join(mesh_dir, "vertices.txt"), ndmin=2)
    pxy = np.loadtxt(os.path.join(sim_dir, "PXY.txt"), ndmin=2)
    path = os.path.join(out_dir, "vertices.txt")
    backup = os.path.join(out_dir, "vertices_undeformed.txt")
    if not os.path.isfile(backup):
        shutil.copy(path, backup)
    xy = np.loadtxt(backup, ndmin=2)
    dist, idx = cKDTree(v0[:, :xy.shape[1]]).query(xy)
    scale = np.ptp(v0, axis=0).max()
    if dist.max() > 1e-9 * scale:
        raise SystemExit(f"output points are not mesh vertices (max distance {dist.max():.3g}); "
                         "use -d1 or no -d, or animation snapshots")
    np.savetxt(path, pxy[idx])
    print(f"{len(xy)} points moved to the deformed mesh, max displacement {np.abs(pxy[idx] - xy).max():.4g}")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
