---
name: flowpro-postprocessing
description: Spouštění, sledování a vyhodnocení výpočtů FlowPro – běh na pozadí, residuum.txt, řízené zastavení, export výsledků (postprocessing do VTK/ParaView a textu), vzorkování podél přímky a v bodech, snímky animace, síly na tělesech z bodiesDynamic.txt (c_D, c_L, Strouhalovo číslo), grafy. Použij při vyhodnocování nebo kontrole libovolného výpočtu FlowPro; ostatní skilly flowpro-* na něj odkazují.
---

# Běh a vyhodnocení výpočtů FlowPro

Vše v adresáři `FlowPro` (`cd FlowPro`). Skripty jsou v `scripts/` tohoto skillu (Python 3 + numpy, scipy, matplotlib).

## Spuštění a sledování

```
java -jar FlowPro.jar check -g GEOM -s SIM                         # parametry + síť, nic nepočítá
java -jar FlowPro.jar run -g GEOM -s SIM > simulations/GEOM/SIM.log 2>&1   # na pozadí (run_in_background)
java -jar FlowPro.jar run -g GEOM -s SIM -p steps=20               # zkušební běh (odhad s/krok)
java -jar FlowPro.jar list | params | help
```
* Vždy nejdřív zkušební běh 10–20 kroků: čas na krok × počet kroků = doba výpočtu. Dlouhé výpočty pouštěj
  na pozadí a průběh čti z logu (`grep "resid:" SIM.log | tail -n 1`), neblokuj se čekáním.
* Řádek logu: `krok/steps resid: R dt: … t: … CFL: … CPU: … AT: sestavení ST: řešení soustavy`.
  `WARN … GMRES does not converge, time step reduced` = lineární řešič nedokonvergoval; při pevném `dt` se krok
  půlí a po opakovaných selháních výpočet skončí `ERROR … algorithm does not converge`.
* `python scripts/residuum.py simulations/GEOM/SIM [--png r.png]` – shrnutí `residuum.txt`
  (sloupce: relativní residuum = max přes rovnice, čas, CPU [ms], residua jednotlivých rovnic).
* Řízené zastavení: vytvoř soubor `simulations/GEOM/SIM/STOP` → dokončí krok, uloží, skončí.
  Pokračování: `continueComputation = true` (nebo `-p continueComputation=true`); `steps` je počet kroků dalšího
  běhu, `tEnd` absolutní čas. Před novým výpočtem od začátku smaž `W.txt We.txt state.txt residuum.txt`.
  **`bodiesDynamic.txt` se vymaže při každém startu výpočtu, i při pokračování** – před pokračováním ho
  zkopíruj (např. `bodiesDynamic_part1.txt`) a části pak spoj.
* **Pevný `dt` a `tEnd`**: čas se sčítá se zaokrouhlením (1200 × 0,1 = 119,99999999999746 < 120), poslední krok
  se pak ořízne na dt ≈ 1e−12 – v BDF2 s takovým poměrem kroků vznikne nesmyslný stav (u nestlačitelného modelu
  tlak ~1e4, který se uloží jako konečný). Délku běhu řiď `steps` a `tEnd` dej o půl kroku větší.
* Více výpočtů najednou: součet `threads` ≤ počet jader.

## Výstupy výpočtu (`simulations/GEOM/SIM/`)

`We.txt` (koeficienty DG řešení), `W.txt` (průměry v elementech), `state.txt` (krok, čas `t`, CFL, …),
`residuum.txt`, `animation/We########.txt` (+ `vertices########.txt` při pohyblivé síti) pro `animation = true`,
`bodiesDynamic.txt` pro pohyblivou síť (viz níže).

## Export výsledků

