# FlowProPackage v2 – přehled změn

Kopie `FlowProPackage` s opravenými chybami z analýzy kódu, novým způsobem spouštění úloh
a dvěma novými GUI nástroji. Původní adresář `FlowProPackage` zůstal beze změny.

Do v2 **nebyly zkopírovány uživatelské simulace** (`FlowPro/simulations`, 143 adresářů s výsledky),
jen `examples/GAMM`. Nově přibyl příklad `examples/GAMMgenerated` se sítí z nového generátoru.
Vlastní simulace stačí zkopírovat do `FlowPro/simulations`.

Sestavení: `gradlew build` (Java 17+, testováno s Javou 21). Výsledné JARy jsou v `FlowPro/`:
`FlowPro.jar`, `MeshGenerator2D.jar`, `FlowProControl.jar`.

---

## 1. Opravené chyby

### Kritické

| # | Kde | Chyba | Oprava |
|---|-----|-------|--------|
| K1 | `solver/LocalExplicitSolver` | Smyčka stupňů RK běžela `0..n-1`, stupně jsou `1..n` – poslední stupeň (zápis výsledku) se nikdy neprovedl. RK2 dělal jen půl kroku, RK3 skončil na mezistupni, RK4 byl 1. řádu. | Smyčka `1..n`. Ověřeno testem řádu: RK3 je 3. řádu (viz kap. 6). |
| K2 | `element/RK2..4`, `LocalExplicitSolver` | Souběh vláken: limiter (zápis `eps`) běžel paralelně s výpočtem residua sousedů, kteří `eps` čtou. | Limiter běží v samostatné paralelní fázi před každým stupněm s residuem. |
| K3 | `FlowProMain.generateMeshPartition` | Prohozené min/max – dělení oblasti nebylo podél nejdelšího rozměru, poslední doména byla malá. | Opraveno, domény mají stejnou velikost (±1 element). |
| K4 | `FlowProMain.generateBoundaryDistanceFunction`, `Deformation` | Vzdálenost od hranice byla všude 0 (brala všechny stěny), váhování deformace sítě nefungovalo. `eTyp[elemsType[i]]` padal pro typy 31, 33, 42, 61, 63. | Jen hraniční stěny (`TT < 0`), společná funkce pro vzdálenost od stěny/hranice. Typy elementů z továrny. |
| K5 | `LocalImplicitSolver`, `State` | Při pevném `dt` a nekonvergenci GMRES nekonečná smyčka. | Při pevném `dt` se krok půlí (a pak zase obnovuje), po 1024× zmenšení řízené ukončení. Při ukončení s pohyblivou sítí se vrací poloha sítě. |
| K6 | `State` | Adaptivní CFL (`CFL <= 0`) v lokálním implicitním řešiči nefungovalo (`varyCFL` se nenastavovalo). | `State` používá společnou třídu `CFLSetup`. |
| K7 | `ILU0`, `ParallelIlu0` | Chyběla kontrola konce řádku – úprava sousedního řádku / výjimka. | Doplněno `l < IA[k+1]`. |
| K8 | `Parameters`, `Mesh` | Lokální řád jen z `order.txt` → řád kvadratury −1 → pád. Kvadratura byla globální. | Řád kvadratury se odvozuje z řádu elementu (pokud není zadán explicitně). |

### Další opravy

