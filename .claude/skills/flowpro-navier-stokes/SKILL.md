---
name: flowpro-navier-stokes
description: Nestacionární proudění stlačitelné vazké tekutiny (Navierovy–Stokesovy rovnice) ve FlowPro – model NavierStokes s isFlowViscous = true, Reynoldsovo a Machovo číslo z klidových veličin, implicitní BDF2 s pevným krokem, rozběh z ustáleného stavu, síly a Strouhalovo číslo, ověření na obtékání válce při Re = 100, M = 0,2. Použij pro laminární stlačitelné nestacionární úlohy – obtékání profilů a válců, odtrhávání vírů, nestacionární kanály.
---

# Nestacionární stlačitelné Navierovy–Stokesovy rovnice (FlowPro)

Předpoklady: síť (skill `flowpro-mesh`), spouštění a vyhodnocení (skill `flowpro-postprocessing`).
Nevazké proudění: `flowpro-euler`; turbulence (RANS): `flowpro-turbulence`; pohyb těles: `flowpro-moving-boundary`.
Šablona: `Tools/FlowProControl/src/main/resources/flowpro/control/templates/navierstokes.txt`.

## Model a jednotky

* `model = NavierStokes`, `isFlowViscous = true`; W = (ρ, ρu, ρv, E); konstantní viskozita, `prandtl` (0,72).
* Referenční stav = klidový stav na vstupu: `pRef = pIn0`, `rhoRef = rhoIn0`, `vRef = √(pIn0/rhoIn0)`,
  `tRef = lRef/vRef`. S `pIn0 = rhoIn0 = lRef = 1` jsou časy v jednotkách lRef/√(p0/ρ0).
* **Machovo číslo** nabíhajícího proudu je dáno poměrem tlaků: `pIn0/pOut = (1 + (κ−1)/2·M²)^(κ/(κ−1))`
  (M = 0,2 → `pOut = 0.97250`; M = 0,39 → 0,9). Rychlost v jednotkách řešiče U∞ = M·√(κ p∞/ρ∞),
  p∞ = pOut/pIn0, ρ∞ = (pOut/pIn0)^(1/κ) (M = 0,2 → U∞ = 0,2357).
* **Reynoldsovo číslo**: `reynolds` je vztaženo k nabíhajícímu proudu (ρ∞, U∞) a délce `lRef`
  (model ho přepočte na referenční stav). Alternativa: `viscosity` + `conductivity` přímo v jednotkách
  řešiče (μ/(ρRef·vRef·lRef), k = cp·μ/Pr, cp = κ·cv) – nutné pro tekutinu v klidu (U∞ = 0).
* Okrajové podmínky: −1 stěna no-slip, adiabatická (na pohyblivé síti rychlost stěny + práce smykového napětí),
  −2 vstup (klidové veličiny, směr `attackAngle` v radiánech), −3 výstup (`pOut`), −4 klouzavá stěna/symetrie.
* Nízká Machova čísla: stlačitelný model je použitelný od M ≈ 0,1–0,2; pro menší rychlosti volit
  `flowpro-incompressible`.

## parameters.txt (ověřené nestacionární nastavení)

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
tEnd = 400.2         % o půl kroku víc než steps * dt (krok oříznutý na 1e-12 škodí)
saveRate = 100

