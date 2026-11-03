---
name: flowpro-turbulence
description: Turbulentní proudění ve FlowPro (RANS a URANS) – modely Spalart–Allmaras a k-ω SST pro stlačitelné proudění, Spalart–Allmaras pro nestlačitelné, síť u stěny (y+), vzdálenost od stěny, turbulentní okrajové podmínky na vstupu, ustálený i nestacionární výpočet, ověření na turbulentní desce (součinitel tření). Použij pro proudění s vysokým Reynoldsovým číslem – obtékání profilů, desky, kanály, úplavy.
---

# Turbulentní proudění – RANS / URANS (FlowPro)

Předpoklady: síť zahuštěná ke stěnám (skill `flowpro-mesh`, bloková síť), základní nastavení modelu proudění
(`flowpro-navier-stokes`, `flowpro-incompressible`), vyhodnocení (`flowpro-postprocessing`).
Šablony: `Tools/FlowProControl/src/main/resources/flowpro/control/templates/rans_sa.txt`, `rans_sst.txt`,
`incompressible_sa.txt` (konstanty modelů – kopíruj je odtud).

## Volba modelu

| model | proudění | neznámé | poznámka |
|---|---|---|---|
| `SpalartAllmaras` | stlačitelné | ρ, ρu, ρv, E, ρν̃ | nejrobustnější, doporučený start |
| `KOmegaSST` | stlačitelné | ρ, ρu, ρv, E, ρk, ρω | Menter SST, Sutherlandova viskozita (`SuthConst`) |
| `KOmega`, `KOmegaSutherland` | stlačitelné | | starší varianty |
| `SpalartAllmarasTrans*` | stlačitelné | | s modelem přechodu (experimentální) |
| `IncompressibleNavierStokesSpalartAllmaras` | nestlačitelné | p, u, v, ν̃ | „negative SA“ (Allmaras 2012), ν̃ může být záporné |

Parametry proudu (`reynolds`, `pIn0`, `pOut`, … resp. `VIn`, `viscosity`) jako u laminárních modelů,
`isFlowViscous` se nezadává. Výstupní veličiny: `mut` (SA), `k, omega, turbulentviscosity` (SST).

## Turbulentní parametry

* SA: `vtIn` = ν̃/ν na vstupu (3 = plně turbulentní volný proud, 0,1 – laminární nabíhající proud),
  konstanty `sigma cb1 cb2 ka cw2 cw3 cv1 ct1–ct4 Prt C_prod` ze šablony (povinné).
* SST: `kIn` = 1,5 (I·U∞)² a `omIn` = kIn/ν_t v jednotkách řešiče (klidový stav, lRef); konstanty
  `a1 sk1 som1 alpha1 beta1 betast sk2 som2 alpha2 beta2 Prt SuthConst` ze šablony.
* Vzdálenost od stěny se spočítá automaticky ze stěn −1 při prvním startu a uloží do `mesh/wallDistance.txt`
  – **po změně sítě (nebo typů stěn) soubor smaž**. Stěna −4 (klouzavá) se jako stěna nepočítá.

## Síť u stěny

* První buňka: y⁺ ≈ 1 pro p = 1, y⁺ ≲ 5 pro p = 2 (polynom uvnitř elementu rozliší vazkou podvrstvu).
  y₁ = y⁺ · L / (Re_L · √(c_f/2)), odhad c_f ≈ 0,0576·Re_x^(−1/5).
* Bloková síť s geometrickým zhuštěním (`gy` = poměr poslední/první buňky, 30–60 buněk napříč mezní vrstvou
  a oblastí), viz `flowpro-mesh/scripts/example_plate_blocks.py`; kolem profilu C-/O-bloky s obloukem `arc`.
  Nestrukturovaná síť (Ruppert) neumí anizotropní buňky – pro RANS jen jako vnější část nebo pro hrubé odhady.

## Řešič