* **GMRES** – kritérium konvergence porovnávalo předpodmíněné residuum s nepředpodmíněnou pravou stranou; nyní se normuje předpodmíněnou pravou stranou. Ošetřen „happy breakdown“ (dělení nulou → NaN). *Pozor: `iterativeSolverTol` má teď konzistentní, ale jiný význam než dřív.*
* **Selhání předpodmínění** (nulový pivot ILU) nebo NaN v řešení už výpočet neshodí – bere se jako nekonvergence a sníží se CFL.
* **Explicitní řešič** ignoroval pevný krok `dt` (krok 0 → NaN); nyní používá stejnou logiku jako implicitní.
* **Paralelní řešiče** (KSP, Schwarz): místo `System.exit(1)` řízené ukončení s uložením; pevné `dt` hlásí jasnou chybu (nepodporováno).
* **Barthův limiter** mohl zesílit gradient až 2× (správně max. 1); meze ±1e7 nahrazeny ±∞; element bez vnitřních sousedů → 0. `FVMlimiter = none` je teď explicitní.
* **Zdrojový člen FVM** se násobil vahou jen prvního kvadraturního bodu, nyní plochou elementu.
* **Periodické stěny** – souřadnice kvadraturních bodů se posouvaly přímo v poli elementu (chybná rekonstrukce FVM); nyní se posouvá kopie.
* `Integration.initNeighbours` – hraniční stěna si nesla souseda z předchozí stěny.
* `Domain` – pole `save`/`save1` byla přealokovaná a doplněná nulami (element 0 chybně označen).
* **Periodický posun** se škáluje stejně jako síť (`meshScale/lRef`, `scaleDims`).
* **DDG `beta0`** (metoda `DG`) se trvale přepisoval podle globálního řádu; nyní podle řádu elementů na stěně – skokový člen se vynechá jen na stěně mezi dvěma elementy řádu 1 (resp. na hranici elementu řádu 1). Hodnota je tak na obou stranách stěny stejná a tok je konzervativní (první verze opravy brala jen řád vlastního elementu). Varianty `DGpure*` beze změny: tam se používá maximální řád sítě, což je na stěně symetrické.
* **`blockilu0`** nefungoval vůbec: bloky se kopírovaly jen jednou před sestavením matice (rozkládaly se nuly), výsledek rozkladu se zahazoval (přiřazení do lokální proměnné), vynechával se předchozí element a bloky se braly netransponované. Přepsáno; na GAMM dává stejný výsledek jako `ilu0` (rozdíl 7e−10).
* **Adaptivní CFL** (`CFL <= 0`): počáteční residuum `Double.MAX_VALUE` prošlo „filtrem“ a ~300 kroků zvětšovalo CFL 1,3× v každém kroku bez ohledu na konvergenci (GAMM: CFL 5e22), potom CFL při pomalé konvergenci klesalo 0,8× za krok. Nyní SER: `CFL_n = CFL_{n-1}·res_{n-1}/res_n`, změna max. 2× za krok, rozsah `[1, maxCFL]` (nový parametr, výchozí 1e4); po selhání řešiče se v dalším kroku CFL nezvětšuje.
* **Start BDF2:** první krok počítal s `Wo2 = Wo`, tj. `(3/2)(W−Wo)/dt = R` – chyba O(dt) v prvním kroku a celkově jen 1. řád. Nyní je první krok výpočtu (i po `continueComputation`) BDF1, stejně i rychlost sítě. Ověřeno na GAMM (pevné `dt`, t = 0,1, Newton do 1e−11): poměr rozdílů při půlení `dt` 3,88 / 3,99 / 4,00 (dříve ≈ 2,1). Paralelní řešiče posílají slave procesům `{dt, dto}` (dříve si `dto` odvozovaly samy a ve druhé Newtonově iteraci bylo `dto = dt`) a opraveno prohození `dt`/`dto` při výpočtu rychlosti sítě ve slave procesech.
* `shock_senzor2` sčítal hodnotu souseda do `rhoL` místo `rhoR`.
* **Počáteční podmínka ze skriptu `initScript.js`** v původní verzi s GraalJS nefungovala (JS dostal nepřístupné Java pole → NaN, vrácené pole nešlo převést). Nyní funkce `init(x)` dostane JS pole a může vrátit pole i řetězec. Chybějící `init` nebo chyba ve skriptu = jasná chyba (dřív `NullPointerException` nebo tichý přechod na konstantní stav).
* `LocalImplicitSolver`: `solveTime = +…` → `+=`; ukládání podle `timeSaveRate` po pokračování výpočtu neukládalo v každém kroku.
* `MasterSolver`: porovnání `Integer` přes `==` → `Integer.compare`.
* `Preconditioner.factory`: neznámá hodnota → nápověda místo pádu; nezáleží na velikosti písmen.
* **Neuzavřené soubory** (`State.save`, `Parameters`, továrny modulů, `Mat.load*`, `FlowProMain`) – na Windows držely zámky souborů.
* `FlowProProperties.getBoolean`: překlep (`ture`) už není tiše `false`, ale chyba.
* `Mat`: `max()` pro záporná pole, `gaussian` (pivot pro každý sloupec), `jacobi` (testoval normu řešení místo změny), načítání polí (více čísel na řádku, více mezer, prázdné řádky).
* Návratový kód aplikace: chyba → `1`, špatné argumenty → `2` (dřív `0`).
* Chybové hlášky (`unknown spatial method` u časové metody, nápověda `parallelSolverType`).

