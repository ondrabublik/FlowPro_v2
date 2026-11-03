---
name: flowpro-euler
description: Nestacionární proudění stlačitelné nevazké tekutiny (Eulerovy rovnice) ve FlowPro – nastavení parameters.txt (model NavierStokes bez viskozity nebo AdiabaticEulers), počáteční podmínka skriptem initScript.js, časová integrace RK3 / BDF2 s pevným krokem, tEnd, ukládání snímků, stabilizace rázových vln, ověření na Sodově rázové trubici. Použij pro nevazké nestacionární úlohy – rázové trubice, šíření rázových vln, akustické a nestacionární obtékání, přechodové děje v kanálech.
---

# Nestacionární Eulerovy rovnice (FlowPro)

Předpoklady: síť v `simulations/GEOMETRIE/mesh/` (skill `flowpro-mesh`), JARy sestavené (`gradlew build`).
Příkazy se spouštějí v adresáři `FlowPro`. Výsledky a jejich zpracování: skill `flowpro-postprocessing`.

## Volba modelu

| model | rovnice | poznámka |
|---|---|---|
| `NavierStokes` + `isFlowViscous = false` | úplné Eulerovy rovnice (ρ, ρu, ρv, E) | standard; stěna −1 i −4 je klouzavá |
| `AdiabaticEulers` | izentropické Eulerovy rovnice (bez energie) | jen bez rázů / se slabými rázy |

Šablona: `Tools/FlowProControl/src/main/resources/flowpro/control/templates/euler.txt` (ustálené proudění kanálem);
pro nestacionární úlohu ji uprav podle níže.

## Bezrozměrné veličiny – důležité

Model vše převádí na bezrozměrné veličiny: `pRef = pIn0`, `rhoRef = rhoIn0`, `vRef = √(pRef/rhoRef)`,
`tRef = lRef / vRef`. **`dt`, `tEnd`, `timeSaveRate` a čas v `residuum.txt` jsou v jednotkách `tRef`**, stav W
a hodnoty z `initScript.js` jsou bezrozměrné (ρ/ρRef, ρu/(ρRef vRef), E/pRef).
Nejjednodušší je zadávat `pIn0 = 1`, `rhoIn0 = 1`, `lRef = 1` – pak jsou zadané a výpočtové hodnoty totožné.
Model vyžaduje vstupní/výstupní parametry (`isInletSupersonic`, `pIn0`, `rhoIn0`, `pOut`) i když síť
nemá vstup ani výstup – určují referenční stav. Úhel `attackAngle` je v radiánech.

## Nestacionární parameters.txt (jádro)

```
model = NavierStokes
results = mach, pressure, velocity, density
dimension = 2
isFlowViscous = false
cv = 1
kappa = 1.4
isInletSupersonic = false
pIn0 = 1
rhoIn0 = 1
pOut = 0.9            % podzvukový výstup: pIn0/pOut určuje Machovo číslo

continueComputation = false
steps = 100000        % horní mez, výpočet ukončí tEnd
tEnd = 0.2            % konečný čas (v tRef)
timeSaveRate = 0.01   % ukládání po čase (nebo saveRate = N kroků)
animation = true      % každé uložení i do animation/We########.txt (+ vertices pro pohyblivou síť)

spatialMethod = DGpure
order = 2             % p + 1; p = 1 je robustní, p = 2 pro hladká pole
dampTol = 3           % detektor rázů + umělá viskozita (0 = vypnuto, pro hladké úlohy)
dampConst = 0
penalty = 1
threads = 8
```

Časová integrace – vyber jednu variantu:

* **Explicitní RK3 (rázy, krátké děje)**: `timeMethod = RK`, `timeOrder = 3`, `CFL = 0.3`,
  `linearSolver = gmres`, `preconditioner = none`, `newtonIters = 1`. CFL je už vztažené k řádu
  (krok obsahuje 1/(2p+1)), 0,3 funguje pro p = 1 i 2. Krok je globální (časově přesný).
* **Implicitní BDF2 s pevným krokem (dlouhé děje, nízké Machovo číslo)**: `timeMethod = BDF`,
  `timeOrder = 2`, `dt = ...` (místo `CFL`; obojí najednou = použije se CFL), `linearSolver = gmres`,
  `preconditioner = ilu0`, `newtonIters = 3–5`, `newtonIterTol = 1e-4`. První krok je automaticky BDF1.
  Krok volit podle děje, ne podle stability (např. 50–200 kroků na periodu).
* **Nepoužívat pro časově přesné výpočty**: adaptivní CFL (`CFL <= 0`) – mění krok podle residua;
  `newtonIters = 1` s BDF při velkém kroku – nelineární chyba v každém kroku.

Počáteční podmínka:
* `initScript.js` v adresáři výpočtu – `function init(x) { return [rho, rho*u, rho*v, E]; }`,
  `E = p/(kappa-1) + 0.5*rho*(u*u + v*v)`; vyhodnotí se v těžišti elementu (po elementech konstantní).
