---
name: flowpro-incompressible
description: Unsteady flow of an incompressible viscous fluid (incompressible Navier–Stokes equations) in FlowPro – model IncompressibleNavierStokes, DGpureIncompressible + BDFIncompressible (BDF2, fixed step), solver settings (blockilu0, iterativeSolverTol), inlet profiles, Reynolds number, forces and the Strouhal number, verification on the flow past a cylinder at Re = 100. Use for laminar incompressible problems – flow past bodies, vortex shedding, channels, cavities.
---

# Unsteady incompressible Navier–Stokes equations (FlowPro)

Prerequisites: mesh (skill `flowpro-mesh`), running and evaluation (skill `flowpro-postprocessing`).
Turbulent incompressible flow (Spalart–Allmaras): skill `flowpro-turbulence`.

## Model and units

* `model = IncompressibleNavierStokes`, unknowns W = (p, u, v) – the pressure is the first component (`W0`).
* Dimensionless quantities: velocity / |VIn|, length / `lRef`, time / (lRef/|VIn|), pressure / (ρ|VIn|²).
  **Re = |VIn| · lRef / viscosity** (or directly `reynolds`, which takes precedence). Printed at the start (`Reynolds number …`).
  `VIn` must not be zero (reference velocity) – solve problems "at rest" (a body oscillating in a fluid at rest) with the compressible
  model at a low Mach number (skill `flowpro-moving-boundary`).
* `pOut` is given directly in the dimensionless units ρ|VIn|² (usually 0).
* Boundary conditions: −1 no-slip wall (on a moving mesh the wall velocity), −2 inlet (velocity), −3 outlet
  (pressure `pOut`, extrapolated velocity), −4 slip wall / symmetry.
* `inletType`: `velocity` (default, constant `VIn`), `parabola` (parabolic profile with the mean |VIn|, **hard-coded
  for a channel y ∈ [0, 1]**), `parabola2` (constant `VIn`, x-component ramped by (1 − cos(πt/2))/2 during t·tRef < 2).

## parameters.txt (verified setup)

```
model = IncompressibleNavierStokes
results = pressure, velocity, vorticity
dimension = 2
isFlowViscous = true
viscosity = 0.01      % Re = 1 * 1 / 0.01 = 100
density = 1
VIn = 1, 0
pOut = 0
lRef = 1              % reference length = diameter / chord / channel height

continueComputation = false
steps = 1200          % length of the run = steps * dt
tEnd = 120            % = steps * dt
saveRate = 200
%animation = true     % snapshots for visualisation (large files)

spatialMethod = DGpureIncompressible
timeMethod = BDFIncompressible
timeOrder = 2
dt = 0.1              % 50–100 steps per period (St = 0.165 -> T = 6)
linearSolver = gmres
preconditioner = blockilu0
iterativeSolverTol = 0.1
order = 2
threads = 12
newtonIters = 3
newtonIterTol = 1e-3
penalty = 1
dampTol = 0
dampConst = 0
```

Key solver settings (verified, see below):
* **`preconditioner = blockilu0`** – `ilu0` ends with "zero pivot" (the continuity equation has no time derivative,
  elements with two boundary faces have a zero diagonal pressure term). `blockjacobi` does not converge.
* **`iterativeSolverTol = 0.1`** – GMRES(30) with 5 restarts does not reach the default tolerance 1e−2 for this system
  ("GMRES does not converge" in the 2nd step, the step is halved until the computation aborts – a smaller `dt` does not help, the pressure part
  of the system does not depend on `dt`). The inexact Newton method then needs 2–3 iterations (`newtonIters = 3`, `newtonIterTol = 1e-3`).
* `timeOrder = 2` with a fixed `dt`; the first step is BDF1 automatically. An impulsive start from `VIn` (default initial
  condition) is fine.
* `order = 2` (p = 1) for flows with shedding; `order = 3` is more accurate but several times more expensive per step.

## Forces and vortex shedding

The forces are written only by the body dynamics. For a fixed body add (the mesh must have ALE tags:
outer boundary 1, body 2):
```
movingMesh = true
dynamicsModel = Rigid2D
meshDeformationType = Rigid2D
rotationCenters = 0, 0
alphaMotion = t < 2 ? 0.1*(1 - Math.cos(Math.PI*t)) : 0   % a short rotation there and back breaks the symmetry
```
Without breaking the symmetry the shedding develops only from numerical noise (hundreds of time units).
Rotating the cylinder there and back (the circular wall rotates, the mesh is deformed only temporarily) speeds up the start – saturated
shedding from t ≈ 40; for a non-circular body a short computation with an oblique `VIn = 1, 0.05` and a continuation
(`continueComputation = true`) with `VIn = 1, 0`, or `initScript.js` with an asymmetric perturbation.
Evaluation: `python <flowpro-postprocessing>/scripts/forces.py simulations/GEOM/SIM/bodiesDynamic.txt --from 60`
(q = 0.5, U = 1 by default → c_D, c_L, St).

## Verification – cylinder at Re = 100

Mesh `flowpro-mesh/scripts/example_cylinder.py` (cylinder D = 1, channel [−8, 22] × [−8, 8], 11,382 triangles,
96 faces on the cylinder), parameters above (`examples/cylinder_Re100.txt`).

```
java -jar FlowPro.jar run -g skills/cylinder -s incRe100 > simulations/skills/cylinder/incRe100.log 2>&1
python <flowpro-postprocessing>/scripts/forces.py simulations/skills/cylinder/incRe100/bodiesDynamic.txt --from 70
```

Result (verified): 1200 steps to t = 120, 48 min (12 threads, ~2.4 s/step), no GMRES failure;
saturated periodic shedding from t ≈ 40.

| quantity | FlowPro (t ∈ [70, 120], 8 periods) | literature Re = 100 (unbounded flow) |
|---|---|---|
| Strouhal number | 0.170 | 0.164–0.166 |
| mean c_D | 1.36 | 1.33–1.35 |
| amplitude of c_L | 0.326 | 0.32–0.33 |

The slightly higher St and c_D correspond to the channel blockage D/H = 6 % (slip side walls). Unlike the compressible
model (`flowpro-navier-stokes`) the velocity upstream of the body is exactly `VIn` (velocity inlet).

**`tEnd` and a very short step**: a step cut by `tEnd` shows up in `BDFIncompressible` (BDF2 with a step ratio ≪ 1)
as a nonsense pressure. FlowPro therefore does not compute a remainder below 1e−6·dt (round-off of the summed steps);
set `tEnd` to a multiple of `dt`, not slightly more.
