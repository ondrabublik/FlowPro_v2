---
name: flowpro-moving-boundary
description: Proudění s pohyblivou hranicí ve FlowPro (ALE, pohyblivá a deformující se síť) – předepsaný pohyb těles (posuv, kmitání, rotace) přes Rigid2D, volný pohyb pružně uloženého tělesa (matice M, B, K), ALE značky v síti, blending funkce, GCL, síly a polohy v bodiesDynamic.txt, ověření na válci kmitajícím v klidné tekutině proti Stokesovu řešení. Použij pro kmitající/rotující profily a válce, interakci tekutiny s tělesem (flutter, vírem buzené kmitání), pohyb stěn.
---

# Pohyblivá hranice – ALE (FlowPro)

Předpoklady: síť s ALE značkami (skill `flowpro-mesh`), model proudění (`flowpro-euler`, `flowpro-navier-stokes`,
`flowpro-incompressible`, `flowpro-turbulence`), vyhodnocení (`flowpro-postprocessing`).

## Princip

Těleso se pohybuje jako tuhé (posuv x, y, natočení α kolem `rotationCenters`), síť se deformuje: uzel se posune
o `b(x)·(pohyb tělesa)`, kde blending funkce b = 1 na tělese a 0 na pevné hranici (řešení Laplaceovy rovnice
na síti). Rovnice jsou v ALE tvaru s rychlostí sítě, `gcl = true` (výchozí) zajišťuje diskrétní zákon zachování
geometrie pro BDF (homogenní proud se na pohyblivé síti zachová přesně). Explicitní RK a `BDFIncompressible`
GCL nemají – pro pohyblivou síť používej `timeMethod = BDF` (u nestlačitelného modelu jen malé deformace).

## Síť

ALE značky úseček (`point x y TYP h ALE`, skill `flowpro-mesh`): **1 = pevná hranice** (vnější), **2 = těleso 1**,
3 = těleso 2, … (`boundaryTypeALE.txt`). Hranice se značkou 0 nejsou ani pevné ani tělesa – vnější hranici vždy
označ 1. FlowPro si v `mesh/` uloží `neighborsALE.txt` a `blendingFunctions.txt`; **po změně sítě nebo značek je smaž**.
Kolem tělesa nech dostatek elementů, deformace se rozloží mezi těleso a vnější hranici (velké natočení profilu
→ vnější hranice daleko, R ≥ 10 c).

## parameters.txt – předepsaný pohyb

```
movingMesh = true
dynamicsModel = Rigid2D          % modul dynamiky těles (ModuleProjects/Dynamics/RigidBody)
meshDeformationType = Rigid2D    % tuhý pohyb těles + blending funkce
rotationCenters = 0.25, 0        % x, y pro každé těleso (x1, y1, x2, y2, …)
alphaMotion = 10.0*Math.PI/180*Math.sin(1.5*t)    % úhel [rad], kladný = proti směru hodinových ručiček
%xMotion = …                     % posuv ve směru x (délka v jednotkách sítě)
%yMotion = 0.05*Math.sin(0.5*t)
timeMethod = BDF
timeOrder = 2
dt = …                           % pevný krok, 60–100 kroků na periodu pohybu
newtonIters = 3                  % síla v záznamu je z předposlední Newtonovy iterace
```

Výrazy pohybu (JavaScript, proměnná `t`):
* **`t` je rozměrný čas t·tRef** (stlačitelné modely tRef = lRef/√(pIn0/rhoIn0), nestlačitelné lRef/|VIn|) – s
  referenčními hodnotami 1 je totožný s časem řešiče.
* Výraz pro každé těleso, oddělené čárkou → **ve výrazu nesmí být čárka** (`Math.pow(a, b)`, `Math.max(a, b)`
  nepoužívat; psát `a*a`, ternární `c ? a : b`), **ani `%` a `#`** (začátek komentáře; místo modulo vlastní výraz).
* **Hladký rozjezd**: pohyb náhle rozjetého tělesa (skok rychlosti stěny) zhroutí výpočet ve 2. kroku (záporný tlak,
  nulový pivot ILU). Amplitudu náběhovat během první periody smoothstepem:
  `A*(t < T ? (t/T)*(t/T)*(3 - 2*t/T) : 1)*Math.sin(w*t)` (při startu z času t0 nahradit t → (t − t0)).
* Přepisy `-p` z příkazové řádky platí i pro parametry dynamiky (`xMotion`, `M`, `tKick`, …).

## Volný pohyb (interakce s tělesem)

```
M = m, 0, 0, 0, m, 0, 0, 0, I      % matice 3×3 po řádcích (x, y, α), v rozměrných jednotkách (přepočet pRef, lRef, tRef)
B = …                               % tlumení
K = 0, 0, 0, 0, ky, 0, 0, 0, ka     % tuhost
xFreedom = false                    % volné stupně volnosti pro každé těleso
yFreedom = true
alphaFreedom = true
tKick = 5                           % do t·tRef < tKick předepsaný pohyb (xMotion …), pak volný
method = 3                          % 1 Adams–Bashforth 2 (výchozí), 2 BDF1, 3 BDF2
zLength = 1                         % hloubka tělesa (síly × zLength)
%yExternForceInTime = …             % vnější síla jako funkce času
```
Bez `M`, `B`, `K` (hláška „Mass, stifness or damping matrix not defined!“) je pohyb čistě kinematický.
* Pohybové rovnice dostávají Fx, Fy a moment M k aktuálnímu středu rotace (`rotationCenters` + posunutí).
* Předepsaný pohyb do `tKick` dávej hladký, s nulovou rychlostí v `tKick` (rychlost při přechodu na volný pohyb
  je jen první diferencí polohy); souřadnice bez výrazu pohybu se počítají dynamicky i před `tKick`.