* `initW.txt` – hodnoty pro každý element (řádek = element).
* bez obou: konstantní stav modelu (klid / nabíhající proud podle okrajových podmínek).
* ustálený stav jako počátek: nejprve ustálený výpočet (BDF1, `CFL = 0` adaptivní nebo `CFL = 5–50`,
  `residuum = 1e-6`), pak ve stejném adresáři `continueComputation = true`, `dt`, `timeOrder = 2`, `tEnd`
  (čas pokračuje; `tEnd` je absolutní čas, ne délka běhu – aktuální čas je `t=` ve `state.txt`).

## Spuštění

```
java -jar FlowPro.jar check -g GEOM -s SIM                 # kontrola parametrů a sítě
java -jar FlowPro.jar run -g GEOM -s SIM > SIM.log 2>&1     # dlouhý výpočet spouštěj na pozadí
java -jar FlowPro.jar run -g GEOM -s SIM -p dt=5e-4 -p tEnd=0.1   # dočasné přepsání parametrů
```
Zastavení s uložením: soubor `STOP` v adresáři výpočtu. Řádek výpisu
`N/steps resid: … dt: … t: … CFL: …`; `residuum.txt`: sloupce relativní residuum, čas, CPU [ms], residua rovnic.
U nestacionárních úloh residuum neklesá k nule – sleduj čas `t` a fyzikální veličiny.

## Ověření (vzorová úloha – Sodova trubice)

Síť 200 × 4 čtyřúhelníky na [0, 1] × [0, 0.02], všechny hranice −4 (`block 0 1 2 3 200 4 1 1 -4 -4 -4 -4 0 0 0 0`),
`initScript.js`: vlevo (x < 0.5) ρ = 1, p = 1, vpravo ρ = 0.125, p = 0.1, `tEnd = 0.2`, p = 1, `dampTol = 3`.
Soubory jsou v `examples/sod/` tohoto skillu (`geometry.geo2d`, `initScript.js`, `parameters_rk3.txt`, `parameters_bdf2.txt`):

```
mkdir -p simulations/skills/sod/mesh simulations/skills/sod/rk3
cp <skill>/examples/sod/geometry.geo2d simulations/skills/sod/mesh/
cp <skill>/examples/sod/initScript.js simulations/skills/sod/rk3/
cp <skill>/examples/sod/parameters_rk3.txt simulations/skills/sod/rk3/parameters.txt
java -jar MeshGenerator2D.jar --batch simulations/skills/sod/mesh/geometry.geo2d simulations/skills/sod/mesh
java -jar FlowPro.jar run -g skills/sod -s rk3
java -jar FlowPro.jar postprocessing density pressure velocity -ftxt -d2 -g skills/sod -s rk3
python <flowpro-postprocessing>/scripts/sample.py simulations/skills/sod/rk3/output density pressure velocity --line 0 0.01 1 0.01 400 --csv line.csv
python <tento skill>/scripts/riemann_exact.py --left 1 0 1 --right 0.125 0 0.1 --t 0.2 --compare line.csv
```
Výsledek (ověřeno, střední |ρ − ρ_exact| podél osy trubice):

| varianta | kroků | čas | L1 chyba ρ | L1 chyba p |
|---|---|---|---|---|
| RK3, p = 1, CFL 0,3 | 886 | 3 s | 2,5e−3 | 2,0e−3 |
| BDF2, p = 1, dt = 1e−3, 4 Newtonovy iterace | 200 | 31 s | 2,4e−3 | 1,8e−3 |
| RK3, p = 2, CFL 0,3 | 1464 | 65 s (2 vlákna) | 1,3e−3 | 1,1e−3 |

Poloha rázu, kontaktní nespojitosti i zředění souhlasí s přesným řešením, překmit na rázu < 10 % (rychlost). Skript `riemann_exact.py` řeší libovolný Riemannův problém (stavy ρ, u, p).

## Časté problémy

* `NaN` / záporný tlak na začátku: silná nespojitost v počáteční podmínce bez `dampTol` → zapnout `dampTol = 3`,
  snížit CFL, `order = 2`. Limiter pozitivity (`positivityLimiter = true`) je zapnutý ve výchozím stavu.
* Oscilace za rázem při p ≥ 2: zvýšit `dampConst` (např. 0.1) nebo zjemnit síť.
* Odraz vln od výstupu: podzvukový výstup −3 drží `pOut`, akustické vlny se částečně odrážejí; výstup dál od oblasti zájmu.
* Výsledek nesedí s očekávaným Machovým číslem: podzvukové vstupy jsou dány poměrem `pIn0/pOut`
  (M = √(2/(κ−1)·((pIn0/pOut)^((κ−1)/κ) − 1))), ne rychlostí.