### Výkon

* Sdílený pool vláken `parallel/ParallelLoop` místo vytváření nových vláken při každém násobení maticí,
  aplikaci předpodmínění a sestavení (tisíce vláken za časový krok).
* Odstraněny 4 kopie třídy `CFLSetup` a 4 kopie zápisu `residuum.txt`.

### Build a bezpečnost

* Cílová verze Javy 17 (GraalJS ji vyžaduje; README původně uvádělo Javu 8).
* `log4j 1.2.17` (bez podpory, známé CVE) nahrazen kompatibilním `reload4j 1.2.25`.
* `org.json` 20090211 → 20240303, odstraněn nepoužívaný `commons-validator`.
* Síťová komunikace paralelního režimu (`litempi`, `Matlab`) deserializuje jen povolené třídy
  (`SafeObjectInputStream`, `ObjectInputFilter`); dřív mohl kdokoli s přístupem na port spustit kód.
  Seznam lze změnit vlastností `-Dflowpro.serialFilter=...`.
* `run.bat` a staré GUI používaly volbu `-d64`, se kterou Java 10+ vůbec nenastartuje.
* `mergedJavadoc` používal konfiguraci `compile`, která v Gradle 7+ neexistuje.

---

## 2. Spouštění úloh – zhodnocení a změny

**Původní stav:** `java -jar FlowPro.jar master 0`, geometrie a simulace se četly ze souboru `args.txt`.
Problémy:

* Pro jiný výpočet bylo nutné přepsat `args.txt` – nešlo spustit dvě úlohy z jednoho adresáře ani je skriptovat.
* Pro změnu parametru (např. CFL pro studii) bylo nutné kopírovat/editovat `parameters.txt`.
* Překlep v názvu parametru (`CFl`, `steps `) se tiše ignoroval a použila se výchozí hodnota.
* Chyby se hlásily postupně po jedné, často až po načtení sítě. Chyběl seznam parametrů s popisem
  (`getparameters` vypisoval Java pole, ne klíče souboru).
* Návratový kód při chybě byl 0, `run.bat` nefungoval na Javě 10+.
* Ukončit výpočet šlo jen zabitím procesu – výsledky od posledního uložení se ztratily.

**Nově** (původní `master N` a `args.txt` fungují dál):

```
java -jar FlowPro.jar run -g examples/GAMM -s default -p CFL=10 -p steps=500
java -jar FlowPro.jar check -g examples/GAMM -s default      # kontrola bez výpočtu
java -jar FlowPro.jar list                                   # geometrie a simulace
java -jar FlowPro.jar params                                 # popis parametrů
java -jar FlowPro.jar help
```

| Volba | Význam |
|---|---|
| `-g, --geometry NAME` | geometrie (`simulations/NAME`), jinak z `args.txt` |
| `-s, --simulation NAME` | simulace, jinak z `args.txt` |
| `-d, --domains N` | počet domén paralelního výpočtu |
| `-p, --set KEY=VALUE` | přepíše hodnotu z `parameters.txt` (opakovatelné, soubor se nemění); prázdná hodnota parametr zruší (`-p CFL= -p dt=1e-3`) |

* **Registr parametrů** (`ParameterRegistry`) popisuje všechny parametry jádra (typ, výchozí hodnota, povolené hodnoty).
  Při každém spuštění se kontrolují typy, povinné parametry, kombinace CFL/dt a překlepy
  („parameter 'CFLL' is not known…, did you mean 'CFL'?“). Parametry rovnic čtou moduly – ty se nehlásí.
* **Řízené zastavení:** soubor `STOP` v adresáři simulace → výpočet dokončí krok, uloží výsledky a skončí
  (pokračovat lze s `continueComputation = true`). Používá ho GUI.
* `run.bat` / `run.sh` předávají argumenty: `run.bat -s mojeSimulace -p steps=100`.

---

