---
name: flowpro-mesh
description: Generování 2D výpočetní sítě pro FlowPro (MeshGenerator2D, formát .geo2d, dávkový režim, nestrukturované trojúhelníkové i blokové strukturované sítě, okrajové podmínky, ALE značky těles, kontrola kvality). Použij, když uživatel chce vytvořit, upravit nebo zkontrolovat síť (mesh) pro výpočet ve FlowPro – kanál, profil NACA, válec, deska, oblast s tělesem.
---

# Generování výpočetní sítě (FlowPro)

Síť je v adresáři `FlowPro/simulations/GEOMETRIE/mesh/`, výpočty jsou sourozenecké adresáře
(`simulations/GEOMETRIE/VYPOCET/parameters.txt`). Všechny příkazy níže se spouštějí v adresáři `FlowPro`.
JARy vzniknou `gradlew build` v kořeni balíku (pokud `FlowPro/MeshGenerator2D.jar` chybí).

## Postup

1. **Geometrii napiš skriptem** – `scripts/geo2d.py` v tomto skillu (třída `Geometry`, funkce
   `rectangle`, `circle`, `naca4`, `polyline`). Skript ulož vedle sítě (např. `simulations/NAZEV/make_geometry.py`),
   aby šla síť zopakovat. Ruční psaní `.geo2d` jen pro drobné úpravy.
2. **Vygeneruj síť dávkově**:
   ```
   java -jar MeshGenerator2D.jar --batch simulations/NAZEV/mesh/geometry.geo2d simulations/NAZEV/mesh
   ```
   Vypíše počet elementů a kvalitu (min. úhel, „poor elements“). Návratový kód ≠ 0 = chyba geometrie.
3. **Zkontroluj**: `python <skill>/scripts/mesh_info.py simulations/NAZEV/mesh` – počty stěn a délky
   po typech okrajových podmínek (ověř, že každá hranice má zamýšlený typ), ALE značky, min. úhel, velikosti.
4. **Ověř ve FlowPro** po vytvoření `parameters.txt` (viz skilly modelů):
   `java -jar FlowPro.jar check -g NAZEV -s VYPOCET` (načte síť, nic nepočítá).

GUI alternativa: `FlowProControl.bat` → záložka *Mesh* (úlohy v `simulations/GUI/NAZEV`), samostatně
`MeshGenerator2D.bat`. Formát souboru je stejný; GUI je vhodné na prohlédnutí výsledku.

## Formát `.geo2d`

```
meshSize 1.0          # globální (maximální) velikost buňky
minAngle 28           # Ruppertovo zjemnění, rozumně 25–30 (víc nemusí skončit)
grading 0.25          # růst velikosti buňky se vzdáleností od zahuštění (menší = pozvolnější)
smoothing 5           # Laplaceovo vyhlazení
refinement 0          # každá úroveň rozdělí trojúhelník na 4 (jen nestrukturovaná síť)
blockTriangles 0      # bloky rozdělit na trojúhelníky (1) místo čtyřúhelníků (0)
region box x0 y0 x1 y1 h      # max. velikost h v obdélníku (úplav, okolí tělesa)
region circle cx cy r h
loop                  # uzavřená smyčka; smyčka s největší plochou = vnější hranice, ostatní = díry/tělesa
point x y TYP h ALE   # atributy patří úsečce z tohoto bodu do dalšího; h = NaN → globální velikost
end
```

Strukturované bloky (jako blockMesh): `vertex x y`, `block v0 v1 v2 v3 nx ny gx gy t0 t1 t2 t3 a0 a1 a2 a3`
(vrcholy proti směru hodinových ručiček, stěna k jde z v[k] do v[k+1], stěny 0/2 mají nx buněk, 1/3 ny buněk,
`gx`/`gy` = poměr poslední/první buňky ve směru v0→v1 / v1→v2), `arc a b x y` (hrana jako oblouk přes bod).
Sdílená stěna dvou bloků musí mít stejné dělení (`ny`, `gy`); její typ se ignoruje (dej 0).
**Geometrie s bloky se meshuje jen blokově – smyčky (`loop`) se pak ignorují, `refinement` neplatí.**

## Okrajové podmínky (typ úsečky)

