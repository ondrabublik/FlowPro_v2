# FlowPro v2

FlowPro is an open-source Java solver for systems of hyperbolic and convection–diffusion partial
differential equations based on the **discontinuous Galerkin finite element method (DGFEM)**.
Mathematical models are plug-in modules, so you can define your own equations without touching the solver core.

This repository contains the whole package in one Gradle build: the solver, the model API, the bundled
physical models and the GUI tools. Unlike the original
[FlowProPackage](https://github.com/ondrabublik/FlowProPackage), it does not use git submodules.

## Features

* DG on unstructured 2D/3D meshes, arbitrary polynomial order, also finite-volume reconstruction (p = 0)
* Implicit (BDF1/BDF2, Newton + GMRES with `ilu0`, `blockilu0`, `blockjacobi` preconditioners)
  and explicit (Runge–Kutta) time stepping, local time stepping, adaptive CFL
* Moving and deforming meshes (ALE with discrete geometric conservation law), coupling with rigid and elastic bodies
* Parallel computation by domain decomposition
* Stabilization: artificial viscosity, limiters, positivity limiter
* Bundled models: compressible Euler / Navier–Stokes, RANS (Spalart–Allmaras, k-ω, k-ω SST),
  incompressible Navier–Stokes (laminar and Spalart–Allmaras), 3D flow in a rotating frame,
  shallow water, scalar equations, Schrödinger equation
* GUI for managing tasks, generating 2D meshes, running and monitoring computations and viewing results

## Repository layout

| Directory | Contents |
|---|---|
| `FlowPro` | the solver; also the working directory with built JARs, launch scripts and `simulations` |
| `FlowProAPI` | interface between the solver and the models (equations, dynamics, ...) |
| `ModuleProjects` | models implemented against FlowProAPI (`Equations`, `Dynamics`, `SolutionMonitor`, ...) |
| `Tools/FlowProControl` | main GUI: tasks, mesh generator, parameters, running, monitoring, results |
| `Tools/MeshGenerator2D` | standalone 2D mesh generator, also with a batch mode |
| `Tools/UMFPACKWrapper64` | 64-bit native wrapper of the UMFPACK direct solver |
| `FlowProManager` | the original GUI, kept for compatibility |

## Requirements

* Java 17 or newer (tested with Java 21)
* [ParaView](https://www.paraview.org/) or another VTK viewer, optional, for visualisation of the results

Gradle does not need to be installed, the build uses the included Gradle wrapper.

## Quick start

```
git clone https://github.com/ondrabublik/FlowPro_v2.git
cd FlowPro_v2
gradlew build
```

The build compiles all projects, runs the tests and puts `FlowPro.jar`, `FlowProControl.jar`
and `MeshGenerator2D.jar` into the `FlowPro` directory. Modules are copied into `FlowPro/modules`.

### GUI

Run `FlowProControl.bat` in the root directory (or `java -jar FlowProControl.jar` in `FlowPro`).

* a **task** (`FlowPro/simulations/GUI/NAME`) has one mesh and any number of computations
* new computations can start from model templates (Euler, Navier–Stokes, RANS SA, RANS k-ω SST,
  incompressible laminar and SA) or from the parameters of another computation
* the *Mesh* tab draws the geometry (polygons, rectangles, circles, NACA profiles, channels), sets
  boundary conditions and local cell sizes and generates a triangular mesh (Delaunay + Ruppert refinement)
* parameters are edited in a table with descriptions; computations can be run, stopped (results are saved)
  and monitored with a live convergence plot
* results are shown as a colour map of solution components or model quantities, also inside the elements
  for higher orders

### Command line

```
cd FlowPro
java -jar FlowPro.jar run -g examples/GAMM -s default
java -jar FlowPro.jar postprocessing mach pressure -fvtk -g examples/GAMM -s default
```

The results are written to `simulations/examples/GAMM/default/output` and can be opened in ParaView.

| Command | Meaning |
|---|---|
| `run` | run the computation (locally, or in parallel with `-d N`) |
| `check` | validate `parameters.txt` and the mesh, nothing is computed |
| `list` | list geometries and simulations in `simulations` |
| `params` | describe all parameters of `parameters.txt` |
| `postprocessing [names]` | export results (`-fFORMAT`, `-dN`, `-oDIR`, ...) |
| `help` | full list of commands and options |

| Option | Meaning |
|---|---|
| `-g, --geometry NAME` | geometry, i.e. directory `simulations/NAME` (default from `args.txt`) |
| `-s, --simulation NAME` | simulation, i.e. directory `simulations/GEOMETRY/NAME` (default from `args.txt`) |
| `-d, --domains N` | number of domains of the parallel computation |
| `-p, --set KEY=VALUE` | override a value of `parameters.txt` without changing the file, may be repeated |

Parameters are checked before the computation starts: unknown names, wrong types and missing values
are reported at once (e.g. *parameter 'CFLL' is not known, did you mean 'CFL'?*).
A file named `STOP` in the simulation directory stops the computation after the current step and saves
the results; it can be continued with `continueComputation = true`.

`run.bat` / `run.sh` in `FlowPro` pass their arguments to `run` with larger JVM memory,
e.g. `run.bat -g examples/GAMM -s default -p steps=100`.

## Simulation directory

```
FlowPro/simulations/GEOMETRY/
  mesh/                 vertices.txt, elements.txt, elementType.txt, boundaryType.txt, neighbors.txt, ...
  SIMULATION/
    parameters.txt      model, numerical method, solver and output settings
    output/             results
```

Examples: `examples/GAMM` (GAMM channel) and `examples/GAMMgenerated` (the same channel meshed
by the built-in generator, with its `geometry.geo2d`).

## Writing your own model

A model is a class implementing the `Equation` interface of [FlowProAPI](FlowProAPI) (fluxes, sources,
boundary conditions, eigenvalues, output quantities). Put it into a Gradle project under `ModuleProjects`
following the existing ones (e.g. `ModuleProjects/Equations/ScalarEquations`); `settings.gradle` includes
every directory with a `build.gradle` automatically. Select the model in `parameters.txt`.

## Tests

`gradlew build` runs the JUnit tests of `FlowPro/src/test/java` (about 10 s): order of convergence
on manufactured solutions, free-stream preservation on a deforming mesh, analytic Jacobians
against finite differences and linear solvers against a direct solver.

## Claude Code skills

`.claude/skills` contains skills (in Czech) for [Claude Code](https://claude.com/claude-code) that describe
how to set up, run and evaluate typical tasks, each verified on a test problem:

| Skill | Task | Verification |
|---|---|---|
| `flowpro-mesh` | 2D meshes (`.geo2d`, batch mode, blocks, boundary types, ALE tags) | meshes of all the cases below |
| `flowpro-euler` | unsteady compressible inviscid flow | Sod shock tube vs. exact solution |
| `flowpro-navier-stokes` | unsteady compressible viscous flow | cylinder, Re = 100, M = 0.2 |
| `flowpro-incompressible` | unsteady incompressible viscous flow | cylinder, Re = 100 |
| `flowpro-turbulence` | RANS / URANS (Spalart–Allmaras, k-ω SST) | turbulent flat plate, skin friction |
| `flowpro-moving-boundary` | moving and deforming meshes (ALE, rigid bodies) | cylinder oscillating in a fluid at rest vs. Stokes solution |
| `flowpro-postprocessing` | running, monitoring, export, forces, Strouhal number | used by all the skills |

## Changes in version 2

Version 2 fixes a number of bugs of the original code (Runge–Kutta stages, block ILU, adaptive CFL,
BDF2 start, Spalart–Allmaras model, quadrature, ...), improves the method (GCL, positivity limiter,
residual-based convergence, RCM ordering) and adds the new command line and the GUI tools.
The detailed list (in Czech) is in [ZMENY_V2.md](ZMENY_V2.md).

## Authors

Ondřej Bublík, Aleš Pecka, Jan Vimmr – University of West Bohemia in Pilsen

If you use FlowPro in your work, please cite it as described in [CITATION.cff](FlowPro/CITATION.cff).

## License

BSD license, see [license.txt](license.txt).
