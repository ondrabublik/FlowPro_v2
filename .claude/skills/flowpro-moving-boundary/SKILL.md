---
name: flowpro-moving-boundary
description: Flow with a moving boundary in FlowPro (ALE, moving and deforming mesh) – prescribed motion of bodies (translation, oscillation, rotation) via Rigid2D, free motion of an elastically supported body (matrices M, B, K), ALE tags in the mesh, blending functions, GCL, forces and positions in bodiesDynamic.txt, verification on a cylinder oscillating in a fluid at rest against the Stokes solution and on a cylinder on a torsional spring (free motion). Use for oscillating/rotating airfoils and cylinders, fluid–structure interaction (flutter, vortex-induced vibration), moving walls.
---

# Moving boundary – ALE (FlowPro)

Prerequisites: a mesh with ALE tags (skill `flowpro-mesh`), a flow model (`flowpro-euler`, `flowpro-navier-stokes`,
`flowpro-incompressible`, `flowpro-turbulence`), evaluation (`flowpro-postprocessing`).

## Principle

The body moves as a rigid body (translation x, y, rotation α about `rotationCenters`), the mesh deforms: a node is moved
by `b(x)·(body motion)`, where the blending function b = 1 on the body and 0 on the fixed boundary (solution of the Laplace equation
on the mesh). The equations are in the ALE form with the mesh velocity, `gcl = true` (default) ensures the discrete geometric
conservation law for BDF (a uniform flow is preserved exactly on a moving mesh). Explicit RK and `BDFIncompressible`
have no GCL – use `timeMethod = BDF` for a moving mesh (with the incompressible model only small deformations).

## Mesh

ALE tags of the segments (`point x y TYPE h ALE`, skill `flowpro-mesh`): **1 = fixed boundary** (outer), **2 = body 1**,
3 = body 2, … (`boundaryTypeALE.txt`). Boundaries with tag 0 are neither fixed nor bodies – always tag the outer
boundary with 1. FlowPro stores `neighborsALE.txt` and `blendingFunctions.txt` in `mesh/`; **delete them after changing the mesh or the tags**.
Leave enough elements around the body, the deformation is spread between the body and the outer boundary (large rotation of an airfoil
→ outer boundary far away, R ≥ 10 c).

## parameters.txt – prescribed motion

```
movingMesh = true
dynamicsModel = Rigid2D          % body dynamics module (ModuleProjects/Dynamics/RigidBody)
meshDeformationType = Rigid2D    % rigid motion of the bodies + blending functions
rotationCenters = 0.25, 0        % x, y for every body (x1, y1, x2, y2, …)
alphaMotion = 10.0*Math.PI/180*Math.sin(1.5*t)    % angle [rad], positive = counter-clockwise
%xMotion = …                     % translation in x (length in mesh units)
%yMotion = 0.05*Math.sin(0.5*t)
timeMethod = BDF
timeOrder = 2
dt = …                           % fixed step, 60–100 steps per period of the motion
newtonIters = 3                  % the recorded force is from the last but one Newton iteration
```

Motion expressions (JavaScript, variable `t`):
* **`t` is the dimensional time t·tRef** (compressible models tRef = lRef/√(pIn0/rhoIn0), incompressible lRef/|VIn|) – with
  reference values 1 it equals the solver time.
* One expression per body, separated by commas → **the expression must not contain a comma** (do not use `Math.pow(a, b)`,
  `Math.max(a, b)`; write `a*a`, ternary `c ? a : b`), **nor `%` and `#`** (start of a comment; write your own expression instead of modulo).
* **Smooth start**: an abruptly started body (jump of the wall velocity) crashes the computation in the 2nd step (negative pressure,
  zero ILU pivot). Ramp the amplitude during the first period with a smoothstep:
  `A*(t < T ? (t/T)*(t/T)*(3 - 2*t/T) : 1)*Math.sin(w*t)` (when starting from time t0 replace t → (t − t0)).
* `-p` overrides from the command line apply to the dynamics parameters too (`xMotion`, `M`, `tKick`, …).

## Free motion (fluid–structure interaction)

```
M = m, 0, 0, 0, m, 0, 0, 0, I      % 3×3 matrix by rows (x, y, α), in dimensional units (converted by pRef, lRef, tRef)
B = …                               % damping
K = 0, 0, 0, 0, ky, 0, 0, 0, ka     % stiffness
xFreedom = false                    % free degrees of freedom for every body
yFreedom = true
alphaFreedom = true
tKick = 5                           % prescribed motion (xMotion …) while t·tRef < tKick, then free
method = 3                          % 1 Adams–Bashforth 2 (default), 2 BDF1, 3 BDF2
zLength = 1                         % depth of the body (forces × zLength)
%yExternForceInTime = …             % external force as a function of time
```
Without `M`, `B`, `K` (message "Mass, stifness or damping matrix not defined!") the motion is purely kinematic.
* The equations of motion get Fx, Fy and the torque M about the current centre of rotation (`rotationCenters` + translation).
* Make the prescribed motion up to `tKick` smooth, with zero velocity at `tKick` (the velocity at the switch to free motion
  is only a first difference of the position); coordinates without a motion expression are computed dynamically even before `tKick`.