| typ | stlačitelné modely (`NavierStokes`, `SpalartAllmaras`, `KOmegaSST`, …) | nestlačitelné (`IncompressibleNavierStokes*`) |
|---|---|---|
| −1 | stěna (vazká: no-slip, adiabatická; nevazká: klouzání) | stěna no-slip |
| −2 | vstup (klidové veličiny `pIn0`, `rhoIn0`/`TIn0`; směr `attackAngle` v radiánech, bez něj kolmo ke hranici) | vstup, rychlost `VIn` |
| −3 | výstup (statický tlak `pOut`, nadzvukový výstup bez podmínky) | výstup, tlak `pOut` |
| −4 | nevazká (klouzavá) stěna, symetrie | klouzavá stěna, symetrie |
| −5 | volný vstup/výstup – jen model `NavierStokesR` | – |

Hranice se zapisují do `boundaryType.txt` (`typ i j` pro každou hraniční hranu).

## ALE značky (pohyblivá síť)

Třetí atribut bodu / poslední čtveřice bloku. `0` = bez značky, `1` = **pevná** hranice (vnější),
`2` = těleso 1, `3` = těleso 2, … (počet těles = max. značka − 1). Zapíše se do `boundaryTypeALE.txt`.
Pro statické výpočty značky nevadí, takže je rovnou dávej (vnější hranice 1, těleso 2) – síť pak
poslouží i pro pohyblivou hranici (skill `flowpro-moving-boundary`).

## Doporučení pro velikosti

* Těleso: na obvod válce/profilu ≥ 80–100 úseček (`circle(..., n=96, h=0.035)` pro D = 1),
  profil `naca4(n=80–90)` s `h ≈ 0.01·c`, zahuštění náběžné a odtokové hrany `region_circle`.
* Úplav: `region_box` za tělesem s h ≈ 0.1–0.15 D (pro odtrhávání vírů), alespoň 10 D za tělesem.
* Vzdálenost hranic: vstup ≥ 8 D před tělesem, výstup ≥ 15–20 D za ním, boční hranice ≥ 8 D
  (zablokování kanálu D/H ≤ 6 %), kruhové vzdálené pole R ≈ 12–20 c.
* DG vyššího řádu (p = 2) potřebuje méně elementů: hrubší síť + `order = 3` je často levnější než jemná síť a p = 1.
* Turbulentní (RANS) mezní vrstva: blokovou síť s první buňkou y⁺ ≈ 1 (p = 1) až 5 (p = 2);
  y₁ ≈ y⁺ / (Re · √(c_f/2)), c_f ≈ 0,0576 Re_x^(−1/5). `gy` se dopočítá bisekcí (příklad
  `scripts/example_plate_blocks.py`).
* Měřítko: síť je v jednotkách délky, výpočet ji násobí `meshScale / lRef` (výchozí 1). Obvykle síť kresli
  s referenční délkou 1 (průměr, tětiva).

## Odvozené soubory

FlowPro si v `mesh/` ukládá `wallDistance.txt` (turbulentní modely), `neighborsALE.txt` a `blendingFunctions.txt`
(pohyblivá síť). Generátor je při exportu smaže; **když síť měníš jinak (ruční úprava, kopie), smaž je**,
jinak se použijí staré hodnoty. Výsledky výpočtů ze staré sítě (`W.txt`, `We.txt`, `state.txt`) jsou neplatné –
nový výpočet s `continueComputation = false`.

## Ověření

Sítě vytvořené tímto postupem (dávkový režim + `mesh_info.py` + `FlowPro.jar check`) a použité v ověřovacích
výpočtech ostatních skillů:

| síť | generátor | výsledek | použití |
|---|---|---|---|
| válec v kanálu (`example_cylinder.py`) | Ruppert | 11 382 trojúhelníků, min. úhel 29,0°, 0,5 s | Re = 100 nestlačitelné i stlačitelné |
| válec v kruhu R = 10 (128 úseček, h = 0,025) | Ruppert | 8 562 trojúhelníků, min. úhel 28,3° | kmitající válec (ALE) |
| deska (`example_plate_blocks.py`) | 2 bloky | 2 480 čtyřúhelníků, první buňka 5e−5 (y⁺ ≈ 2) | turbulentní deska |
| rázová trubice 200 × 4 | 1 blok | 800 čtyřúhelníků | Sodova úloha |

## Příklady

* `scripts/example_cylinder.py` – válec D = 1 v kanálu 30 × 16 (vstup −2, výstup −3, boky −4,
  válec −1 s ALE 2, vnější ALE 1), zahuštění úplavu; ≈ 11 400 trojúhelníků, min. úhel 29°.
* `scripts/example_plate_blocks.py` – deska délky 2 s náběhovou oblastí, 2 bloky, první buňka 5e−5.
* `simulations/NACA0012pitching/scripts/make_geometry.py` – NACA0012 v kruhovém poli R = 12.
* `simulations/examples/GAMMgenerated/mesh/geometry.geo2d` – kanál GAMM.
