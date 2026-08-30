# Clavier Sons (fork HeliBoard + panneau myinstants) — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fork HeliBoard avec un bouton 🔊 dans la toolbar qui ouvre un panneau de sons : recherche sur myinstants.com, aperçu audio, envoi du fichier audio via la feuille de partage Android.

**Architecture:** Tout le code nouveau vit dans un module JVM pur `:sounds-core` (parser, stockage — testable en local sans SDK Android) et dans le paquet `app/.../keyboard/sounds/` (vue, téléchargement, partage). HeliBoard est modifié au minimum : `KeyCode`, `ToolbarUtils`, `KeyboardIconsSet`, `KeyboardSwitcher`, `KeyboardActionListenerImpl`, `main_keyboard_frame.xml`, manifest, strings. `LatinIME` et `InputView` ne sont **pas** touchés (correction vs spec : `KeyboardSwitcher` est le vrai aiguilleur de panneaux, pas `InputView`).

**Tech Stack:** HeliBoard 3.9 (Java/Kotlin, AOSP), Kotlin JVM 2.2.21, Jsoup (seule nouvelle dépendance), MediaPlayer (aperçu), FileProvider + ACTION_SEND (envoi), GitHub Actions (builds APK + release continue `dev`).

**Spec:** `docs/superpowers/specs/2026-08-30-clavier-sons-design.md`

## Global Constraints

- **Aucun SDK Android en local** (contrainte du user). Tests du module : `./gradlew -p sounds-core test` (JVM pur, JDK 17 déjà installé). Tout le reste (compilation app, APK) : GitHub Actions.
- Repo local : git uniquement. Push sur `main` (release) et `wip` (itération) déclenche le workflow.
- Nouvelles dépendances : **jsoup uniquement** (module `:sounds-core`). Pas d'okhttp (Jsoup télécharge aussi les mp3), pas de Room (stockage = `java.util.Properties`).
- La logique pure (parser, stockage) vit dans `:sounds-core`, package `helium314.keyboard.soundscore`. Le code Android vit dans `app/src/main/java/helium314/keyboard/keyboard/sounds/`, package `helium314.keyboard.keyboard.sounds`.
- En-têtes de fichiers nouveaux : `// SPDX-License-Identifier: GPL-3.0-only` (convention HeliBoard).
- Strings : défaut anglais dans `values/`, français dans `values-fr/`. Nom de string du bouton toolbar = `sounds` (dérive de `ToolbarKey.SOUNDS.name.lowercase()`).
- Fonctions réseau du module : **bloquantes** (appelées depuis un thread secondaire côté app).
- `versionCode` (3901) : à incrémenter manuellement avant chaque APK destiné à une mise à jour sur le téléphone.
- myinstants scrape (vérifié par curl le 2026-08-30) : résultats dans `div.instant`, chaque bloc contient
  `<button class="small-button" onclick="play('/media/sounds/X.mp3', 'loader-<id>', '<slug>')">` et
  `<a href="/fr/instant/<slug>/" class="instant-link link-secondary">TITRE</a>`.
- Le réseau n'est JAMAIS appelé dans les tests : les tests parsent des fixtures HTML sauvegardées dans `sounds-core/src/test/resources/`.

## Vérifications — qui tourne où

| Type | Commande / lieu | Durée |
|---|---|---|
| Tests module | `./gradlew -p sounds-core test` (local, Git Bash) | ~10 s |
| Compilation app + APK | push → GitHub Actions job `build` | ~15 min |
| Tests module en CI | push → GitHub Actions job `test` (`./gradlew :sounds-core:test`) | ~15 min |
| UX | Install APK depuis la Release `dev` sur le téléphone | manuel |

---

### Task 1: Base HeliBoard + identité + signature + CI (M0)

Fusionner HeliBoard dans le repo (qui contient déjà `docs/`), renommer l'app, créer le keystore, configurer la signature release, créer le workflow GitHub Actions qui publie l'APK sur une Release `dev`.

**Files:**
- Modify: `app/build.gradle.kts` (signingConfig release, versionName)
- Modify: `app/src/main/res/values/strings.xml` (app_name)
- Create: `keystore/keyboard-release.keystore` (via keytool)
- Create: `.github/workflows/build-sons.yml`

**Interfaces:**
- Consumes: rien.
- Produces: un APK release signé installable, workflow `Build Sons APK` actif sur push (`main`, `wip`), tag de Release `dev`.

- [ ] **Step 1: Fusionner HeliBoard dans le repo**

```bash
cd "c:/Users/Administrator/Desktop/Projets/clavier"
git remote add heliboard https://github.com/Helium314/HeliBoard.git
git fetch heliboard master
git merge heliboard/master --allow-unrelated-histories -m "Merge HeliBoard 3.9 base"
```

Vérifier: `ls app/build.gradle.kts settings.gradle gradlew` existent.

- [ ] **Step 2: Renommer l'app et la version**

Dans `app/build.gradle.kts` remplacer `versionName = "3.9"` par `versionName = "3.9-sons"`.
Dans `app/src/main/res/values/strings.xml`, localiser la ligne `name="app_name"` et mettre la valeur `Clavier Sons`.

```bash
grep -n 'name="app_name"' app/src/main/res/values/strings.xml
```

- [ ] **Step 3: Générer le keystore (JDK 17 déjà présent sur la machine)**

```bash
mkdir -p keystore
keytool -genkeypair -v -keystore keystore/keyboard-release.keystore -alias clavier \
  -keyalg RSA -keysize 2048 -validity 10000 -storepass clavier123 -keypass clavier123 \
  -dname "CN=clavier-sons"
```

Projet perso : les mots de passe sont volontairement en clair et le keystore est commité (signature stable = mises à jour installables par-dessus sans désinstallation).

- [ ] **Step 4: Configurer la signature release**

Dans `app/build.gradle.kts`, dans le bloc `android { }`, ajouter AVANT `buildTypes` :

```kotlin
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/keyboard-release.keystore")
            storePassword = "clavier123"
            keyAlias = "clavier"
            keyPassword = "clavier123"
        }
    }
```

et dans `buildTypes { release { ... } }` ajouter la ligne :

```kotlin
            signingConfig = signingConfigs.getByName("release")
```

- [ ] **Step 5: Créer le workflow CI**

Créer `.github/workflows/build-sons.yml` :

