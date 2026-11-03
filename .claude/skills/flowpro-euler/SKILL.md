---
name: flowpro-euler
description: Unsteady flow of a compressible inviscid fluid (Euler equations) in FlowPro – parameters.txt setup (model NavierStokes without viscosity or AdiabaticEulers), initial condition by the script initScript.js, RK3 / BDF2 time integration with a fixed step, tEnd, saving snapshots, shock stabilisation, verification on the Sod shock tube. Use for inviscid unsteady problems – shock tubes, shock wave propagation, acoustic and unsteady external flows, transients in channels.
---

# Unsteady Euler equations (FlowPro)

Prerequisites: a mesh in `simulations/GEOMETRY/mesh/` (skill `flowpro-mesh`), built JARs (`gradlew build`).
Commands are run in the `FlowPro` directory. Results and their processing: skill `flowpro-postprocessing`.

## Choice of the model

| model | equations | note |
|---|---|---|
| `NavierStokes` + `isFlowViscous = false` | full Euler equations (ρ, ρu, ρv, E) | standard; walls −1 and −4 are both slip walls |
| `AdiabaticEulers` | isentropic Euler equations (no energy) | only without shocks / with weak shocks |

Template: `Tools/FlowProControl/src/main/resources/flowpro/control/templates/euler.txt` (steady channel flow);
adapt it for an unsteady problem as below.

## Dimensionless quantities – important

The model converts everything to dimensionless quantities: `pRef = pIn0`, `rhoRef = rhoIn0`, `vRef = √(pRef/rhoRef)`,
`tRef = lRef / vRef`. **`dt`, `tEnd`, `timeSaveRate` and the time in `residuum.txt` are in units of `tRef`**, the state W
and the values of `initScript.js` are dimensionless (ρ/ρRef, ρu/(ρRef vRef), E/pRef).
The simplest choice is `pIn0 = 1`, `rhoIn0 = 1`, `lRef = 1` – the given and the computed values are then identical.
The model requires the inlet/outlet parameters (`isInletSupersonic`, `pIn0`, `rhoIn0`, `pOut`) even if the mesh
has no inlet or outlet – they define the reference state. The angle `attackAngle` is in radians.

## Unsteady parameters.txt (core)

```
model = NavierStokes
results = mach, pressure, velocity, density
dimension = 2
isFlowViscous = false
cv = 1
kappa = 1.4
isInletSupersonic = false
pIn0 = 1
rhoIn0 = 1
pOut = 0.9            % subsonic outlet: pIn0/pOut determines the Mach number

continueComputation = false
steps = 100000        % upper limit, the computation is ended by tEnd
tEnd = 0.2            % final time (in tRef)
timeSaveRate = 0.01   % saving by time (or saveRate = N steps)
animation = true      % every save also to animation/We########.txt (+ vertices for a moving mesh)

spatialMethod = DGpure
order = 2             % p + 1; p = 1 is robust, p = 2 for smooth fields
dampTol = 3           % shock detector + artificial viscosity (0 = off, for smooth problems)
dampConst = 0
penalty = 1
threads = 8
```

Time integration – choose one variant:

* **Explicit RK3 (shocks, short transients)**: `timeMethod = RK`, `timeOrder = 3`, `CFL = 0.3`,
  `linearSolver = gmres`, `preconditioner = none`, `newtonIters = 1`. The CFL is already scaled by the order
  (the step contains 1/(2p+1)), 0.3 works for p = 1 and 2. The step is global (time accurate).
* **Implicit BDF2 with a fixed step (long processes, low Mach number)**: `timeMethod = BDF`,
  `timeOrder = 2`, `dt = ...` (instead of `CFL`; with both the CFL is used), `linearSolver = gmres`,
  `preconditioner = ilu0`, `newtonIters = 3–5`, `newtonIterTol = 1e-4`. The first step is BDF1 automatically.
  Choose the step by the physics, not by stability (e.g. 50–200 steps per period).
* **Do not use for time-accurate computations**: adaptive CFL (`CFL <= 0`) – it changes the step by the residual;
  `newtonIters = 1` with BDF and a large step – nonlinear error in every step.

Initial condition:
* `initScript.js` in the simulation directory – `function init(x) { return [rho, rho*u, rho*v, E]; }`,
  `E = p/(kappa-1) + 0.5*rho*(u*u + v*v)`; evaluated at the element centroid (piecewise constant).
