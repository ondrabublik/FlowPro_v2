---
name: flowpro-mesh
description: Generating 2D computational meshes for FlowPro (MeshGenerator2D, the .geo2d format, batch mode, unstructured triangular and structured block meshes, boundary conditions, ALE body tags, quality check). Use when the user wants to create, modify or check a mesh for a FlowPro computation – channel, NACA airfoil, cylinder, flat plate, domain with a body.
---

# Mesh generation (FlowPro)

The mesh lives in `FlowPro/simulations/GEOMETRY/mesh/`, computations are sibling directories
(`simulations/GEOMETRY/SIMULATION/parameters.txt`). All commands below are run in the `FlowPro` directory.
The JARs are built by `gradlew build` in the package root (if `FlowPro/MeshGenerator2D.jar` is missing).

## Workflow

1. **Write the geometry with a script** – `scripts/geo2d.py` of this skill (class `Geometry`, functions
   `rectangle`, `circle`, `naca4`, `polyline`). Save the script next to the mesh (e.g. `simulations/NAME/make_geometry.py`)
   so the mesh can be reproduced. Edit `.geo2d` by hand only for small changes.
2. **Generate the mesh in batch mode**:
   ```
   java -jar MeshGenerator2D.jar --batch simulations/NAME/mesh/geometry.geo2d simulations/NAME/mesh
   ```
   It prints the number of elements and the quality (min. angle, "poor elements"). Exit code ≠ 0 = geometry error.
3. **Check it**: `python <skill>/scripts/mesh_info.py simulations/NAME/mesh` – numbers of faces and lengths
   per boundary condition type (verify that every boundary has the intended type), ALE tags, min. angle, sizes.
4. **Verify in FlowPro** after creating `parameters.txt` (see the model skills):
   `java -jar FlowPro.jar check -g NAME -s SIMULATION` (loads the mesh, computes nothing).

GUI alternative: `FlowProControl.bat` → tab *Mesh* (tasks in `simulations/GUI/NAME`), standalone
`MeshGenerator2D.bat`. The file format is the same; the GUI is handy for looking at the result.

## The `.geo2d` format

```
meshSize 1.0          # global (maximum) cell size
minAngle 28           # Ruppert refinement, 25–30 is reasonable (more may not terminate)
grading 0.25          # growth of the cell size with the distance from refinements (smaller = more gradual)
smoothing 5           # Laplacian smoothing
refinement 0          # each level splits a triangle into 4 (unstructured mesh only)
blockTriangles 0      # split blocks into triangles (1) instead of quadrilaterals (0)
region box x0 y0 x1 y1 h      # max. size h in a rectangle (wake, surroundings of a body)
region circle cx cy r h
loop                  # closed loop; the loop with the largest area = outer boundary, the others = holes/bodies
point x y TYPE h ALE  # the attributes belong to the segment from this point to the next one; h = NaN → global size
end
```

Structured blocks (like blockMesh): `vertex x y`, `block v0 v1 v2 v3 nx ny gx gy t0 t1 t2 t3 a0 a1 a2 a3`
(vertices counter-clockwise, side k goes from v[k] to v[k+1], sides 0/2 have nx cells, 1/3 ny cells,
`gx`/`gy` = ratio of the last to the first cell in the direction v0→v1 / v1→v2), `arc a b x y` (edge as an arc through a point).
A side shared by two blocks must have the same division (`ny`, `gy`); its type is ignored (use 0).
**A geometry with blocks is meshed by blocks only – loops (`loop`) are then ignored and `refinement` does not apply.**

## Boundary conditions (segment type)

| type | compressible models (`NavierStokes`, `SpalartAllmaras`, `KOmegaSST`, …) | incompressible (`IncompressibleNavierStokes*`) |
|---|---|---|
| −1 | wall (viscous: no-slip, adiabatic; inviscid: slip) | no-slip wall |
| −2 | inlet (stagnation quantities `pIn0`, `rhoIn0`/`TIn0`; direction `attackAngle` in radians, without it normal to the boundary) | inlet, velocity `VIn` |
| −3 | outlet (static pressure `pOut`, supersonic outlet without a condition) | outlet, pressure `pOut` |
| −4 | inviscid (slip) wall, symmetry | slip wall, symmetry |
| −5 | free inlet/outlet – model `NavierStokesR` only | – |

