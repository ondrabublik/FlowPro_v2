---
name: flowpro-postprocessing
description: Running, monitoring and evaluating FlowPro computations – running in the background, residuum.txt, controlled stop, export of results (postprocessing to VTK/ParaView and text), sampling along a line and at points, animation snapshots, forces on bodies from bodiesDynamic.txt (c_D, c_L, Strouhal number), plots. Use when evaluating or checking any FlowPro computation; the other flowpro-* skills refer to it.
---

# Running and evaluating FlowPro computations

Everything in the `FlowPro` directory (`cd FlowPro`). The scripts are in `scripts/` of this skill (Python 3 + numpy, scipy, matplotlib).

## Running and monitoring

```
java -jar FlowPro.jar check -g GEOM -s SIM                         # parameters + mesh, computes nothing
java -jar FlowPro.jar run -g GEOM -s SIM > simulations/GEOM/SIM.log 2>&1   # in the background (run_in_background)
java -jar FlowPro.jar run -g GEOM -s SIM -p steps=20               # trial run (estimate of s/step)
java -jar FlowPro.jar list | params | help
```
* Always do a trial run of 10–20 steps first: time per step × number of steps = duration of the computation. Run long computations
  in the background and read the progress from the log (`grep "resid:" SIM.log | tail -n 1`), do not block waiting.
* Log line: `step/steps resid: R dt: … t: … CFL: … CPU: … AT: assembly ST: solution of the system`.
  `WARN … GMRES does not converge, time step reduced` = the linear solver did not converge; with a fixed `dt` the step is
  halved and after repeated failures the computation ends with `ERROR … algorithm does not converge`.
* `python scripts/residuum.py simulations/GEOM/SIM [--png r.png]` – summary of `residuum.txt`
  (columns: relative residual = max over the equations, time, CPU [ms], residuals of the individual equations).
* Controlled stop: create the file `simulations/GEOM/SIM/STOP` → finishes the step, saves, ends.
  Continuation: `continueComputation = true` (or `-p continueComputation=true`); `steps` is the number of steps of the next
  run, `tEnd` the absolute time. Before a new computation from scratch delete `W.txt We.txt state.txt residuum.txt`.
  `bodiesDynamic.txt` is cleared only for a new computation (`continueComputation = false`), on a continuation it is
  appended to (the first record of the continuation may repeat a time – `forces.py` takes the last record of a time).
* **Fixed `dt` and `tEnd`**: the computation ends at `tEnd`; a remainder smaller than 1e−6·dt (round-off of the summed steps,
  1200 × 0.1 = 119.99999999999746) is not computed as another step, so `tEnd = steps · dt` is fine.
  If `tEnd` is not a multiple of `dt`, the last step is shortened (BDF2 with a variable step).
* Several computations at once: sum of `threads` ≤ number of cores.

## Outputs of a computation (`simulations/GEOM/SIM/`)

`We.txt` (coefficients of the DG solution), `W.txt` (element averages), `state.txt` (step, time `t`, CFL, …),
`residuum.txt`, `animation/We########.txt` (+ `vertices########.txt` for a moving mesh) for `animation = true`,
`bodiesDynamic.txt` for a moving mesh (see below).

## Export of results

```
java -jar FlowPro.jar postprocessing mach pressure velocity -fvtk -g GEOM -s SIM          # ParaView: output/results.vtk
java -jar FlowPro.jar postprocessing density pressure velocity -ftxt -d2 -g GEOM -s SIM -oout_d2
java -jar FlowPro.jar postprocessing vorticity -ftxt -d2 -i00000400 -g GEOM -s SIM -osnap400  # animation snapshot
```
* Quantity names = the `results` parameter of the model (`mach, pressure, velocity, vorticity, temperature, density, mut, …`)
  and `W0, W1, …` (components of the solution).
* `-dN` = values at the points of elements divided into N parts per edge (DG polynomial, suitable for p ≥ 1);
  without `-d` averages at the vertices. `-iITER` = snapshot from `animation/`, `-oDIR` = output subdirectory.
* **Moving mesh**: the final solution (without `-i`) is plotted on the deformed mesh `SIM/PXY.txt` (saved together
  with `We.txt`), an animation snapshot `-iITER` on `animation/vertices########.txt`. Without `PXY.txt` `mesh/vertices.txt` is used.
* `python scripts/sample.py OUTDIR q1 q2 --line X0 Y0 X1 Y1 N --csv line.csv` – values along a line
  (`velocity` → `xvelocity`, `yvelocity`); `--points X Y X Y …` – probes.

## Forces on bodies (`bodiesDynamic.txt`)

Written by the `Rigid2D` dynamics (requires `movingMesh = true`; for a **fixed body** `alphaMotion = 0`
is enough – a trick to get the forces on a static mesh too). Row: `t` and for every body
`x y alpha Fx Fy M` (M = torque about `rotationCenters`). The forces are per unit depth in solver units,
i.e. related to `pRef · lRef`.

* **Timing**: the column `t` is the start of the step, the position belongs to time `t + dt`; the force is computed from the solution
  of the last but one Newton iteration of the step (`newtonIters = 1` → force from time `t`, `≥ 2` → approximately `t + dt`).
  This matters for the phase and frequency – shift the time by `dt` and use `newtonIters ≥ 2`.
* `python scripts/forces.py simulations/GEOM/SIM/bodiesDynamic.txt --from T0 --L 1 [--png f.png]` +
  - incompressible models: `--q 0.5 --U 1` (default; `pRef = ρ|VIn|²`, velocity in units of |VIn|),
  - compressible models: `--stagnation KAPPA PIN0 RHOIN0 POUT` (q and U of the free stream from the stagnation state).
  Prints the mean and amplitude of c_x, c_y, c_m, the lift frequency (FFT and zero crossings) and St = f L / U.
  Verified on `NACA0012pitching/k175` (U∞ = 0.4556, frequency = ω/2π = 0.2538).

## Dimensionless units (summary)

| models | pRef | rhoRef | vRef | tRef |
|---|---|---|---|---|
| compressible (`NavierStokes`, `SpalartAllmaras`, `KOmegaSST`, …) | `pIn0` | `rhoIn0` | √(pIn0/rhoIn0) | lRef/vRef |
| incompressible (`IncompressibleNavierStokes*`) | ρ·\|VIn\|² | `density` | \|VIn\| | lRef/\|VIn\| |

`dt`, `tEnd`, `timeSaveRate` and the output time are in `tRef`; the body motion scripts (`xMotion`, …) get the
dimensional time `t·tRef`. For simplicity choose reference values 1 (`pIn0 = rhoIn0 = lRef = 1`, or `|VIn| = 1`).
