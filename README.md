# ✈ Letadlo.app

Jednoduchý letecký simulátor pro Android, který se ovládá **jen náklonem telefonu**.
Žádné palivo, žádné úkoly – jen létání. Když se vybouráš, let skončí.

## Stažení

Nejnovější APK je vždy tady: **[Letadlo.apk](https://github.com/Garon92/Letadlo/releases/latest/download/Letadlo.apk)**
(sestavuje se automaticky přes GitHub Actions z větve `main`).

Na telefonu povol instalaci z neznámých zdrojů a APK otevři. Vyžaduje Android 8.0+ a OpenGL ES 3.0.

## Jak se hraje

1. V menu vyber mapu: **Hory**, **Město** nebo **Krajina**.
2. Drž telefon na šířku v pohodlné poloze – první půlvteřinu se kalibruje „rovný let".
3. Letadlo se samo rozjede po dráze. Až ukazatel napíše *„Teď!"*, nakloň horní hranu telefonu k sobě a vzlétneš.
4. Natáčení telefonu jako volantem (nebo náklon do strany) = zatáčka.
   Horní hrana k sobě = stoupání, od sebe = klesání.
5. Ťuknutím na obrazovku telefon kdykoli znovu zkalibruješ.
6. Náraz do země, hory, budovy, stromů nebo pád do vody let ukončí. Měkké dosednutí na dráhu je přistání – můžeš zase vzlétnout.

V menu jde zapnout **Obrátit výškovku**, pokud ti víc sedí opačné ovládání.

## Mapy

| Mapa | Co v ní je |
| --- | --- |
| 🏔️ Hory | Dlouhé údolí od letiště, štíty přes 1 800 m se sněhem, jehličnaté lesy, horská jezera |
| 🏙️ Město | Mrakodrapy v centru, síť ulic, parky a řeka protékající městem |
| 🌾 Krajina | Pole, louky, lesy, jezera a zvlněné kopce |

Svět je procedurálně generovaný a nekonečný – můžeš letět kterýmkoli směrem.

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