```
java -jar FlowPro.jar postprocessing mach pressure velocity -fvtk -g GEOM -s SIM          # ParaView: output/results.vtk
java -jar FlowPro.jar postprocessing density pressure velocity -ftxt -d2 -g GEOM -s SIM -oout_d2
java -jar FlowPro.jar postprocessing vorticity -ftxt -d2 -i00000400 -g GEOM -s SIM -osnap400  # snímek animace
```
* Názvy veličin = parametr `results` modelu (`mach, pressure, velocity, vorticity, temperature, density, mut, …`)
  a `W0, W1, …` (složky řešení).
* `-dN` = hodnoty v bodech elementů dělených na N dílů na hranu (polynom DG, vhodné pro p ≥ 1);
  bez `-d` průměry do vrcholů. `-iITER` = snímek z `animation/`, `-oDIR` = výstupní podadresář.
* **Pohyblivá síť**: bez `-i` umístí `postprocessing` (i GUI) konečné řešení na **nedeformovanou** síť
  `mesh/vertices.txt` – obraz je zkreslený (pootočený válec → „stočený“ úplav). Deformovaná síť je v `SIM/PXY.txt`.
  Buď použij snímky animace (`animation = true`, `-iITER` načte `animation/vertices########.txt`), nebo export
  `-ftxt -d1` (či bez `-d`) přemapuj: `python scripts/remap_deformed.py SIM/OUTDIR SIM GEOM/mesh`.
* `python scripts/sample.py OUTDIR q1 q2 --line X0 Y0 X1 Y1 N --csv line.csv` – hodnoty podél přímky
  (`velocity` → `xvelocity`, `yvelocity`); `--points X Y X Y …` – sondy.

## Síly na tělesech (`bodiesDynamic.txt`)

Zapisuje je dynamika `Rigid2D` (vyžaduje `movingMesh = true`; pro **nehybné těleso** stačí
`alphaMotion = 0` – trik pro průběh sil i na statické síti). Řádek: `t` a pro každé těleso
`x y alpha Fx Fy M` (M = moment k `rotationCenters`). Síly jsou na jednotku hloubky v jednotkách řešiče,
tj. vztažené k `pRef · lRef`.

* **Časování**: sloupec `t` je začátek kroku, poloha patří času `t + dt`; síla se počítá z řešení
  předposlední Newtonovy iterace kroku (`newtonIters = 1` → síla z času `t`, `≥ 2` → přibližně `t + dt`).
  Pro fázi a frekvenci na tom záleží – posuň čas o `dt` a používej `newtonIters ≥ 2`.
* `python scripts/forces.py simulations/GEOM/SIM/bodiesDynamic.txt --from T0 --L 1 [--png f.png]` +
  - nestlačitelné modely: `--q 0.5 --U 1` (výchozí; `pRef = ρ|VIn|²`, rychlost v jednotkách |VIn|),
  - stlačitelné modely: `--stagnation KAPPA PIN0 RHOIN0 POUT` (q a U nabíhajícího proudu z klidového stavu).
  Vypíše střední hodnotu a amplitudu c_x, c_y, c_m a frekvenci vztlaku (FFT a průchody nulou) a St = f L / U.
  Ověřeno na `NACA0012pitching/k175` (U∞ = 0,4556, frekvence = ω/2π = 0,2538).

## Bezrozměrné jednotky (shrnutí)

| modely | pRef | rhoRef | vRef | tRef |
|---|---|---|---|---|
| stlačitelné (`NavierStokes`, `SpalartAllmaras`, `KOmegaSST`, …) | `pIn0` | `rhoIn0` | √(pIn0/rhoIn0) | lRef/vRef |
| nestlačitelné (`IncompressibleNavierStokes*`) | ρ·\|VIn\|² | `density` | \|VIn\| | lRef/\|VIn\| |

`dt`, `tEnd`, `timeSaveRate` a výstupní čas jsou v `tRef`; skripty pohybu těles (`xMotion`, …) dostávají
rozměrný čas `t·tRef`. Pro jednoduchost volit referenční hodnoty 1 (`pIn0 = rhoIn0 = lRef = 1`, resp. `|VIn| = 1`).