The boundaries are written to `boundaryType.txt` (`type i j` for every boundary edge).

## ALE tags (moving mesh)

Third attribute of a point / last quadruple of a block. `0` = no tag, `1` = **fixed** boundary (outer),
`2` = body 1, `3` = body 2, … (number of bodies = max. tag − 1). Written to `boundaryTypeALE.txt`.
The tags do no harm in static computations, so always add them (outer boundary 1, body 2) – the mesh can then
be reused for a moving boundary (skill `flowpro-moving-boundary`).

## Size recommendations

* Body: ≥ 80–100 segments on the perimeter of a cylinder/airfoil (`circle(..., n=96, h=0.035)` for D = 1),
  airfoil `naca4(n=80–90)` with `h ≈ 0.01·c`, refine the leading and trailing edges with `region_circle`.
* Wake: `region_box` behind the body with h ≈ 0.1–0.15 D (for vortex shedding), at least 10 D behind the body.
* Boundary distances: inlet ≥ 8 D upstream of the body, outlet ≥ 15–20 D downstream, side boundaries ≥ 8 D
  (channel blockage D/H ≤ 6 %), circular far field R ≈ 12–20 c.
* Higher order DG (p = 2) needs fewer elements: a coarser mesh + `order = 3` is often cheaper than a fine mesh and p = 1.
* Turbulent (RANS) boundary layer: block mesh with the first cell at y⁺ ≈ 1 (p = 1) to 5 (p = 2);
  y₁ ≈ y⁺ / (Re · √(c_f/2)), c_f ≈ 0.0576 Re_x^(−1/5). `gy` is found by bisection (example
  `scripts/example_plate_blocks.py`).
* Scale: the mesh is in length units, the computation multiplies it by `meshScale / lRef` (default 1). Usually draw
  the mesh with the reference length 1 (diameter, chord).

## Derived files

FlowPro stores `wallDistance.txt` (turbulence models), `neighborsALE.txt` and `blendingFunctions.txt`
(moving mesh) in `mesh/`. The generator deletes them on export; **when you change the mesh otherwise (manual edit, copy), delete them**,
otherwise the old values are used. Results computed on the old mesh (`W.txt`, `We.txt`, `state.txt`) are invalid –
start a new computation with `continueComputation = false`.

## Verification

Meshes created by this workflow (batch mode + `mesh_info.py` + `FlowPro.jar check`) and used in the verification
computations of the other skills:

| mesh | generator | result | used for |
|---|---|---|---|
| cylinder in a channel (`example_cylinder.py`) | Ruppert | 11,382 triangles, min. angle 29.0°, 0.5 s | Re = 100 incompressible and compressible |
| cylinder in a circle R = 10 (128 segments, h = 0.025) | Ruppert | 8,562 triangles, min. angle 28.3° | oscillating cylinder (ALE) |
| flat plate (`example_plate_blocks.py`) | 2 blocks | 2,480 quadrilaterals, first cell 5e−5 (y⁺ ≈ 2) | turbulent flat plate |
| shock tube 200 × 4 | 1 block | 800 quadrilaterals | Sod problem |

## Examples

* `scripts/example_cylinder.py` – cylinder D = 1 in a 30 × 16 channel (inlet −2, outlet −3, sides −4,
  cylinder −1 with ALE 2, outer ALE 1), wake refinement; ≈ 11,400 triangles, min. angle 29°.
* `scripts/example_plate_blocks.py` – flat plate of length 2 with a leading region, 2 blocks, first cell 5e−5.
* `simulations/NACA0012pitching/scripts/make_geometry.py` – NACA0012 in a circular field R = 12.
* `simulations/examples/GAMMgenerated/mesh/geometry.geo2d` – GAMM channel.