* Pokračování (`continueComputation = true`) načte stav těles (poloha, rychlost, historie metody) z
  `bodiesState.txt`, který `Rigid2D` přepisuje po každém kroku – pokračovat z výsledků uložených po `saveRate`
  krocích po násilném přerušení proto nejde (stav těles je novější; vypíše se varování), zastavuj souborem `STOP`.

## Výstupy

`bodiesDynamic.txt`: `t` + pro každé těleso `x y α Fx Fy M`. Poloha patří času `t + dt`, síla předposlední
Newtonově iteraci (detaily a skript `forces.py`: skill `flowpro-postprocessing`). Soubor se vymaže při novém
výpočtu, při pokračování (`continueComputation = true`) se připisuje. Konečná deformovaná síť je v `PXY.txt`
(rychlost sítě `UXY.txt`) a `postprocessing` na ní konečné řešení kreslí; `animation = true` ukládá i síť snímků
(`animation/vertices########.txt`, `postprocessing -iITER`).

## Ověření – válec kmitající v klidné tekutině (Stokes 1851)

Válec D = 1, y = A s(t) sin(ωt), A = 0,05, ω = 0,1, ν = 2e−4 (Stokesovo číslo β = D²/(νT) = 80), stlačitelný
`NavierStokes` v klidu (`pIn0 = pOut = 1`, `viscosity = 2e-4`, `conductivity = 3.889e-4`), kruhová oblast R₂ = 10
(−3, ALE 1), válec 128 úseček h = 0,025 (−1, ALE 2), 8 562 trojúhelníků, p = 2, BDF2, dt = T/64, 5 period,
hladký náběh amplitudy během první periody. Soubory: `examples/make_heave_geometry.py`, `examples/heaving_cylinder_stokes.txt`.
Přesné řešení: síla = −m_a·zrychlení − c·rychlost, m_a = πρR²·Re[1 + 4K₁(kR)/(kR K₀(kR))], k = √(iω/ν);
skript umí i korekci na konečnou oblast s pevným tlakem a stlačitelnost (`--Rout`, `--c`, Besselovy funkce).

```
python <tento skill>/examples/make_heave_geometry.py simulations/skills/heave/mesh
java -jar MeshGenerator2D.jar --batch simulations/skills/heave/mesh/geometry.geo2d simulations/skills/heave/mesh
java -jar FlowPro.jar run -g skills/heave -s w01 > simulations/skills/heave/w01.log 2>&1
python <tento skill>/scripts/stokes_cylinder.py simulations/skills/heave/w01/bodiesDynamic.txt --A 0.05 --omega 0.1 --nu 2e-4 --Rout 10 --c 1.1832 --from 125.7
```

Výsledek (ověřeno, periody 3–5, 37 min, 16 vláken, žádné selhání řešiče):

| | přesně | FlowPro | odchylka |
|---|---|---|---|
| poloha (sin / cos složka) | 0,05 / 0 | 0,05000 / −5e−5 | – |
| síla – setrvačná složka (přidaná hmota, c_m = 1,252) | 4,92e−4 | 4,94e−4 | 0,4 % |
| síla – tlumicí složka (c = 0,269) | 1,06e−4 | 1,08e−4 | 2,4 % |
| fáze síly | | | −0,24° |

**Stlačitelný model a akustika**: stejná úloha s ω = 0,5 (ν = 1e−3) dala setrvačnou složku o 14–16 % nižší –
vnější hranice s pevným tlakem odráží akustické vlny a kR₂ = ωR₂/c = 4,2 už není malé (stojaté vlny mezi válcem
a hranicí, přesná korekce −9 %, zbytek nevytlumené vlny z rozběhu). Pro „nestlačitelné“ děje stlačitelným modelem
drž ωR₂/c ≲ 1 (nižší frekvence nebo menší oblast) a ověř korekci skriptem (`--Rout`, `--c`).

## Ověření – volný pohyb: válec na torzní pružině v klidné tekutině

Síť jako výše, `NavierStokes` v klidu s ν = 0,01, I = 0,2, K = 0,05 (ω₀ = 0,5), do `tKick = 5` hladké natočení
na α = 0,1 s nulovou rychlostí, pak volné tlumené kmity; p = 1, BDF2, dt = 0,2, 300 kroků (4 min, 16 vláken).
Přesně: vlastní číslo s rovnice I s² + B s + K + Z(s) s = 0 s vazkým momentem rotujícího válce
Z(s) = 2πμR²(2 + z K₀(z)/K₁(z)), z = R√(s/ν). Parametry: `examples/rotating_cylinder_free.txt`.

```
java -jar FlowPro.jar run -g skills/heave -s rotfull > simulations/skills/heave/rotfull.log 2>&1
python <tento skill>/scripts/rotation_cylinder.py simulations/skills/heave/rotfull/bodiesDynamic.txt --I 0.2 --K 0.05 --nu 0.01 --tKick 5
```

| | přesně | FlowPro | odchylka |
|---|---|---|---|
| útlum Re s | −0,1103 | −0,1115 | 1,1 % |
| frekvence Im s | 0,3937 | 0,4022 | 2,2 % |
| reziduum I α'' + K α − M (diference ze záznamu) | 0 | 2 % max K α | – |

Výpočet rozdělený na dvě části (150 kroků, pak `-p continueComputation=true -p tEnd=60`) se od nepřerušeného
liší o 8e−5 v úhlu a 1 % v momentu.