```yaml
name: Build Sons APK

on:
  push:
    branches: [main, wip]

permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - uses: gradle/actions/setup-gradle@v3
      - name: Build release APK
        run: |
          chmod +x gradlew
          ./gradlew assembleRelease
      - name: Publish to dev release
        uses: softprops/action-gh-release@v2
        with:
          tag_name: dev
          name: "Dernière version de dev"
          files: app/build/outputs/apk/release/*.apk
          make_latest: true
```

(ubuntu-latest a l'Android SDK préinstallé ; l'AGP télécharge le NDK 28.0.13004108 automatiquement, licences déjà acceptées sur les runners GitHub.)

- [ ] **Step 6: Créer le repo GitHub et pousser**

Nécessite un compte GitHub du user (gratuit). Créer un repo **public** vide nommé `clavier-sons`, puis :

```bash
git add -A
git commit -m "feat: HeliBoard base + signature release + CI (M0)"
git remote add origin https://github.com/<USER>/clavier-sons.git
git push -u origin main
```

- [ ] **Step 7: Vérifier le CI et l'APK**

Attendre ~15 min (github.com → Actions). Job `build` vert + Release `dev` contenant `HeliBoard_3.9-sons-release.apk`.

- [ ] **Step 8: Checkpoint user — installer sur le téléphone (M0)**

User : télécharger l'APK depuis la Release `dev` dans le navigateur du téléphone → installer (autoriser « sources inconnues ») → activer le clavier dans Réglages → vérifier que la frappe, les suggestions et le dictionnaire FR marchent.

---

### Task 2: Module JVM `:sounds-core` (composite build)

Créer un module Gradle autonome (buildable/testable sans SDK Android grâce au flag `-p`) et le brancher dans l'app via un composite build.

**Files:**
- Create: `sounds-core/settings.gradle.kts`
- Create: `sounds-core/build.gradle.kts`
- Create: `sounds-core/src/main/kotlin/helium314/keyboard/soundscore/Sounds.kt` (placeholder vide pour l'instant : juste le package)
- Create: `sounds-core/src/test/kotlin/helium314/keyboard/soundscore/SmokeTest.kt`
- Modify: `settings.gradle` (includeBuild)
- Modify: `app/build.gradle.kts` (dépendance + job test CI viendra dans le workflow)
- Modify: `.github/workflows/build-sons.yml` (job `test`)

**Interfaces:**
- Consumes: rien.
- Produces: module `helium314.keyboard:sounds-core:1.0` consommable par `:app` ; commande de test locale `./gradlew -p sounds-core test`.

- [ ] **Step 1: Créer `sounds-core/settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories { mavenCentral(); google() }
}
dependencyResolutionManagement {
    repositories { mavenCentral(); google() }
}
rootProject.name = "sounds-core"
```

- [ ] **Step 2: Créer `sounds-core/build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm") version "2.2.21"
    `java-library`
}

group = "helium314.keyboard"
version = "1.0"

kotlin { jvmToolchain(17) }

dependencies {
    api("org.jsoup:jsoup:1.18.3")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
```

- [ ] **Step 3: Créer le placeholder et le smoke test**

`sounds-core/src/main/kotlin/helium314/keyboard/soundscore/Sounds.kt` :

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.soundscore
```

`sounds-core/src/test/kotlin/helium314/keyboard/soundscore/SmokeTest.kt` :

```kotlin
package helium314.keyboard.soundscore

import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test fun sanity() { assertEquals(4, 2 + 2) }
}
```

- [ ] **Step 4: Tester en local (SANS SDK Android — ça doit marcher)**

```bash
cd "c:/Users/Administrator/Desktop/Projets/clavier"
./gradlew -p sounds-core test
```

Attendu: `BUILD SUCCESSFUL`, 1 test exécuté. (Premier run plus long : téléchargement de Gradle + deps.)

- [ ] **Step 5: Brancher le composite build**

Dans `settings.gradle` (racine), ajouter à la fin :

```groovy
includeBuild 'sounds-core'
```

Dans `app/build.gradle.kts`, dans le bloc `dependencies { }` :

```kotlin
    implementation("helium314.keyboard:sounds-core:1.0")
```

- [ ] **Step 6: Ajouter le job test au workflow**

Dans `.github/workflows/build-sons.yml`, ajouter AVANT le job `build` :

```yaml
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - uses: gradle/actions/setup-gradle@v3
      - name: Run sounds-core tests
        run: |
          chmod +x gradlew
          ./gradlew :sounds-core:test
```

et ajouter `needs: test` au job `build` (sous `build:`, ligne `needs: test`).

- [ ] **Step 7: Commit + push + vérifier CI**

```bash
git add -A && git commit -m "feat: sounds-core JVM module (composite build, tests locaux sans SDK)"
git push
```

CI: job `test` vert puis job `build` vert, nouvelle APK sur la Release `dev`.

---

### Task 3: SoundItem + parser MyInstantsSource (TDD local)

**Files:**
- Create: `sounds-core/src/main/kotlin/helium314/keyboard/soundscore/SoundItem.kt`
- Create: `sounds-core/src/main/kotlin/helium314/keyboard/soundscore/MyInstantsSource.kt`
- Create: `sounds-core/src/test/resources/search_bruh.html` (fixture)
- Create: `sounds-core/src/test/kotlin/helium314/keyboard/soundscore/MyInstantsSourceTest.kt`

**Interfaces:**
- Consumes: module `:sounds-core` (Task 2).
- Produces: `data class SoundItem(id: String, title: String, mediaUrl: String, pageUrl: String)` ;
  `class MyInstantsSource(baseUrl: String = "https://www.myinstants.com") : SoundSource` avec
  `override fun search(query: String): List<SoundItem>`, `override fun trending(): List<SoundItem>`
  (bloquantes, thread secondaire requis côté app) et `fun parse(html: String): List<SoundItem>` (pure, pour tests).

- [ ] **Step 1: Écrire les fixtures HTML (markup réel, vérifié par curl)**

`sounds-core/src/test/resources/search_bruh.html` :

```html
<html><body><div id="instants_container">
<div class="instants result-page">
<div class="instant">
<button class="small-button" onclick="play('/media/sounds/movie_1.mp3', 'loader-23010', 'bruh')" title="Jouer le son de BRUH" type="button">▶</button>
<a href="/fr/instant/bruh/" class="instant-link link-secondary">BRUH</a>
</div>
<div class="instant">
<button class="small-button" onclick="play('/media/sounds/movie_1_C2K5NH0.mp3', 'loader-143672', 'bruh-meme-44087')" title="Jouer le son de Bruh meme" type="button">▶</button>
<a href="/fr/instant/bruh-meme-44087/" class="instant-link link-secondary">Bruh meme</a>
</div>
<div class="instant">
<button class="small-button" onclick="play('/media/sounds/bruh-sound-effect_WstdzdM.mp3', 'loader-297229', 'bruh-sound-effect-26614')" title="Jouer le son de BRUH sound effect!" type="button">▶</button>
<a href="/fr/instant/bruh-sound-effect-26614/" class="instant-link link-secondary">BRUH sound effect!</a>
</div>
</div>
</div></body></html>
```

- [ ] **Step 2: Écrire SoundItem**

`sounds-core/src/main/kotlin/helium314/keyboard/soundscore/SoundItem.kt` :

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.soundscore

data class SoundItem(
    val id: String,
    val title: String,
    val mediaUrl: String,
    val pageUrl: String,
)

interface SoundSource {
    /** blocking — call from a background thread */
    fun search(query: String): List<SoundItem>
    /** blocking — call from a background thread */
    fun trending(): List<SoundItem>
}
```

