# ✈ Letadlo.app

Jednoduchý letecký simulátor pro Android, který se ovládá **jen náklonem telefonu**.
Žádné palivo, žádné úkoly – jen létání. Když se vybouráš, let skončí.

## Stažení

Nejnovější APK je vždy tady: **[Letadlo.apk](https://github.com/Garon92/Letadlo/releases/latest/download/Letadlo.apk)**
(sestavuje se automaticky přes GitHub Actions z větve `main`).

Na telefonu povol instalaci z neznámých zdrojů a APK otevři. Vyžaduje Android 8.0+ a OpenGL ES 3.0.

## Jak se hraje

1. V menu vyber **letadlo** a pak **mapu** (Hory, Město nebo Krajina).
2. Drž telefon na šířku v pohodlné poloze – první půlvteřinu se kalibruje „rovný let".
3. Letadlo se samo rozjede po dráze. Až ukazatel napíše *„Teď!"*, nakloň horní hranu telefonu k sobě a vzlétneš.
   Vrtulník nejdřív roztočí rotor a pak vzlétá svisle.
4. Natáčení telefonu jako volantem (nebo náklon do strany) = zatáčka.
   Horní hrana k sobě = stoupání, od sebe = klesání.
5. Ťuknutím na obrazovku telefon kdykoli znovu zkalibruješ.
6. Kulatá tlačítka vpravo dole: **✈️ / 💺** přepíná pohled zvenku a z kabiny, **☀️ / 🌙** den a noc.
   Vpravo nahoře je průhledná mapa okolí natočená ve směru letu.
7. **Stíhačka** má vlevo tlačítko 🚀 – dokud ho držíš, neomezeně pálí rakety přesně do zeleného zaměřovače.
   **Bombardér** má tlačítko 💣 – shazuje silné bomby, zelený kruh na zemi ukazuje, kam dopadnou.
   Výbuchy rozházejí auta a lidi, zbortí budovy a domy, spálí stromy a zanechají ohořelou zem a hořící trosky.
8. Náraz do země, hory, budovy, stromů, elektrárny nebo pád do vody let ukončí.
   Měkké dosednutí na dráhu je přistání – můžeš zase vzlétnout. Vrtulník přistane na jakékoli rovině.

V menu je posuvník **citlivosti náklonu** (výchozí: plná zatáčka už při ~15° náklonu telefonu),
volba **Obrátit výškovku** a vypínač **Zvuku motoru**.

## Letadla

| Letadlo | Vlastnosti |
| --- | --- |
| 🛩️ Sportovní | ~300 km/h, krátký rozjezd, klidné a snadné |
| ⚡ Stíhačka | ~2 200 km/h, ostré zatáčky, rakety a zaměřovač |
| ✈️ Dopravní | ~840 km/h, dlouhý rozjezd, pomalé zatáčky |
| 🚁 Vrtulník | ~270 km/h, svislý vzlet, přistání kdekoli, náklon k sobě = stoupání |
| 💣 Bombardér | ~480 km/h, čtyři vrtule, neomezené bomby |

## Mapy

| Mapa | Co v ní je |
| --- | --- |
| 🏔️ Hory | Dlouhé údolí od letiště, štíty přes 1 800 m se sněhem, jehličnaté lesy, balvany, ovce |
| 🏙️ Město | Mrakodrapy, ulice s auty a chodci, předměstí, parky a řeka s loďkami |
| 🌾 Krajina | Pole, lesy, vesnice s kostelem, jezera s plachetnicemi, krávy, větrné elektrárny |

Všude létají hejna ptáků a plují mraky. V noci svítí okna, pouliční lampy, světla dráhy,
světla aut i přistávací světlo letadla. Svět je procedurálně generovaný a nekonečný.

Výbuch letadla rozhází auta a lidi v okolí.

## Technicky

- Kotlin + OpenGL ES 3.0, bez herního enginu a bez závislostí (jen Android SDK).
- Terén se generuje po čtvercích 640 × 640 m na pozadí, se dvěma úrovněmi detailu.
- Kolize používají přesně stejnou triangulaci jako vykreslený terén.
- Letový model je arkádový: náklon telefonu → cílový náklon křídel a sklon nosu, motor táhne pořád.

```
app/src/main/java/app/letadlo/
├── MainActivity.kt      menu a výběr mapy
├── GameActivity.kt      hra, HUD, obrazovka havárie
├── TiltInput.kt         čtení náklonu (gravitační senzor) a kalibrace
├── game/                čistá logika bez Androidu (testovatelná na JVM)
│   ├── World.kt         terén, mapy, stromy, budovy, kolize
│   ├── FlightModel.kt   letový model
│   └── ...
└── gl/                  vykreslování (shadery, chunky, renderer)
```

### Sestavení

```bash
./gradlew testDebugUnitTest assembleRelease
```

Výsledek: `app/build/outputs/apk/release/app-release.apk`. Podpisový klíč `app/letadlo.keystore`
je záměrně veřejný (jen pro sideload), aby šly nové verze instalovat přes staré.