Ustálený RANS:
```
timeMethod = BDF
timeOrder = 1
CFL = 0              % adaptivní (SER), nebo pevné CFL = 5–50 jako v šablonách
maxCFL = 1000
residuum = 1e-6
linearSolver = gmres
preconditioner = ilu0          % nestlačitelný SA: blockilu0 + iterativeSolverTol = 0.1
newtonIters = 1
order = 2
dampTol = 0
```
URANS (nestacionární): `timeOrder = 2`, pevné `dt`, `newtonIters = 3`, `newtonIterTol = 1e-3`; start z ustáleného RANS
(`continueComputation = true`). Čas a jednotky jako v `flowpro-navier-stokes` / `flowpro-incompressible`.

* Krok u stěny je malý (buňky 1e−5–1e−4), časová přesnost ustáleného výpočtu nehraje roli – adaptivní CFL.
  Residuum turbulentní rovnice (poslední sloupec `residuum.txt`) bývá o 1–2 řády nad ostatními a určuje
  rychlost růstu CFL; sleduj i fyzikální veličiny (c_f, síly), ne jen residuum.
* Záporné ν̃ v úplavu na hrubé síti (nestlačitelný SA) je přípustné (negative SA), řešením je jemnější síť.

## Ověření – turbulentní deska, Re = 10⁶/m, M = 0,2 (SA)

Síť `flowpro-mesh/scripts/example_plate_blocks.py` (deska x ∈ [0, 2], náběh x ∈ [−0,5; 0] symetrie −4, horní
hranice −4, 2 bloky 12+50 × 40 čtyřúhelníků, první buňka 5e−5 → y⁺ ≈ 2), model `SpalartAllmaras`, `vtIn = 3`.
Součinitel tření z gradientu rychlosti u stěny: c_f = 2/(Re·U∞) · ∂u/∂y|_w (jednotky řešiče, lRef = 1),
skript `scripts/plate_cf.py`. Parametry: `examples/plate_sa.txt`.

```
java -jar FlowPro.jar run -g skills/plate -s sa
java -jar FlowPro.jar postprocessing velocity mut -ftxt -d1 -g skills/plate -s sa -oout_d1
python <tento skill>/scripts/plate_cf.py simulations/skills/plate/sa/out_d1 --re 1e6 --U 0.2357 --xmax 2 --png cf.png
```

Výsledek (ověřeno): ustálený RANS, BDF1, adaptivní CFL (`CFL = 0`, `maxCFL = 1000`), residuum 1e−6 za 1411 kroků
(15 min, 6 vláken). Prvních ~900 kroků drželo residuum SA rovnice CFL kolem 100–130 (krok 7e−3), pak CFL
vystoupalo na 1000 a výpočet rychle dokonvergoval – stagnace residua kolem 1e−3 na začátku tedy není důvod zasahovat.

| x | Re_x | c_f FlowPro | 0,0576 Re_x^−0,2 | White | odchylka od White |
|---|---|---|---|---|---|
| 0,26 | 2,6e5 | 0,00417 | 0,00477 | 0,00489 | −15 % |
| 0,48 | 4,8e5 | 0,00388 | 0,00420 | 0,00431 | −10 % |
| 0,97 | 9,7e5 | 0,00351 | 0,00365 | 0,00378 | −7 % |
| 1,55 | 1,5e6 | 0,00326 | 0,00333 | 0,00348 | −6 % |
| 1,88 | 1,9e6 | 0,00316 | 0,00320 | 0,00336 | −6 % |

y⁺ první buňky ≈ 2,1. Proudění je plně turbulentní (laminární Blasius by dal 5× menší c_f); od x ≈ 1 je odchylka
od mocninové korelace 1–4 %. Větší odchylka u náběžné hrany: korelace předpokládají turbulenci už od náběžné
hrany a síť je tam ve směru x hrubá (50 buněk, `gx = 20`).