* A continuation (`continueComputation = true`) loads the state of the bodies (position, velocity, history of the method) from
  `bodiesState.txt`, which `Rigid2D` rewrites after every step – continuing from results saved every `saveRate`
  steps after a forced termination is therefore not possible (the body state is newer; a warning is printed), stop with the `STOP` file.

## Outputs

`bodiesDynamic.txt`: `t` + for every body `x y α Fx Fy M`. The position belongs to time `t + dt`, the force to the last but one
Newton iteration (details and the script `forces.py`: skill `flowpro-postprocessing`). The file is cleared for a new
computation and appended to on a continuation (`continueComputation = true`). The final deformed mesh is in `PXY.txt`
(mesh velocity `UXY.txt`) and `postprocessing` plots the final solution on it; `animation = true` also saves the mesh of the snapshots
(`animation/vertices########.txt`, `postprocessing -iITER`).

## Verification – cylinder oscillating in a fluid at rest (Stokes 1851)

Cylinder D = 1, y = A s(t) sin(ωt), A = 0.05, ω = 0.1, ν = 2e−4 (Stokes number β = D²/(νT) = 80), compressible
`NavierStokes` at rest (`pIn0 = pOut = 1`, `viscosity = 2e-4`, `conductivity = 3.889e-4`), circular domain R₂ = 10
(−3, ALE 1), cylinder 128 segments h = 0.025 (−1, ALE 2), 8,562 triangles, p = 2, BDF2, dt = T/64, 5 periods,
smooth ramp of the amplitude during the first period. Files: `examples/make_heave_geometry.py`, `examples/heaving_cylinder_stokes.txt`.
Exact solution: force = −m_a·acceleration − c·velocity, m_a = πρR²·Re[1 + 4K₁(kR)/(kR K₀(kR))], k = √(iω/ν);
the script can also correct for a finite domain with a fixed pressure and for compressibility (`--Rout`, `--c`, Bessel functions).

```
python <this skill>/examples/make_heave_geometry.py simulations/skills/heave/mesh
java -jar MeshGenerator2D.jar --batch simulations/skills/heave/mesh/geometry.geo2d simulations/skills/heave/mesh
java -jar FlowPro.jar run -g skills/heave -s w01 > simulations/skills/heave/w01.log 2>&1
python <this skill>/scripts/stokes_cylinder.py simulations/skills/heave/w01/bodiesDynamic.txt --A 0.05 --omega 0.1 --nu 2e-4 --Rout 10 --c 1.1832 --from 125.7
```

Result (verified, periods 3–5, 37 min, 16 threads, no solver failure):

| | exact | FlowPro | deviation |
|---|---|---|---|
| position (sin / cos component) | 0.05 / 0 | 0.05000 / −5e−5 | – |
| force – inertial component (added mass, c_m = 1.252) | 4.92e−4 | 4.94e−4 | 0.4 % |
| force – damping component (c = 0.269) | 1.06e−4 | 1.08e−4 | 2.4 % |
| phase of the force | | | −0.24° |

**Compressible model and acoustics**: the same problem with ω = 0.5 (ν = 1e−3) gave an inertial component 14–16 % lower –
the outer boundary with a fixed pressure reflects acoustic waves and kR₂ = ωR₂/c = 4.2 is no longer small (standing waves between the cylinder
and the boundary, exact correction −9 %, the rest are undamped waves from the start). For "incompressible" processes computed with the compressible model
keep ωR₂/c ≲ 1 (lower frequency or smaller domain) and check the correction with the script (`--Rout`, `--c`).

## Verification – free motion: cylinder on a torsional spring in a fluid at rest

Mesh as above, `NavierStokes` at rest with ν = 0.01, I = 0.2, K = 0.05 (ω₀ = 0.5), up to `tKick = 5` a smooth rotation
to α = 0.1 with zero velocity, then free damped oscillations; p = 1, BDF2, dt = 0.2, 300 steps (4 min, 16 threads).
Exact: the eigenvalue s of I s² + B s + K + Z(s) s = 0 with the viscous torque of a rotating cylinder
Z(s) = 2πμR²(2 + z K₀(z)/K₁(z)), z = R√(s/ν). Parameters: `examples/rotating_cylinder_free.txt`.

```
java -jar FlowPro.jar run -g skills/heave -s rotfull > simulations/skills/heave/rotfull.log 2>&1
python <this skill>/scripts/rotation_cylinder.py simulations/skills/heave/rotfull/bodiesDynamic.txt --I 0.2 --K 0.05 --nu 0.01 --tKick 5
```

| | exact | FlowPro | deviation |
|---|---|---|---|
| decay rate Re s | −0.1103 | −0.1115 | 1.1 % |
| frequency Im s | 0.3937 | 0.4022 | 2.2 % |
| residual I α'' + K α − M (differences of the record) | 0 | 2 % of max K α | – |

The computation split into two parts (150 steps, then `-p continueComputation=true -p tEnd=60`) differs from the uninterrupted one
by 8e−5 in the angle and 1 % in the torque.