- [ ] **Step 3: Écrire le test (échoue — MyInstantsSource n'existe pas)**

`sounds-core/src/test/kotlin/helium314/keyboard/soundscore/MyInstantsSourceTest.kt` :

```kotlin
package helium314.keyboard.soundscore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MyInstantsSourceTest {
    private val html = javaClass.getResourceAsStream("/search_bruh.html")!!
        .readBytes().decodeToString()
    private val source = MyInstantsSource()

    @Test fun parseExtractsSoundsWithAbsoluteUrls() {
        val items = source.parse(html)
        assertEquals(3, items.size)
        val first = items.first()
        assertEquals("23010", first.id)
        assertEquals("BRUH", first.title)
        assertEquals("https://www.myinstants.com/media/sounds/movie_1.mp3", first.mediaUrl)
        assertEquals("https://www.myinstants.com/fr/instant/bruh/", first.pageUrl)
    }

    @Test fun parseEmptyPageReturnsEmptyList() {
        assertTrue(source.parse("<html><body></body></html>").isEmpty())
    }
}
```

- [ ] **Step 4: Vérifier l'échec**

```bash
./gradlew -p sounds-core test
```

Attendu: FAIL — `MyInstantsSource` introuvable (compilation).

- [ ] **Step 5: Implémenter MyInstantsSource**

`sounds-core/src/main/kotlin/helium314/keyboard/soundscore/MyInstantsSource.kt` :

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.soundscore

import org.jsoup.Jsoup
import java.net.URLEncoder

class MyInstantsSource(
    private val baseUrl: String = "https://www.myinstants.com",
) : SoundSource {

    override fun search(query: String): List<SoundItem> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = Jsoup.connect("$baseUrl/fr/search/?name=$encoded")
            .userAgent(USER_AGENT).get()
        return parse(doc)
    }

    override fun trending(): List<SoundItem> {
        val doc = Jsoup.connect("$baseUrl/fr/").userAgent(USER_AGENT).get()
        return parse(doc)
    }

    fun parse(html: String): List<SoundItem> = parse(Jsoup.parse(html))

    fun parse(doc: org.jsoup.nodes.Document): List<SoundItem> = doc.select("div.instant").mapNotNull { el ->
        val play = el.selectFirst("button.small-button")?.attr("onclick") ?: return@mapNotNull null
        val media = PLAY_REGEX.find(play)?.groupValues?.get(1) ?: return@mapNotNull null
        val link = el.selectFirst("a.instant-link") ?: return@mapNotNull null
        val id = ID_REGEX.find(play)?.groupValues?.get(1) ?: media.hashCode().toString()
        SoundItem(id, link.text().trim(), absolutize(media), absolutize(link.attr("href")))
    }

    private fun absolutize(url: String) = if (url.startsWith("http")) url else baseUrl + url

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"
        val PLAY_REGEX = Regex("""play\('([^']+)'.*?loader-(\d+)""")
        val ID_REGEX = Regex("""loader-(\d+)""")
    }
}
```

- [ ] **Step 6: Vérifier le passage**

```bash
./gradlew -p sounds-core test
```

Attendu: PASS (3 tests).

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat(sounds-core): SoundItem + MyInstantsSource parser (fixtures HTML réels)"
```

---

### Task 4: SoundStore (favoris + récents, TDD local)

**Files:**
- Create: `sounds-core/src/main/kotlin/helium314/keyboard/soundscore/SoundStore.kt`
- Create: `sounds-core/src/test/kotlin/helium314/keyboard/soundscore/SoundStoreTest.kt`

**Interfaces:**
- Consumes: `SoundItem` (Task 3).
- Produces: `class SoundStore(file: java.io.File)` avec
  `fun favorites(): List<SoundItem>`, `fun isFavorite(id: String): Boolean`,
  `fun toggleFavorite(item: SoundItem)`, `fun addRecent(item: SoundItem)`, `fun recents(): List<SoundItem>`
  (récents : max 20, plus récent en premier, dédupliqués par id ; persistance automatique à chaque mutation).

- [ ] **Step 1: Écrire le test (échoue)**

`sounds-core/src/test/kotlin/helium314/keyboard/soundscore/SoundStoreTest.kt` :

```kotlin
package helium314.keyboard.soundscore

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SoundStoreTest {
    private fun item(id: String) = SoundItem(id, "titre $id", "https://x/$id.mp3", "https://x/$id")

    @Test fun toggleFavoriteAddsThenRemoves() {
        val store = SoundStore(File.createTempFile("store", ".properties"))
        store.toggleFavorite(item("1"))
        assertTrue(store.isFavorite("1"))
        assertEquals(1, store.favorites().size)
        store.toggleFavorite(item("1"))
        assertFalse(store.isFavorite("1"))
        assertEquals(0, store.favorites().size)
    }

    @Test fun addRecentDedupsAndLimitsTo20() {
        val store = SoundStore(File.createTempFile("store", ".properties"))
        (1..25).forEach { store.addRecent(item("$it")) }
        store.addRecent(item("25")) // déjà en tête
        val recents = store.recents()
        assertEquals(20, recents.size)
        assertEquals("25", recents.first().id)
        assertEquals("6", recents.last().id)
    }

    @Test fun persistsAcrossInstances() {
        val file = File.createTempFile("store", ".properties")
        val store = SoundStore(file)
        store.toggleFavorite(item("42"))
        store.addRecent(item("7"))
        val reloaded = SoundStore(file)
        assertTrue(reloaded.isFavorite("42"))
        assertEquals("7", reloaded.recents().single().id)
        assertEquals("titre 7", reloaded.recents().single().title)
    }
}
```