* `initW.txt` – values for every element (row = element).
* neither: constant state of the model (rest / free stream given by the boundary conditions).
* steady state as the start: first a steady computation (BDF1, adaptive `CFL = 0` or `CFL = 5–50`,
  `residuum = 1e-6`), then in the same directory `continueComputation = true`, `dt`, `timeOrder = 2`, `tEnd`
  (time continues; `tEnd` is the absolute time, not the length of the run – the current time is `t=` in `state.txt`).

## Running

```
java -jar FlowPro.jar check -g GEOM -s SIM                 # check of the parameters and the mesh
java -jar FlowPro.jar run -g GEOM -s SIM > SIM.log 2>&1     # run long computations in the background
java -jar FlowPro.jar run -g GEOM -s SIM -p dt=5e-4 -p tEnd=0.1   # temporary override of parameters
```
Stop with saving: a file `STOP` in the simulation directory. Output line
`N/steps resid: … dt: … t: … CFL: …`; `residuum.txt`: columns relative residual, time, CPU [ms], residuals of the equations.
In unsteady problems the residual does not go to zero – watch the time `t` and the physical quantities.

## Verification (sample problem – Sod shock tube)

Mesh of 200 × 4 quadrilaterals on [0, 1] × [0, 0.02], all boundaries −4 (`block 0 1 2 3 200 4 1 1 -4 -4 -4 -4 0 0 0 0`),
`initScript.js`: left (x < 0.5) ρ = 1, p = 1, right ρ = 0.125, p = 0.1, `tEnd = 0.2`, p = 1, `dampTol = 3`.
The files are in `examples/sod/` of this skill (`geometry.geo2d`, `initScript.js`, `parameters_rk3.txt`, `parameters_bdf2.txt`):

```
mkdir -p simulations/skills/sod/mesh simulations/skills/sod/rk3
cp <skill>/examples/sod/geometry.geo2d simulations/skills/sod/mesh/
cp <skill>/examples/sod/initScript.js simulations/skills/sod/rk3/
cp <skill>/examples/sod/parameters_rk3.txt simulations/skills/sod/rk3/parameters.txt
java -jar MeshGenerator2D.jar --batch simulations/skills/sod/mesh/geometry.geo2d simulations/skills/sod/mesh
java -jar FlowPro.jar run -g skills/sod -s rk3
java -jar FlowPro.jar postprocessing density pressure velocity -ftxt -d2 -g skills/sod -s rk3
python <flowpro-postprocessing>/scripts/sample.py simulations/skills/sod/rk3/output density pressure velocity --line 0 0.01 1 0.01 400 --csv line.csv
python <this skill>/scripts/riemann_exact.py --left 1 0 1 --right 0.125 0 0.1 --t 0.2 --compare line.csv
```
Result (verified, mean |ρ − ρ_exact| along the tube axis):

| variant | steps | time | L1 error ρ | L1 error p |
|---|---|---|---|---|
| RK3, p = 1, CFL 0.3 | 886 | 3 s | 2.5e−3 | 2.0e−3 |
| BDF2, p = 1, dt = 1e−3, 4 Newton iterations | 200 | 31 s | 2.4e−3 | 1.8e−3 |
| RK3, p = 2, CFL 0.3 | 1464 | 65 s (2 threads) | 1.3e−3 | 1.1e−3 |

The positions of the shock, the contact discontinuity and the rarefaction agree with the exact solution, the overshoot at the shock is < 10 % (velocity). The script `riemann_exact.py` solves any Riemann problem (states ρ, u, p).

## Common problems

* `NaN` / negative pressure at the start: a strong discontinuity in the initial condition without `dampTol` → set `dampTol = 3`,
  lower the CFL, `order = 2`. The positivity limiter (`positivityLimiter = true`) is on by default.
* Oscillations behind a shock with p ≥ 2: increase `dampConst` (e.g. 0.1) or refine the mesh.
* Waves reflected from the outlet: the subsonic outlet −3 holds `pOut`, acoustic waves are partly reflected; move the outlet away from the region of interest.
* The result does not match the expected Mach number: subsonic inlets are given by the ratio `pIn0/pOut`
  (M = √(2/(κ−1)·((pIn0/pOut)^((κ−1)/κ) − 1))), not by a velocity.