## 3. Generátor 2D sítí – `MeshGenerator2D`

`MeshGenerator2D.bat` nebo `java -jar MeshGenerator2D.jar` (v adresáři `FlowPro`). Čistá Java/Swing bez závislostí.

* **Kreslení:** polygon (kliky, dvojklik/Enter uzavře), obdélník, kružnice, NACA 4místný profil,
  kanál (vstup/výstup/stěny), import bodů z textového souboru. První (největší) smyčka je vnější hranice,
  ostatní jsou díry/tělesa.
* **Editace:** přesun bodů myší, dvojklik na úsečku vloží bod, Delete maže bod/smyčku, přichytávání k mřížce.
  Pan pravým/prostředním tlačítkem (nebo Ctrl+tažení), zoom kolečkem.
* **Okrajové podmínky** na každé úsečce: typ (−1 stěna, −2 vstup, −3 výstup, −4 nevazká stěna, libovolné celé číslo),
  lokální velikost buňky (zahuštění, např. u profilu) a ALE značka tělesa (→ `boundaryTypeALE.txt`).
* **Algoritmus:** Delaunayova triangulace (Bowyer–Watson), vynucení hranic dělením úseček,
  Ruppertovo zjemnění (minimální úhel a velikost buněk s plynulým růstem `grading` od zahuštěných úseček),
  Laplaceovo vyhlazení s kontrolou kvality. Typicky min. úhel 27–29°.
* **Export do FlowPro:** `simulations/NAZEV/mesh/` – `vertices.txt`, `elements.txt`, `elementType.txt`,
  `boundaryType.txt`, `neighbors.txt` (+ `boundaryTypeALE.txt`). Soubory odvozené ze staré sítě
  (`wallDistance.txt`, `blendingFunctions.txt`, …) smaže. Geometrii uloží jako `geometry.geo2d`
  a nabídne vytvoření simulace `default` se šablonou parametrů.
* **Dávkový režim:** `java -jar MeshGenerator2D.jar --batch geometrie.geo2d vystupniAdresar`.
* Formát `.geo2d` je čitelný text (viz `simulations/examples/GAMMgenerated/mesh/geometry.geo2d`).

---

## 4. Řízení výpočtu – `FlowProControl`

`FlowProControl.bat` v hlavním adresáři balíku (nebo v `FlowPro`), případně `java -jar FlowProControl.jar`.
FlowPro spouští jako samostatný proces.
Generátor sítí je součástí aplikace (záložka *Mesh*), `MeshGenerator2D.jar` zůstává jako samostatný nástroj
a pro dávkový režim.

* **Úlohy:** všechny úlohy GUI jsou v `simulations/GUI/NAZEV`. Jedna úloha = jedna výpočtová síť
  (`mesh/`, geometrie generátoru `geometry.geo2d`) a libovolný počet výpočtů (podadresáře s `parameters.txt`).
  Pro FlowPro je úloha geometrie `GUI/NAZEV` (`java -jar FlowPro.jar run -g GUI/NAZEV -s VYPOCET`).
* **Seznam úloh** se svými výpočty, *New task* (prázdná, nebo kopie sítě existující úlohy či libovolné
  geometrie ze `simulations` včetně parametrů jejích výpočtů), *New computation* (výchozí parametry nebo kopie
  parametrů jiného výpočtu úlohy, výsledky se nekopírují), *Rename*, *Delete*, *Open folder*.
* **Mesh:** editor geometrie a generátor sítě úlohy. *Save mesh to task* zapíše síť do `mesh/` (upozorní,
  pokud mají výpočty výsledky ze staré sítě), *Save geometry only*, *Revert to saved*. Při přepnutí úlohy
  s neuloženými změnami se aplikace zeptá. Uložená trojúhelníková síť se při otevření úlohy zobrazí;
  pokud byla geometrie od uložení sítě změněna, zobrazí se jen geometrie.
* **Parameters:** tabulka parametrů `parameters.txt` s popisy z registru; zapnutí/vypnutí parametru (`%`),
  přidání parametru ze seznamu. Ukládá se bez porušení komentářů a struktury souboru. Tlačítko *Check*.