- [ ] **Step 2: Vérifier l'échec**

```bash
./gradlew -p sounds-core test
```

Attendu: FAIL — `SoundStore` introuvable.

- [ ] **Step 3: Implémenter SoundStore**

`sounds-core/src/main/kotlin/helium314/keyboard/soundscore/SoundStore.kt` :

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.soundscore

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties

class SoundStore(private val file: File) {
    private val props = Properties().also { p ->
        if (file.exists()) FileInputStream(file).use { p.load(it) }
    }
    private val favorites = LinkedHashMap<String, SoundItem>()
    private val recents = LinkedHashMap<String, SoundItem>()

    init {
        listOf("favorites" to favorites, "recents" to recents).forEach { (key, map) ->
            idsOf(key).forEach { id -> load(id)?.let { map[id] = it } }
        }
    }

    fun favorites(): List<SoundItem> = favorites.values.toList()

    fun isFavorite(id: String): Boolean = favorites.containsKey(id)

    fun toggleFavorite(item: SoundItem) {
        if (favorites.remove(item.id) == null) favorites[item.id] = item
        save()
    }

    fun addRecent(item: SoundItem) {
        recents.remove(item.id)
        recents[item.id] = item
        while (recents.size > MAX_RECENTS) recents.remove(recents.keys.first())
        save()
    }

    fun recents(): List<SoundItem> = recents.values.toList()

    private fun idsOf(key: String): List<String> =
        (props.getProperty(key) ?: "").split("\n").filter { it.isNotBlank() }

    private fun load(id: String): SoundItem? {
        val media = props.getProperty("item.$id.media") ?: return null
        return SoundItem(id,
            props.getProperty("item.$id.title") ?: "",
            media,
            props.getProperty("item.$id.page") ?: "")
    }

    private fun save() {
        props.setProperty("favorites", favorites.keys.joinToString("\n"))
        props.setProperty("recents", recents.keys.joinToString("\n"))
        (favorites.values + recents.values).forEach {
            props.setProperty("item.${it.id}.title", it.title.replace(Regex("[\\p{Cntrl}]"), " "))
            props.setProperty("item.${it.id}.media", it.mediaUrl)
            props.setProperty("item.${it.id}.page", it.pageUrl)
        }
        FileOutputStream(file).use { props.store(it, "soundboard store") }
    }

    private companion object { const val MAX_RECENTS = 20 }
}
```

- [ ] **Step 4: Vérifier le passage + commit**

```bash
./gradlew -p sounds-core test
git add -A && git commit -m "feat(sounds-core): SoundStore favoris/récents persistant (Properties)"
```

Attendu: PASS (6 tests au total dans le module).

---

### Task 5: Bouton toolbar SONS (KeyCode + ToolbarUtils + icônes + strings)

**Files:**
- Modify: `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/floris/KeyCode.kt`
- Modify: `app/src/main/java/helium314/keyboard/latin/utils/ToolbarUtils.kt`
- Modify: `app/src/main/java/helium314/keyboard/keyboard/internal/KeyboardIconsSet.kt` (3 blocs `when`)
- Create: `app/src/main/res/drawable/ic_music_note.xml`
- Modify: `app/src/main/res/values/strings.xml` + `app/src/main/res/values-fr/strings.xml`

**Interfaces:**
- Consumes: rien.
- Produces: `KeyCode.SOUNDS = -10052` ; entrée `ToolbarKey.SOUNDS` mappée sur ce code, activée par défaut dans la toolbar ; icône `ic_music_note` ; string `sounds` (FR: « sons »).

Note: le `when` des icônes dans `KeyboardIconsSet` est exhaustif (sans `else`) — le compilateur listera chaque bloc à corriger.

- [ ] **Step 1: Ajouter le code**

Dans `KeyCode.kt`, après la ligne `const val INLINE_EMOJI_SEARCH_DONE =  -10051` ajouter :

```kotlin
    const val SOUNDS =                     -10052
```

et dans `checkAndConvertCode()`, dans la liste « heliboard only », ajouter `SOUNDS,` juste après `INLINE_EMOJI_SEARCH_DONE,`.

- [ ] **Step 2: Déclarer la clé toolbar**

Dans `ToolbarUtils.kt` :
1. enum : `..., CLOSE_HISTORY, EMOJI, SOUNDS, LEFT, ...` (insérer `SOUNDS` après `EMOJI`)
2. `getCodeForToolbarKey` : ajouter `SOUNDS -> KeyCode.SOUNDS` (après la ligne `EMOJI -> KeyCode.EMOJI`)
3. `defaultToolbarPref` : insérer `SOUNDS` dans la liste `default` après `CLIPBOARD` :
   `val default = listOf(SETTINGS, VOICE, CLIPBOARD, SOUNDS, UNDO, REDO, SELECT_WORD, COPY, PASTE, LEFT, RIGHT)`

- [ ] **Step 3: Créer l'icône**

`app/src/main/res/drawable/ic_music_note.xml` :

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="@color/toolbar_icon_fill"
        android:pathData="M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z" />
</vector>
```