spatialMethod = DGpure
timeMethod = BDF
timeOrder = 2
dt = 0.4             % ~65 kroků na periodu odtrhávání (T = D/(St U) = 26)
linearSolver = gmres
preconditioner = ilu0
order = 2
threads = 12
newtonIters = 3
newtonIterTol = 1e-3
penalty = 1
dampTol = 0          % bez rázů vypnout umělou viskozitu
dampConst = 0
```

* Pevný `dt` volit podle děje (50–100 kroků na periodu); akustické CFL vychází 40–50 a implicitní BDF2 to zvládá
  (`ilu0` stačí, na rozdíl od nestlačitelného modelu).
* `newtonIters = 2–3`: s jednou iterací je chyba nelinearity úměrná kroku a síly jsou z času t (ne t + dt).
* Rozběh: impulzní start z homogenního proudu (výchozí počáteční podmínka) funguje u nehybných těles.
  Pro drahé úlohy je levnější nejdřív ustálený výpočet (BDF1, `CFL = 0` adaptivní, `maxCFL = 200`,
  `residuum = 1e-6`), pak pokračování (`continueComputation = true`, `timeOrder = 2`, `dt`, `tEnd` absolutní).
  Rozběh pohybujícího se tělesa vždy hladce (skill `flowpro-moving-boundary`).
* Řád: `order = 2` (p = 1) pro první výpočty, `order = 3` (p = 2) pro přesné výsledky na hrubší síti
  (NACA0012 při Re = 1000: p = 2 na 17,5 tis. trojúhelnících).
* Odtrhávání vírů za symetrickým tělesem: rozbít symetrii (pootočení válce, viz `flowpro-incompressible`,
  nebo krátké nenulové `attackAngle` v první fázi a pokračování s 0).

## Síly

Stejně jako u nestlačitelného modelu přes `Rigid2D` (`movingMesh = true`, `alphaMotion = …`); vyhodnocení
`forces.py … --stagnation 1.4 1 1 0.97250` (q∞, U∞ se dopočítají z klidového stavu).

## Ověření – válec při Re = 100, M = 0,2

Síť `flowpro-mesh/scripts/example_cylinder.py` (11 382 trojúhelníků), parametry výše, pootočení válce
`alphaMotion = t < 20 ? 0.5*(1 - Math.cos(Math.PI*t/20)) : 1` (pomalé, U∞ = 0,24). Parametry:
`examples/cylinder_Re100_M02.txt`.

```
java -jar FlowPro.jar run -g skills/cylinder -s nsRe100 > simulations/skills/cylinder/nsRe100.log 2>&1
python <flowpro-postprocessing>/scripts/forces.py simulations/skills/cylinder/nsRe100/bodiesDynamic.txt --stagnation 1.4 1 1 0.9725 --from 250
```

Výsledek (ověřeno): 1000 kroků do t = 400 (~15 period), 36 min (12 vláken), žádné selhání řešiče;
periodické odtrhávání vírů od t ≈ 100.

| veličina | FlowPro, U∞ z `pOut` (0,2357) | FlowPro, změřená U∞ (0,2225) | literatura Re = 100 (nestlačitelně) |
|---|---|---|---|
| Strouhalovo číslo | 0,159 | 0,168 | 0,164–0,166 |
| střední c_D | 1,27 | 1,42 | 1,33–1,35 |
| amplituda c_L | 0,33 | 0,36 | 0,32–0,33 |

**Pozor na referenční rychlost v kanálu**: s klidovým vstupem (−2) a tlakem na výstupu (−3) má kanál s tělesem
tlakovou ztrátu, statický tlak před tělesem je vyšší než `pOut` a skutečná rychlost nižší (zde o 5,6 %, M = 0,189,
efektivně Re ≈ 94). Změř ji (`postprocessing velocity pressure density -ftxt -d1`, `sample.py --points -7 0`)
a zadej `forces.py --q <½ρU²> --U <U>` (zde ρ = 0,982, `--q 0.0243 --U 0.2225`). Se změřenou rychlostí St souhlasí; vyšší c_D (+5 % proti nestlačitelnému
výpočtu na stejné síti, viz `flowpro-incompressible`) odpovídá nižšímu Re, stlačitelnosti při M = 0,19
a zablokování kanálu 6 % (klouzavé stěny). Pro přesné hodnoty bez korekcí větší oblast nebo vzdálené pole
(kruhová vnější hranice: vstupní polovina −2, výstupní −3).
Válec zůstal pootočený o 1 rad, síť je tedy trvale zdeformovaná – pro obrázky pole použij
`flowpro-postprocessing/scripts/remap_deformed.py`. Lepší je pootočení „tam a zpět“
(`t < 20 ? 0.25*(1 - Math.cos(Math.PI*t/10)) : 0`), po kterém se síť vrátí do původního tvaru.