* **Run:** paměť JVM, počet domén, „continue computation“, dočasné přepsání parametrů (`CFL=10, steps=500`),
  *Run*, *Stop* (řízené – uloží výsledky), *Kill*, *Postprocessing*. Výstup programu se zobrazuje živě.
* **Convergence:** živý graf residua (log. osa), osa x krok/fyzikální čas, hodnoty pod kurzorem.
* **Mesh & results:** náhled sítě s barevně odlišenými okrajovými podmínkami a barevná mapa průměrů
  řešení (`W.txt`) po složkách.

---

## 5. Co zůstalo beze změny (doporučení z analýzy)

* Jacobiho matice konečnými diferencemi s absolutním krokem `h = 1e-8` (doporučen relativní krok nebo complex-step).
* Škálování interior penalty (`p²/h`), vlastní čísla Rusanova toku v ALE.
* Počáteční podmínka ze skriptu je po elementech konstantní (hodnota v těžišti).
* Mrtvý kód (`Element0.java`, zakomentované řešiče), duplicity variant `DGpure*`, veřejná měnitelná pole.
* Automatické testy (projekt žádné nemá) – ověření níže bylo provedeno ručně.
* Paralelní (síťový) režim nebyl spuštěn – kompilace a serializace dat přes nový filtr ověřeny.
* Staré GUI `FlowProManager` je zachováno (jen odstraněno `-d64`).

---

## 6. Ověření

* `gradlew build` – všechny projekty včetně modulů.
* GAMM (DGpure, BDF1, ILU0, 20 kroků) proti původní binárce: průběh residua shodný, max. relativní
  rozdíl průměrů 2,7e−4 (vliv opraveného kritéria GMRES).
* RK3 proti původní binárce: stejný fyzikální čas, ale původní verze měnila řešení za krok zhruba o polovinu
  (residuum 0,27 vs. 0,73) – důsledek K1.
* Řád RK3 (pevné `dt`, t = 0,04): |RK3(dt) − RK3(dt/2)| = 8,4e−8, |RK3(dt/2) − RK4(dt)| = 1,2e−8 → poměr ≈ 2³.
* Síť z generátoru (GAMM kanál, 1600 trojúhelníků): `check` projde, 0 vadných elementů, výpočet 200 kroků konverguje.
* Řízené zastavení souborem `STOP` (stav uložen, `steps` odpovídá), JS počáteční podmínka, chybové hlášky.
* Serializace `Mesh` přes `SafeObjectInputStream` projde, cizí třída je odmítnuta.

---

## 7. Zlepšení výpočtové metody (návrhy B z revize)

### B3 – míra konvergence a číslování neznámých

* **Residuum** (sloupec 1 v `residuum.txt`, kritérium `residuum`, adaptivní CFL) je nově L2 norma prostorového
  residua R(W) každé rovnice, ‖M⁻¹R‖ = √(Rᵀ M⁻¹ R), vztažená k maximu z prvních 5 kroků (jako ve Fluentu);
  výsledná hodnota je maximum přes rovnice. Dřív to byl neškálovaný součet |W−Wo|/dt koeficientů báze
  (závislý na síti, bázi a – při velkém CFL – i na dt, residuum klesalo jen proto, že rostlo dt).
  Normalizace se ukládá do `state.txt` (`residuumNormalisation`) a při `continueComputation` se zachová.
  Do `residuum.txt` se za čas a CPU přidávají relativní residua jednotlivých rovnic (GUI čte první 3 sloupce).
  Residuum se počítá z R(W) na začátku kroku – v implicitních řešičích z první Newtonovy iterace, v explicitních
  z prvního stupně, tj. bez dalšího vyhodnocení. Paralelní slave procesy posílají součty norem po rovnicích.
* **Newtonovy iterace** končí, když L2 norma residua časového kroku (pravé strany) klesne pod
  `newtonIterTol` × norma před první iterací (dřív absolutní `L1(x)/nElems`). Residuum se kontroluje bez
  sestavení Jacobiho matice (nová metoda `Implicit.assembleResiduum`). Pro `newtonIters = 1` beze změny.
