---
name: flowpro-navier-stokes
description: Unsteady flow of a compressible viscous fluid (Navier–Stokes equations) in FlowPro – model NavierStokes with isFlowViscous = true, Reynolds and Mach numbers from the stagnation quantities, implicit BDF2 with a fixed step, start from a steady state, forces and the Strouhal number, verification on the flow past a cylinder at Re = 100, M = 0.2. Use for laminar compressible unsteady problems – flow past airfoils and cylinders, vortex shedding, unsteady channels.
---

# Unsteady compressible Navier–Stokes equations (FlowPro)

Prerequisites: mesh (skill `flowpro-mesh`), running and evaluation (skill `flowpro-postprocessing`).
Inviscid flow: `flowpro-euler`; turbulence (RANS): `flowpro-turbulence`; moving bodies: `flowpro-moving-boundary`.
Template: `Tools/FlowProControl/src/main/resources/flowpro/control/templates/navierstokes.txt`.

## Model and units

* `model = NavierStokes`, `isFlowViscous = true`; W = (ρ, ρu, ρv, E); constant viscosity, `prandtl` (0.72).
* Reference state = stagnation state at the inlet: `pRef = pIn0`, `rhoRef = rhoIn0`, `vRef = √(pIn0/rhoIn0)`,
  `tRef = lRef/vRef`. With `pIn0 = rhoIn0 = lRef = 1` times are in units of lRef/√(p0/ρ0).
* The **Mach number** of the free stream is given by the pressure ratio: `pIn0/pOut = (1 + (κ−1)/2·M²)^(κ/(κ−1))`
  (M = 0.2 → `pOut = 0.97250`; M = 0.39 → 0.9). Velocity in solver units U∞ = M·√(κ p∞/ρ∞),
  p∞ = pOut/pIn0, ρ∞ = (pOut/pIn0)^(1/κ) (M = 0.2 → U∞ = 0.2357).
* **Reynolds number**: `reynolds` refers to the free stream (ρ∞, U∞) and the length `lRef`
  (the model converts it to the reference state). Alternative: `viscosity` + `conductivity` directly in solver
  units (μ/(ρRef·vRef·lRef), k = cp·μ/Pr, cp = κ·cv) – required for a fluid at rest (U∞ = 0).
* Boundary conditions: −1 no-slip adiabatic wall (on a moving mesh the wall velocity + work of the shear stress),
  −2 inlet (stagnation quantities, direction `attackAngle` in radians), −3 outlet (`pOut`), −4 slip wall/symmetry.
* Low Mach numbers: the compressible model is usable from M ≈ 0.1–0.2; for lower velocities use
  `flowpro-incompressible`.

## parameters.txt (verified unsteady setup)

```
model = NavierStokes
results = mach, pressure, velocity, vorticity, temperature
dimension = 2
isFlowViscous = true
reynolds = 100
prandtl = 0.72
lRef = 1
cv = 1
kappa = 1.4
isInletSupersonic = false
attackAngle = 0
pIn0 = 1
rhoIn0 = 1
pOut = 0.97250       % M = 0.2

continueComputation = false
steps = 1000
tEnd = 400           % = steps * dt
saveRate = 100

spatialMethod = DGpure
timeMethod = BDF
timeOrder = 2
dt = 0.4             % ~65 steps per shedding period (T = D/(St U) = 26)
linearSolver = gmres
preconditioner = ilu0
order = 2
threads = 12
newtonIters = 3
newtonIterTol = 1e-3
penalty = 1
dampTol = 0          % no shocks: artificial viscosity off
dampConst = 0
```

* Choose the fixed `dt` by the physics (50–100 steps per period); the acoustic CFL is then 40–50, which implicit BDF2
  handles (`ilu0` is enough, unlike the incompressible model).
* `newtonIters = 2–3`: with one iteration the nonlinear error is proportional to the step and the forces are from time t (not t + dt).
* Start: an impulsive start from the uniform stream (default initial condition) works for fixed bodies.
  For expensive problems a steady computation first is cheaper (BDF1, adaptive `CFL = 0`, `maxCFL = 200`,
  `residuum = 1e-6`), then a continuation (`continueComputation = true`, `timeOrder = 2`, `dt`, absolute `tEnd`).
  Always start a moving body smoothly (skill `flowpro-moving-boundary`).
* Order: `order = 2` (p = 1) for first computations, `order = 3` (p = 2) for accurate results on a coarser mesh
  (NACA0012 at Re = 1000: p = 2 on 17.5 thousand triangles).
* Vortex shedding behind a symmetric body: break the symmetry (rotation of the cylinder, see `flowpro-incompressible`,
  or a short nonzero `attackAngle` in the first phase and a continuation with 0).

## Forces

As for the incompressible model via `Rigid2D` (`movingMesh = true`, `alphaMotion = …`); evaluation
`forces.py … --stagnation 1.4 1 1 0.97250` (q∞ and U∞ are computed from the stagnation state).

## Verification – cylinder at Re = 100, M = 0.2

Mesh `flowpro-mesh/scripts/example_cylinder.py` (11,382 triangles), parameters above, rotation of the cylinder
`alphaMotion = t < 20 ? 0.5*(1 - Math.cos(Math.PI*t/20)) : 1` (slow, U∞ = 0.24). Parameters:
`examples/cylinder_Re100_M02.txt`.

```
java -jar FlowPro.jar run -g skills/cylinder -s nsRe100 > simulations/skills/cylinder/nsRe100.log 2>&1
python <flowpro-postprocessing>/scripts/forces.py simulations/skills/cylinder/nsRe100/bodiesDynamic.txt --stagnation 1.4 1 1 0.9725 --from 250
```

Result (verified): 1000 steps to t = 400 (~15 periods), 36 min (12 threads), no solver failure;
periodic vortex shedding from t ≈ 100.

| quantity | FlowPro, U∞ from `pOut` (0.2357) | FlowPro, measured U∞ (0.2225) | literature Re = 100 (incompressible) |
|---|---|---|---|
| Strouhal number | 0.159 | 0.168 | 0.164–0.166 |
| mean c_D | 1.27 | 1.42 | 1.33–1.35 |
| amplitude of c_L | 0.33 | 0.36 | 0.32–0.33 |

**Beware of the reference velocity in a channel**: with a stagnation inlet (−2) and a pressure outlet (−3) a channel with a body
has a pressure loss, the static pressure upstream of the body is higher than `pOut` and the actual velocity lower (here by 5.6 %, M = 0.189,
effectively Re ≈ 94). Measure it (`postprocessing velocity pressure density -ftxt -d1`, `sample.py --points -7 0`)
and pass `forces.py --q <½ρU²> --U <U>` (here ρ = 0.982, `--q 0.0243 --U 0.2225`). With the measured velocity St agrees; the higher c_D (+5 % against the incompressible
computation on the same mesh, see `flowpro-incompressible`) corresponds to the lower Re, compressibility at M = 0.19
and the 6 % channel blockage (slip walls). For accurate values without corrections use a larger domain or a far field
(circular outer boundary: inlet half −2, outlet half −3).
The cylinder stayed rotated by 1 rad, so the mesh is permanently deformed (`postprocessing` plots the final solution
on the deformed mesh `PXY.txt`). A "there and back" rotation is better
(`t < 20 ? 0.25*(1 - Math.cos(Math.PI*t/10)) : 0`), after which the mesh returns to its original shape.