Si `@color/toolbar_icon_fill` n'existe pas, remplir avec `#FFFFFF` et laisser `KeyboardIconsSet`/thème teinter l'icône (les icônes toolbar existantes utilisent le même mécanisme de teinte ; vérifier le fillColor d'une icône existante, ex. `ic_undo.xml`, et copier sa valeur).

- [ ] **Step 4: Enregistrer l'icône dans les 3 styles**

Dans `KeyboardIconsSet.kt`, dans CHACUN des trois blocs `ToolbarKey.entries.forEach { ... }` (holo, material, rounded), ajouter la même branche juste après `ToolbarKey.EMOJI -> ...` :

```kotlin
                    ToolbarKey.SOUNDS -> R.drawable.ic_music_note
```

- [ ] **Step 5: Ajouter les strings (contentDescription = nom d'enum en minuscules)**

`values/strings.xml` : `<string name="sounds">sounds</string>`
`values-fr/strings.xml` : `<string name="sounds">sons</string>`

- [ ] **Step 6: Commit + push + CI vert**

```bash
git add -A && git commit -m "feat: bouton toolbar SONS (code, clé, icône, strings)"
git push
```

Attendu: job `build` vert (le `when` exhaustif garantit qu'aucun enregistrement d'icône n'a été oublié).

---

### Task 6: Panneau sons — squelette UI + commutation (M1)

**Files:**
- Create: `app/src/main/res/layout/sounds_palettes_view.xml`
- Create: `app/src/main/res/layout/item_sound.xml`
- Create: `app/src/main/java/helium314/keyboard/keyboard/sounds/SoundsCallback.kt`
- Create: `app/src/main/java/helium314/keyboard/keyboard/sounds/SoundsPalettesView.kt`
- Modify: `app/src/main/res/layout/main_keyboard_frame.xml` (include du panneau)
- Modify: `app/src/main/java/helium314/keyboard/keyboard/KeyboardSwitcher.java`
- Modify: `app/src/main/java/helium314/keyboard/keyboard/KeyboardActionListenerImpl.kt`

**Interfaces:**
- Consumes: `KeyCode.SOUNDS` (Task 5).
- Produces:
  - `interface SoundsCallback { fun onSendSound(item: SoundItem); fun onSwitchToTextKeyboard() }`
  - `class SoundsPalettesView(context, attrs)` avec `fun setCallback(cb: SoundsCallback?)`, `fun startSoundsPalettes()`, `fun stopSoundsPalettes()`
  - `KeyboardSwitcher`: `public void toggleSoundsPanel()`, `public void setSoundsKeyboard()`, `public boolean isShowingSoundsPalettes()`

- [ ] **Step 1: Layout du panneau**

Créer `app/src/main/res/layout/sounds_palettes_view_children.xml` (le contenu du panneau ;
il sera gonflé DANS la vue custom `SoundsPalettesView` — d'où le nom `_children`) :

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- SPDX-License-Identifier: GPL-3.0-only -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="?android:attr/colorBackground">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:padding="8dp">

        <EditText
            android:id="@+id/sounds_search_edit"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:hint="@string/search_sounds_hint"
            android:imeOptions="actionSearch"
            android:inputType="text"
            android:maxLines="1" />

        <ImageButton
            android:id="@+id/sounds_back_to_keyboard"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:contentDescription="@string/sounds_back_to_keyboard"
            android:src="@drawable/sym_keyboard_keyboard_lxx"
            style="?attr/suggestionWordStyle" />
    </LinearLayout>

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal">

        <TextView android:id="@+id/sounds_tab_trending" style="@style/SoundsTab"
            android:text="@string/sounds_tab_trending" />
        <TextView android:id="@+id/sounds_tab_favorites" style="@style/SoundsTab"
            android:text="@string/sounds_tab_favorites" />
        <TextView android:id="@+id/sounds_tab_recents" style="@style/SoundsTab"
            android:text="@string/sounds_tab_recents" />
    </LinearLayout>

    <TextView
        android:id="@+id/sounds_status_view"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:gravity="center"
        android:padding="24dp"
        android:text="@string/sounds_empty"
        android:visibility="visible" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/sounds_recycler"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:visibility="gone" />
</LinearLayout>
```

Note: si le drawable `sym_keyboard_keyboard_lxx` n'existe pas dans ce fork, remplacer `android:src` par un texte `TextView` « AZERTY » avec le même id.

- [ ] **Step 2: Style des onglets + cellule**

Dans `app/src/main/res/values/styles.xml`, ajouter :

```xml
    <style name="SoundsTab">
        <item name="android:layout_width">0dp</item>
        <item name="android:layout_height">wrap_content</item>
        <item name="android:layout_weight">1</item>
        <item name="android:gravity">center</item>
        <item name="android:padding">10dp</item>
        <item name="android:background">?android:attr/selectableItemBackground</item>
    </style>
```

`app/src/main/res/layout/item_sound.xml` :

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- SPDX-License-Identifier: GPL-3.0-only -->
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="10dp">

    <TextView
        android:id="@+id/sound_title"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:maxLines="1"
        android:ellipsize="end" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal">

        <TextView android:id="@+id/sound_play" android:layout_width="wrap_content"
            android:layout_height="wrap_content" android:padding="8dp"
            android:text="▶" android:textSize="18sp" />
        <TextView android:id="@+id/sound_send" android:layout_width="wrap_content"
            android:layout_height="wrap_content" android:padding="8dp"
            android:text="➤" android:textSize="18sp" />
        <TextView android:id="@+id/sound_favorite" android:layout_width="wrap_content"
            android:layout_height="wrap_content" android:padding="8dp"
            android:text="☆" android:textSize="18sp" />
    </LinearLayout>
</LinearLayout>
```

- [ ] **Step 3: Strings**

`values/strings.xml` :

```xml
    <string name="search_sounds_hint">search a sound…</string>
    <string name="sounds_back_to_keyboard">back to typing</string>
    <string name="sounds_tab_trending">trending</string>
    <string name="sounds_tab_favorites">favorites</string>
    <string name="sounds_tab_recents">recents</string>
    <string name="sounds_empty">search a sound to start</string>
    <string name="sounds_error_network">no connection to myinstants</string>
    <string name="sounds_retry">retry</string>
    <string name="sounds_preview_failed">cannot play this sound</string>
```

`values-fr/strings.xml` :

```xml
    <string name="search_sounds_hint">cherche un son…</string>
    <string name="sounds_back_to_keyboard">retour au clavier</string>
    <string name="sounds_tab_trending">populaires</string>
    <string name="sounds_tab_favorites">favoris</string>
    <string name="sounds_tab_recents">récents</string>
    <string name="sounds_empty">cherche un son pour commencer</string>
    <string name="sounds_error_network">pas de connexion à myinstants</string>
    <string name="sounds_retry">réessayer</string>
    <string name="sounds_preview_failed">impossible de jouer ce son</string>
```

- [ ] **Step 4: Callback + vue**

`app/src/main/java/helium314/keyboard/keyboard/sounds/SoundsCallback.kt` :

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.sounds

import helium314.keyboard.soundscore.SoundItem

interface SoundsCallback {
    fun onSendSound(item: SoundItem)
    fun onSwitchToTextKeyboard()
}
```

`app/src/main/java/helium314/keyboard/keyboard/sounds/SoundsPalettesView.kt` (squelette M1 : recherche et lecture arrivent en Task 7) :

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.sounds

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.R
import helium314.keyboard.soundscore.SoundItem

class SoundsPalettesView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    private var callback: SoundsCallback? = null
    private val adapter = SoundsAdapter()

    init {
        LayoutInflater.from(context).inflate(R.layout.sounds_palettes_view_children, this, true)
        findViewById<RecyclerView>(R.id.sounds_recycler).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SoundsPalettesView.adapter
        }
        findViewById<TextView>(R.id.sounds_back_to_keyboard).setOnClickListener {
            callback?.onSwitchToTextKeyboard()
        }
    }

    fun setCallback(cb: SoundsCallback?) { callback = cb }

    fun startSoundsPalettes() { /* search wiring comes in task 7 */ }

    fun stopSoundsPalettes() { /* player release comes in task 7 */ }

    private class SoundsAdapter : RecyclerView.Adapter<SoundsAdapter.Holder>() {
        var items: List<SoundItem> = emptyList()
        var listener: ((SoundItem) -> Unit)? = null
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_sound, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.itemView.findViewById<TextView>(R.id.sound_title).text = item.title
            holder.itemView.setOnClickListener { listener?.invoke(item) }
        }
        class Holder(v: android.view.View) : RecyclerView.ViewHolder(v)
    }
}
```

Puis, dans `main_keyboard_frame.xml`, ajouter après l'include `clipboard_history_view` (la vue custom est déclarée directement dans le layout, et gonfle elle-même `sounds_palettes_view_children` dans son `init`) :

```xml
        <helium314.keyboard.keyboard.sounds.SoundsPalettesView
            android:id="@+id/sounds_palettes_view"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:visibility="gone" />
```

- [ ] **Step 5: Commutation dans KeyboardSwitcher**

Dans `KeyboardSwitcher.java` :

1. champ (près de `mClipboardHistoryView`) :
```java
    private SoundsPalettesView mSoundsPalettesView;
```
+ import `helium314.keyboard.keyboard.sounds.SoundsPalettesView;`

2. dans `onCreateInputView`, après `mClipboardHistoryView = mCurrentInputView.findViewById(R.id.clipboard_history_view);` :
```java
        mSoundsPalettesView = mCurrentInputView.findViewById(R.id.sounds_palettes_view);
        mSoundsPalettesView.setCallback(new SoundsCallback() {
            @Override public void onSendSound(helium314.keyboard.soundscore.SoundItem item) {
                // task 8: SoundDownloader.downloadAndShare(mLatinIME, item);
            }
            @Override public void onSwitchToTextKeyboard() { setAlphabetKeyboard(); }
        });
```

3. méthode (à côté de `setClipboardKeyboard()`) :
```java
    public void setSoundsKeyboard() {
        if (DEBUG_ACTION) {
            Log.d(TAG, "setSoundsKeyboard");
        }
        mMainKeyboardFrame.setVisibility(View.VISIBLE);
        mKeyboardView.setVisibility(View.GONE);
        mSuggestionStripView.setVisibility(View.GONE);
        mStripContainer.setVisibility(View.GONE);
        mEmojiTabStripView.setVisibility(View.GONE);
        mClipboardStripScrollView.setVisibility(View.GONE);
        mEmojiPalettesView.setVisibility(View.GONE);
        mClipboardHistoryView.setVisibility(View.GONE);
        mSoundsPalettesView.startSoundsPalettes();
        mSoundsPalettesView.setVisibility(View.VISIBLE);
    }

    public void toggleSoundsPanel() {
        if (isShowingSoundsPalettes()) {
            setAlphabetKeyboard();
        } else {
            setSoundsKeyboard();
        }
    }

    public boolean isShowingSoundsPalettes() {
        return mSoundsPalettesView != null && mSoundsPalettesView.isShown();
    }
```

4. dans `setMainKeyboardFrame`, après `mClipboardHistoryView.stopClipboardHistory();` :
```java
        mSoundsPalettesView.setVisibility(View.GONE);
        mSoundsPalettesView.stopSoundsPalettes();
```

5. dans `getVisibleKeyboardView`, avant le `return mKeyboardView;` :
```java
        } else if (isShowingSoundsPalettes()) {
            return mSoundsPalettesView;
```

6. dans `deallocateMemory`, après le bloc clipboard :
```java
        if (mSoundsPalettesView != null) {
            mSoundsPalettesView.stopSoundsPalettes();
        }
```

7. dans `onToggleKeyboard`, branche `else`, après les stops clipboard :
```java
                mSoundsPalettesView.stopSoundsPalettes();
                mSoundsPalettesView.setVisibility(View.GONE);
```

- [ ] **Step 6: Routage du code**

Dans `KeyboardActionListenerImpl.kt`, dans le `when (primaryCode)` en tête de `onCodeInput` :

```kotlin
            KeyCode.SOUNDS -> {
                keyboardSwitcher.toggleSoundsPanel()
                return
            }
```

- [ ] **Step 7: Commit + push + CI + install (M1)**

```bash
git add -A && git commit -m "feat: panneau sons squelette (M1) — bouton toolbar, ouverture/fermeture"
git push
```

CI vert → user installe la nouvelle APK depuis la Release `dev` → **test manuel** : dans un champ texte, le bouton 🎵 de la toolbar ouvre un panneau vide avec la barre de recherche et les onglets ; le bouton retour ou le bouton clavier ramène à la frappe ; l'emoji et le clipboard marchent toujours.

---

### Task 7: Recherche + aperçu audio (M2)

**Files:**
- Modify: `app/src/main/java/helium314/keyboard/keyboard/sounds/SoundsPalettesView.kt` (câblage complet)
- Modify: `app/src/main/AndroidManifest.xml` (permission INTERNET)

**Interfaces:**
- Consumes: `MyInstantsSource.search/trending` (Task 3), layout du panneau (Task 6).
- Produces: panneau fonctionnel — recherche (débounce 400 ms), onglet populaires, grille de résultats, lecture d'aperçu en streaming (un seul MediaPlayer, un nouveau ▶ coupe le précédent).

- [ ] **Step 1: Permission INTERNET**

Dans `AndroidManifest.xml`, juste après la ligne `<manifest ...>` (avec les autres `uses-permission` s'il y en a) :

```xml
    <uses-permission android:name="android.permission.INTERNET" />
```

- [ ] **Step 2: Câbler la vue**

Compléter `SoundsPalettesView.kt` :

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.sounds

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.R
import helium314.keyboard.soundscore.MyInstantsSource
import helium314.keyboard.soundscore.SoundItem

class SoundsPalettesView(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val source = MyInstantsSource()
    private var callback: SoundsCallback? = null
    private val adapter = SoundsAdapter()
    private var player: MediaPlayer? = null
    private var searchRunnable: Runnable? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.sounds_palettes_view_children, this, true)
        findViewById<RecyclerView>(R.id.sounds_recycler).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SoundsPalettesView.adapter
        }
        findViewById<TextView>(R.id.sounds_back_to_keyboard).setOnClickListener {
            callback?.onSwitchToTextKeyboard()
        }
        adapter.onPlay = { item -> playPreview(item) }
        val edit = findViewById<EditText>(R.id.sounds_search_edit)
        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { runSearch(edit.text.toString()); true } else false
        }
        edit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                searchRunnable?.let(mainHandler::removeCallbacks)
                val q = s?.toString()?.trim() ?: ""
                if (q.length < 2) return
                searchRunnable = Runnable { runSearch(q) }.also { mainHandler.postDelayed(it, 400) }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        })
        // onglets : populaires affiche trending, favoris/récents remplis en task 9
        findViewById<TextView>(R.id.sounds_tab_trending).setOnClickListener {
            loadInBackground { source.trending() }
        }
    }

    fun setCallback(cb: SoundsCallback?) { callback = cb }

    fun startSoundsPalettes() { loadInBackground { source.trending() } }

    fun stopSoundsPalettes() {
        stopPreview()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun runSearch(query: String) = loadInBackground { source.search(query) }

    private fun loadInBackground(load: () -> List<SoundItem>) {
        showStatus(context.getString(R.string.sounds_empty)) // sera remplacé par un spinner plus tard
        Thread {
            val items = try { load() } catch (e: Exception) {
                mainHandler.post { showStatus(context.getString(R.string.sounds_error_network)) }
                return@Thread
            }
            mainHandler.post {
                findViewById<RecyclerView>(R.id.sounds_recycler).visibility = if (items.isEmpty()) GONE else VISIBLE
                findViewById<TextView>(R.id.sounds_status_view).visibility = if (items.isEmpty()) VISIBLE else GONE
                adapter.items = items
                adapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun showStatus(text: String) {
        findViewById<RecyclerView>(R.id.sounds_recycler).visibility = GONE
        findViewById<TextView>(R.id.sounds_status_view).apply { visibility = VISIBLE; this.text = text }
    }

    private fun playPreview(item: SoundItem) {
        stopPreview()
        Thread {
            try {
                val p = MediaPlayer()
                p.setAudioAttributes(
                    AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
                )
                p.setDataSource(item.mediaUrl)
                p.setOnCompletionListener { mainHandler.post { stopPreview() } }
                p.prepare() // blocking, background thread
                player = p
                p.start()
            } catch (e: Exception) {
                player = null
                mainHandler.post {
                    android.widget.Toast.makeText(context, R.string.sounds_preview_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun stopPreview() {
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
    }

    private class SoundsAdapter : RecyclerView.Adapter<SoundsAdapter.Holder>() {
        var items: List<SoundItem> = emptyList()
        var onPlay: ((SoundItem) -> Unit)? = null   // ▶ branché en task 7
        var onSend: ((SoundItem) -> Unit)? = null   // ➤ branché en task 8
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_sound, parent, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            val view = holder.itemView
            view.findViewById<TextView>(R.id.sound_title).text = item.title
            view.findViewById<TextView>(R.id.sound_play).setOnClickListener { onPlay?.invoke(item) }
            view.findViewById<TextView>(R.id.sound_send).setOnClickListener { onSend?.invoke(item) }
        }
        class Holder(v: android.view.View) : RecyclerView.ViewHolder(v)
    }
}
```

La cellule ➤ reste inerte jusqu'à la Task 8 (son `onSend` n'est pas encore câblé).

- [ ] **Step 3: Commit + push + CI + install (M2)**

```bash
git add -A && git commit -m "feat: recherche myinstants + aperçu audio dans le panneau (M2)"
git push
```

**Test manuel user (M2)** : panneau → taper « bruh » → résultats s'affichent → ▶ joue le son → ▶ sur un autre coupe le premier. Si le focus de l'EditText interne pose problème sur l'appareil (le texte tape dans la conversation au lieu de la recherche) : plan B documenté = ouvrir la recherche dans une Activity dédiée (modèle `EmojiSearchActivity.kt` de HeliBoard) — décision à prendre à ce moment, pas avant.

---

### Task 8: Envoi du son (M3) — téléchargement + FileProvider + partage

**Files:**
- Create: `app/src/main/java/helium314/keyboard/keyboard/sounds/SoundDownloader.kt`
- Create: `app/src/main/res/xml/sounds_file_paths.xml`
- Modify: `app/src/main/AndroidManifest.xml` (provider)
- Modify: `app/src/main/java/helium314/keyboard/keyboard/KeyboardSwitcher.java` (callback → downloader)

**Interfaces:**
- Consumes: `SoundsCallback.onSendSound` (Task 6), `SoundItem` (Task 3).
- Produces: `object SoundDownloader { fun downloadAndShare(context: android.content.Context, item: SoundItem) }` — télécharge le mp3 (thread), puis ouvre le chooser `ACTION_SEND audio/*`.

- [ ] **Step 1: FileProvider**

`app/src/main/res/xml/sounds_file_paths.xml` :

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- SPDX-License-Identifier: GPL-3.0-only -->
<paths>
    <cache-path name="sounds" path="sounds/" />
</paths>
```

Dans `AndroidManifest.xml`, dans `<application>` (avant `</application>`) :

```xml
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.sounds.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/sounds_file_paths" />
        </provider>
```

- [ ] **Step 2: SoundDownloader**

`app/src/main/java/helium314/keyboard/keyboard/sounds/SoundDownloader.kt` :

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.sounds

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import helium314.keyboard.latin.R
import helium314.keyboard.soundscore.SoundItem
import org.jsoup.Jsoup
import java.io.File

object SoundDownloader {
    fun downloadAndShare(context: Context, item: SoundItem) {
        Toast.makeText(context, R.string.sounds_sending, Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val bytes = Jsoup.connect(item.mediaUrl)
                    .ignoreContentType(true)
                    .maxBodySize(0)
                    .execute()
                    .bodyAsBytes()
                val dir = File(context.cacheDir, "sounds").apply { mkdirs() }
                val file = File(dir, sanitize(item.title) + ".mp3")
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.sounds.fileprovider", file)
                val share = Intent(Intent.ACTION_SEND)
                    .setType("audio/*")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(
                    Intent.createChooser(share, item.title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                main {
                    Toast.makeText(context, R.string.sounds_send_failed, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun sanitize(title: String) =
        title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").take(60).ifBlank { "son" }

    private fun main(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }
}
```

Strings à ajouter : `sounds_sending` (« sending… » / « envoi… ») et `sounds_send_failed` (« download failed » / « échec du téléchargement ») dans `values/` et `values-fr/`.

- [ ] **Step 3: Brancher le callback**

Dans `KeyboardSwitcher.java`, dans le callback `SoundsCallback` de `onCreateInputView`, remplacer le corps de `onSendSound` :

```java
            @Override public void onSendSound(helium314.keyboard.soundscore.SoundItem item) {
                SoundDownloader.downloadAndShare(mLatinIME, item);
            }
```

+ import `helium314.keyboard.keyboard.sounds.SoundDownloader;`

Dans `SoundsPalettesView.onBindViewHolder`, brancher ➤ :

```kotlin
            view.findViewById<TextView>(R.id.sound_send).setOnClickListener { sendListener?.invoke(item) }
```

avec `var sendListener: ((SoundItem) -> Unit)? = null` dans l'adapter, câblé dans `setCallback` de la vue : `adapter.sendListener = { callback?.onSendSound(it) }`.

- [ ] **Step 4: Commit + push + CI + install (M3 — LA VISION)**

```bash
git add -A && git commit -m "feat: envoi du son — téléchargement + FileProvider + ACTION_SEND (M3)"
git push
```

**Test manuel user (M3)** : WhatsApp → conversation → bouton 🎵 → chercher « bruh » → ➤ → feuille de partage → choisir le contact → le pote reçoit un audio jouable.

---

### Task 9: Favoris, récents, populaires + erreurs (M4)

**Files:**
- Modify: `app/src/main/java/helium314/keyboard/keyboard/sounds/SoundsPalettesView.kt`

**Interfaces:**
- Consumes: `SoundStore` (Task 4), tout le reste.
- Produces: onglets fonctionnels (populaires / favoris / récents), ⭐ qui ajoute/retire des favoris (☆↔★), récents mis à jour à l'envoi, bouton « réessayer » en cas d'erreur réseau.

- [ ] **Step 1: Câbler le store et les onglets**

Dans `SoundsPalettesView` :

```kotlin
    private val store by lazy {
        SoundStore(java.io.File(context.filesDir, "sounds_store.properties"))
    }
    private var currentTab = TAB_TRENDING

    private companion object { const val TAB_TRENDING = 0; const val TAB_FAVORITES = 1; const val TAB_RECENTS = 2 }
```

`init` : brancher les 3 onglets :

```kotlin
        findViewById<TextView>(R.id.sounds_tab_trending).setOnClickListener { switchTab(TAB_TRENDING) }
        findViewById<TextView>(R.id.sounds_tab_favorites).setOnClickListener { switchTab(TAB_FAVORITES) }
        findViewById<TextView>(R.id.sounds_tab_recents).setOnClickListener { switchTab(TAB_RECENTS) }
```

Méthodes :

```kotlin
    private fun switchTab(tab: Int) {
        currentTab = tab
        when (tab) {
            TAB_TRENDING -> loadInBackground { source.trending() }
            TAB_FAVORITES -> showList(store.favorites())
            TAB_RECENTS -> showList(store.recents())
        }
    }

    private fun showList(items: List<SoundItem>) {
        if (items.isEmpty()) { showStatus(context.getString(R.string.sounds_empty)); return }
        findViewById<RecyclerView>(R.id.sounds_recycler).visibility = VISIBLE
        findViewById<TextView>(R.id.sounds_status_view).visibility = GONE
        adapter.items = items
        adapter.notifyDataSetChanged()
    }
```

- [ ] **Step 2: ⭐ favori + récents à l'envoi + retry**

Dans l'adapter, ajouter `var favoriteListener: ((SoundItem) -> Unit)? = null` et dans `onBindViewHolder` :

```kotlin
            val fav = view.findViewById<TextView>(R.id.sound_favorite)
            fav.text = if (isFavorite(item)) "★" else "☆"
            fav.setOnClickListener { favoriteListener?.invoke(item) }
```

(ajouter `var isFavorite: (SoundItem) -> Boolean = { false }` à l'adapter aussi).

Dans la vue : `adapter.favoriteListener = { item -> store.toggleFavorite(item); adapter.notifyDataSetChanged() }` ;
dans le callback d'envoi (`adapter.sendListener = { ... }`), ajouter `store.addRecent(it)` avant `callback?.onSendSound(it)` ;
pour le retry : quand `loadInBackground` attrape l'erreur, afficher `sounds_error_network` + rendre `sounds_status_view` cliquable pour relancer `switchTab(currentTab)` (texte du statut devient alors « erreur — tape ici pour réessayer », string `sounds_error_network`).

- [ ] **Step 3: Commit + push + CI + install (M4) + vérification finale**

```bash
git add -A && git commit -m "feat: favoris, récents, onglets populaires + gestion d'erreurs (M4)"
git push
```

**Test manuel final (checklist de la vision)** :
1. Frappe normale + suggestions FR intactes.
2. 🎵 → populaires affichent des sons sans recherche.
3. Recherche « bruh » → ▶ aperçu.
4. ☆ → l'onglet favoris contient le son ; re-tap ☆ → retiré.
5. ➤ → partage → contact → le destinataire entend le son.
6. Le son apparaît dans « récents ».
7. Avion activé → message d'erreur + réessayer après retour du réseau.
8. Aucun crash après rotation / fermeture-ouverture du clavier.

---

## Notes d'exécution

- **Ordre strict** : les tasks 1→9 se construisent l'une sur l'autre ; ne pas sauter M0 (le CI est la fondation de tout).
- **Itération** : pour itérer sans polluer `main`, pousser sur la branche `wip` (le workflow se déclenche aussi dessus), puis merger dans `main` quand le job est vert.
- **Boucle de test locale** : seuls les tasks 3 et 4 ont une boucle TDD locale rapide (`./gradlew -p sounds-core test`). Les tasks 5–9 se vérifient par compilation CI + test téléphone : regrouper plusieurs edits avant chaque push.
- **Plan B recherche (si l'EditText interne dérobe le focus à la conversation)** : reproduire le modèle `EmojiSearchActivity.kt` (activity plein écran avec champ de recherche qui partage/renvoie le son puis `finish()`). C'est le seul point technique non garanti du plan ; tout le reste suit des mécanismes déjà présents dans HeliBoard.
- **Mise à jour sur le téléphone** : incrémenter `versionCode` dans `app/build.gradle.kts` avant de pousser une version destinée à remplacer une APK déjà installée.