* **Číslování neznámých** – nový parametr `elementOrdering` (`rcm` – výchozí, `none`): reverse Cuthill–McKee
  na grafu sousedů. Mění se jen číslování neznámých v soustavě (`gIndex`), indexy elementů a výstupní soubory
  zůstávají. `blockilu0` postupuje v pořadí neznámých. Opravena `SparseMatrix.updateB`, která předpokládala
  číslování v pořadí elementů. Paralelní řešiče na pořadí elementů spoléhají, používají vždy `none`.
* Ověření (GAMM): výsledky s výchozími parametry jsou bit po bitu stejné jako dřív (mění se jen vypisované
  residuum); s předpodmíněním nezávislým na pořadí (`blockjacobi`, `none`) dává `rcm` stejné výsledky (5e−9);
  p = 2, adaptivní CFL, 150 kroků: čas řešení soustav ILU0 6,8 s → 5,8 s, BlockILU0 4,2 s → 3,6 s.
  Newton (BDF2, `newtonIters = 6`): relativní residuum 4e−3 → 1,7e−5 → 1e−7 (kvadratická konvergence).

### B4 – kvadratura

* Pravidlo řádu k je teď pro všechny typy elementů přesné pro polynomy stupně 2k − 1. Na trojúhelnících
  a čtyřstěnech bylo dřív přesné jen pro 2(k − 1), tj. při výchozím k = p + 1 jen pro stupeň 2p – nelineární
  toky byly podintegrované (aliasing). Výchozí objemová kvadratura je tak 2p + 1 jako na hranách a čtyřúhelnících.
* Trojúhelníková pravidla stupně 3 a 7 mají záporné váhy (−0,56 a −0,15), místo nich se bere pravidlo
  o stupeň vyšší (kladné váhy). Čtyřstěny mají všechny váhy kladné.
* Chybějící pravidlo (soubor) se dřív tiše ignorovalo a výpočet spadl až na `NullPointerException`; nyní
  `QuadratureCentral.rule` hlásí, které pravidlo a proč chybí. Trojúhelníky a čtyřstěny podporují k ≤ 8.
* Ověření (GAMM, adaptivní CFL, 150 kroků): p = 1 konverguje stejně (čas +25 %, 6 místo 3 bodů), p = 2
  relativní residuum 1,8e−2 → 1,4e−2, p = 3 beze změny.

### B5 – ALE a pohyblivá síť

* **Diskrétní GCL** (nový parametr `gcl`, výchozí `true`, BDF1/BDF2): matice hmotnosti v časové derivaci
  se na pohyblivé síti nepočítá z geometrie nové hladiny, ale splňuje a1·M + a2·Mo + a3·Mo2 = B, kde
  B_ij = ∫∂K (u·n) φiφj − ∫K u·∇(φiφj) je změna M daná rychlostí sítě, integrovaná stejnou kvadraturou
  jako ALE členy residua (`Element.applyGCLMassMatrix`). Konstantní stav je pak přesným řešením.
  Ověřeno na čtverci 10×10 (512 trojúhelníků, p = 1, deformace `Test2D` s nehybnou hranicí, klidný plyn,
  100 kroků, jedna perioda): odchylka od počátečního stavu BDF1 0,22 → 1,5e−14, BDF2 0,28 → 2,3e−13.
  Neřešeno: explicitní metody (RK používají pevnou M⁻¹) a `BDFIncompressible*`.
* **Rychlost sítě v objemu** – `ElementData.meshVelocity` se v objemových kvadraturních bodech nenastavovala
  (zůstávala hodnota z poslední stěny); používají ji např. zdrojové členy a odhad vlastních čísel.
* **Vlastní číslo** (Rusanovův tok, časový krok, umělá viskozita) v `Aerodynamics` (všechny stlačitelné
  modely kromě rotujících souřadnic) používá rychlost relativní vůči síti |v − u| + a, dřív |v| + a.
  Na statické síti beze změny (rozdíl v zaokrouhlení, GAMM 1,7e−10).
* **Energie na pohyblivé adiabatické stěně** – vazký tok energie se nuloval celý; nyní je nulový jen tepelný
  tok a zůstává práce smykového napětí u_w·(τn). Moduly s typem stěny `WALL` (NavierStokes, KOmega*,
  SpalartAllmaras*, IBM, R, MatlabClient, VelocityBreak); `SpalartAllmarasRotordynamics/Seal` mají vlastní
  rychlost stěny a zůstaly beze změny.
