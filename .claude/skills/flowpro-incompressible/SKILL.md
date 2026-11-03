---
name: flowpro-incompressible
description: Nestacionární proudění nestlačitelné vazké tekutiny (nestlačitelné Navierovy–Stokesovy rovnice) ve FlowPro – model IncompressibleNavierStokes, DGpureIncompressible + BDFIncompressible (BDF2, pevný krok), nastavení řešiče (blockilu0, iterativeSolverTol), vstupní profily, Reynoldsovo číslo, síly a Strouhalovo číslo, ověření na obtékání válce při Re = 100. Použij pro laminární nestlačitelné úlohy – obtékání těles, odtrhávání vírů, kanály, kavity.
---

# Nestacionární nestlačitelné Navierovy–Stokesovy rovnice (FlowPro)

Předpoklady: síť (skill `flowpro-mesh`), spouštění a vyhodnocení (skill `flowpro-postprocessing`).
Turbulentní nestlačitelné proudění (Spalart–Allmaras): skill `flowpro-turbulence`.

## Model a jednotky

* `model = IncompressibleNavierStokes`, neznámé W = (p, u, v) – tlak je první složka (`W0`).
* Bezrozměrné veličiny: rychlost / |VIn|, délka / `lRef`, čas / (lRef/|VIn|), tlak / (ρ|VIn|²).
  **Re = |VIn| · lRef / viscosity** (nebo přímo `reynolds`, má přednost). Vypíše se při startu (`Reynolds number …`).
  `VIn` nesmí být nulové (referenční rychlost) – úlohy „v klidu“ (kmitající těleso v klidné tekutině) řeš stlačitelným
  modelem při nízkém Machově čísle (skill `flowpro-moving-boundary`).
* `pOut` se zadává přímo v bezrozměrných jednotkách ρ|VIn|² (obvykle 0).
* Okrajové podmínky: −1 stěna no-slip (na pohyblivé síti rychlost stěny), −2 vstup (rychlost), −3 výstup
  (tlak `pOut`, rychlost extrapolovaná), −4 klouzavá stěna / symetrie.
* `inletType`: `velocity` (výchozí, konstantní `VIn`), `parabola` (parabolický profil s průměrem |VIn|, **napevno
  pro kanál y ∈ [0, 1]**), `parabola2` (konstantní `VIn`, x-složka náběhem (1 − cos(πt/2))/2 během t·tRef < 2).

## parameters.txt (ověřené nastavení)

```
model = IncompressibleNavierStokes
results = pressure, velocity, vorticity
dimension = 2
isFlowViscous = true
viscosity = 0.01      % Re = 1 * 1 / 0.01 = 100
density = 1
VIn = 1, 0
pOut = 0
lRef = 1              % referenční délka = průměr / tětiva / výška kanálu

continueComputation = false
steps = 1200          % délka běhu = steps * dt
tEnd = 120            % = steps * dt
saveRate = 200
%animation = true     % snímky pro vizualizaci (velké soubory)

spatialMethod = DGpureIncompressible
timeMethod = BDFIncompressible
timeOrder = 2
dt = 0.1              % 50–100 kroků na periodu děje (St = 0.165 -> T = 6)
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

Klíčová nastavení řešiče (ověřeno, viz níže):
* **`preconditioner = blockilu0`** – `ilu0` skončí „zero pivot“ (rovnice kontinuity nemá časovou derivaci,
  elementy se dvěma hraničními stěnami mají nulový diagonální tlakový člen). `blockjacobi` nekonverguje.
* **`iterativeSolverTol = 0.1`** – GMRES(30) s 5 restarty nedosáhne pro tuto soustavu výchozí tolerance 1e−2
  (v 2. kroku „GMRES does not converge“, krok se půlí až do pádu – zmenšení `dt` nepomáhá, tlaková část
  soustavy na `dt` nezávisí). Nepřesný Newton pak potřebuje 2–3 iterace (`newtonIters = 3`, `newtonIterTol = 1e-3`).
* `timeOrder = 2` s pevným `dt`; první krok je automaticky BDF1. Impulzní start z `VIn` (výchozí počáteční
  podmínka) je v pořádku.
* `order = 2` (p = 1) pro obtékání s odtrháváním; `order = 3` je přesnější, ale několikanásobně dražší na krok.

## Síly a odtrhávání vírů

Síly se zapisují jen přes dynamiku těles. Pro nehybné těleso přidej (síť musí mít ALE značky:
vnější hranice 1, těleso 2):
```
movingMesh = true
dynamicsModel = Rigid2D
meshDeformationType = Rigid2D
rotationCenters = 0, 0
alphaMotion = t < 2 ? 0.1*(1 - Math.cos(Math.PI*t)) : 0   % krátké pootočení tam a zpět rozbije symetrii
```
Bez narušení symetrie se odtrhávání vírů rozvíjí jen z numerického šumu (stovky časových jednotek).
Pootočení válce tam a zpět (kruhová stěna se točí, síť se deformuje jen dočasně) zrychlí start – nasycené
odtrhávání od t ≈ 40; u nekruhového tělesa krátký výpočet s šikmým `VIn = 1, 0.05` a pokračování
(`continueComputation = true`) s `VIn = 1, 0`, nebo `initScript.js` s asymetrickou poruchou.
Vyhodnocení: `python <flowpro-postprocessing>/scripts/forces.py simulations/GEOM/SIM/bodiesDynamic.txt --from 60`
(q = 0,5, U = 1 výchozí → c_D, c_L, St).

## Ověření – válec při Re = 100

Síť `flowpro-mesh/scripts/example_cylinder.py` (válec D = 1, kanál [−8, 22] × [−8, 8], 11 382 trojúhelníků,
96 stěn na válci), parametry výše (`examples/cylinder_Re100.txt`).

```
java -jar FlowPro.jar run -g skills/cylinder -s incRe100 > simulations/skills/cylinder/incRe100.log 2>&1
python <flowpro-postprocessing>/scripts/forces.py simulations/skills/cylinder/incRe100/bodiesDynamic.txt --from 70
```

Výsledek (ověřeno): 1200 kroků do t = 120, 48 min (12 vláken, ~2,4 s/krok), žádné selhání GMRES;
nasycené periodické odtrhávání od t ≈ 40.

| veličina | FlowPro (t ∈ [70, 120], 8 period) | literatura Re = 100 (neomezený proud) |
|---|---|---|
| Strouhalovo číslo | 0,170 | 0,164–0,166 |
| střední c_D | 1,36 | 1,33–1,35 |
| amplituda c_L | 0,326 | 0,32–0,33 |

Mírně vyšší St a c_D odpovídají zablokování kanálu D/H = 6 % (klouzavé boční stěny). Na rozdíl od stlačitelného
modelu (`flowpro-navier-stokes`) je rychlost před tělesem přesně `VIn` (rychlostní vstup).

**`tEnd` a velmi krátký krok**: krok oříznutý na `tEnd` se u `BDFIncompressible` (BDF2 s poměrem kroků ≪ 1)
projeví nesmyslným tlakem. FlowPro proto zbytek času pod 1e−6·dt (zaokrouhlení součtu kroků) nepočítá;
`tEnd` nastavuj jako násobek `dt`, ne o kousek větší.
