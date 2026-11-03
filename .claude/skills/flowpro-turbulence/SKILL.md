---
name: flowpro-turbulence
description: Turbulent flow in FlowPro (RANS and URANS) – Spalart–Allmaras and k-ω SST models for compressible flow, Spalart–Allmaras for incompressible flow, near-wall mesh (y+), wall distance, turbulent inlet conditions, steady and unsteady computations, verification on a turbulent flat plate (skin friction coefficient). Use for high Reynolds number flows – airfoils, flat plates, channels, wakes.
---

# Turbulent flow – RANS / URANS (FlowPro)

Prerequisites: a mesh refined towards the walls (skill `flowpro-mesh`, block mesh), basic setup of the flow model
(`flowpro-navier-stokes`, `flowpro-incompressible`), evaluation (`flowpro-postprocessing`).
Templates: `Tools/FlowProControl/src/main/resources/flowpro/control/templates/rans_sa.txt`, `rans_sst.txt`,
`incompressible_sa.txt` (the model constants – copy them from there).

## Choice of the model

| model | flow | unknowns | note |
|---|---|---|---|
| `SpalartAllmaras` | compressible | ρ, ρu, ρv, E, ρν̃ | most robust, recommended start |
| `KOmegaSST` | compressible | ρ, ρu, ρv, E, ρk, ρω | Menter SST, Sutherland viscosity (`SuthConst`) |
| `KOmega`, `KOmegaSutherland` | compressible | | older variants |
| `SpalartAllmarasTrans*` | compressible | | with a transition model (experimental) |
| `IncompressibleNavierStokesSpalartAllmaras` | incompressible | p, u, v, ν̃ | "negative SA" (Allmaras 2012), ν̃ may be negative |

Flow parameters (`reynolds`, `pIn0`, `pOut`, … or `VIn`, `viscosity`) as for the laminar models,
`isFlowViscous` is not given. Output quantities: `mut` (SA), `k, omega, turbulentviscosity` (SST).

## Turbulence parameters

* SA: `vtIn` = ν̃/ν at the inlet (3 = fully turbulent free stream, 0.1 – laminar oncoming flow),
  constants `sigma cb1 cb2 ka cw2 cw3 cv1 ct1–ct4 Prt C_prod` from the template (required).
* SST: `kIn` = 1.5 (I·U∞)² and `omIn` = kIn/ν_t in solver units (stagnation state, lRef); constants
  `a1 sk1 som1 alpha1 beta1 betast sk2 som2 alpha2 beta2 Prt SuthConst` from the template.
* The wall distance is computed automatically from the walls −1 at the first start and saved to `mesh/wallDistance.txt`
  – **delete the file after changing the mesh (or the wall types)**. A slip wall −4 does not count as a wall.

## Near-wall mesh

* First cell: y⁺ ≈ 1 for p = 1, y⁺ ≲ 5 for p = 2 (the polynomial inside the element resolves the viscous sublayer).
  y₁ = y⁺ · L / (Re_L · √(c_f/2)), estimate c_f ≈ 0.0576·Re_x^(−1/5).
* Block mesh with geometric clustering (`gy` = ratio of the last to the first cell, 30–60 cells across the boundary layer
  and the domain), see `flowpro-mesh/scripts/example_plate_blocks.py`; C-/O-blocks with `arc` around an airfoil.
  The unstructured mesh (Ruppert) cannot make anisotropic cells – for RANS only as the outer part or for rough estimates.

## Solver

Steady RANS:
```
timeMethod = BDF
timeOrder = 1
CFL = 0              % adaptive (SER), or a fixed CFL = 5–50 as in the templates
maxCFL = 1000
residuum = 1e-6
linearSolver = gmres
preconditioner = ilu0          % incompressible SA: blockilu0 + iterativeSolverTol = 0.1
newtonIters = 1
order = 2
dampTol = 0
```
URANS (unsteady): `timeOrder = 2`, fixed `dt`, `newtonIters = 3`, `newtonIterTol = 1e-3`; start from a steady RANS
(`continueComputation = true`). Time and units as in `flowpro-navier-stokes` / `flowpro-incompressible`.

* The step at the wall is small (cells 1e−5–1e−4), time accuracy plays no role in a steady computation – adaptive CFL.
  The residual of the turbulence equation (last column of `residuum.txt`) is usually 1–2 orders above the others and controls
  the growth of the CFL; also watch physical quantities (c_f, forces), not just the residual.
* Negative ν̃ in the wake on a coarse mesh (incompressible SA) is admissible (negative SA), the remedy is a finer mesh.

## Verification – turbulent flat plate, Re = 10⁶/m, M = 0.2 (SA)

Mesh `flowpro-mesh/scripts/example_plate_blocks.py` (plate x ∈ [0, 2], leading region x ∈ [−0.5, 0] symmetry −4, upper
boundary −4, 2 blocks 12+50 × 40 quadrilaterals, first cell 5e−5 → y⁺ ≈ 2), model `SpalartAllmaras`, `vtIn = 3`.
Skin friction coefficient from the velocity gradient at the wall: c_f = 2/(Re·U∞) · ∂u/∂y|_w (solver units, lRef = 1),
script `scripts/plate_cf.py`. Parameters: `examples/plate_sa.txt`.

```
java -jar FlowPro.jar run -g skills/plate -s sa
java -jar FlowPro.jar postprocessing velocity mut -ftxt -d1 -g skills/plate -s sa -oout_d1
python <this skill>/scripts/plate_cf.py simulations/skills/plate/sa/out_d1 --re 1e6 --U 0.2357 --xmax 2 --png cf.png
```

Result (verified): steady RANS, BDF1, adaptive CFL (`CFL = 0`, `maxCFL = 1000`), residual 1e−6 in 1411 steps
(15 min, 6 threads). For the first ~900 steps the residual of the SA equation held the CFL around 100–130 (step 7e−3), then the CFL
rose to 1000 and the computation converged quickly – a residual stagnating around 1e−3 at the beginning is therefore no reason to intervene.

| x | Re_x | c_f FlowPro | 0.0576 Re_x^−0.2 | White | deviation from White |
|---|---|---|---|---|---|
| 0.26 | 2.6e5 | 0.00417 | 0.00477 | 0.00489 | −15 % |
| 0.48 | 4.8e5 | 0.00388 | 0.00420 | 0.00431 | −10 % |
| 0.97 | 9.7e5 | 0.00351 | 0.00365 | 0.00378 | −7 % |
| 1.55 | 1.5e6 | 0.00326 | 0.00333 | 0.00348 | −6 % |
| 1.88 | 1.9e6 | 0.00316 | 0.00320 | 0.00336 | −6 % |

y⁺ of the first cell ≈ 2.1. The flow is fully turbulent (laminar Blasius would give a 5× smaller c_f); from x ≈ 1 the deviation
from the power-law correlation is 1–4 %. The larger deviation near the leading edge: the correlations assume turbulence from the leading
edge and the mesh is coarse there in the x direction (50 cells, `gx = 20`).
