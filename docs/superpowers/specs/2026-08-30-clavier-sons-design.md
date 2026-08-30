# Design — Clavier Android avec panneau de sons myinstants

**Date :** 2026-08-30
**Statut :** Validé par le user (approche A + build GitHub Actions)
**Projet :** perso / entre potes, install en sideload APK. Pas de publication Play Store.

## 1. Vision

Réagir dans une conversation avec un **son** comme on réagit avec un GIF, depuis le clavier :

> Je suis dans WhatsApp → j'appuie sur le bouton 🔊 de mon clavier → je cherche "bruh" →
> j'écoute l'aperçu → j'appuie sur envoyer → la feuille de partage Android s'ouvre avec
> le fichier audio → je tape la discussion de mon pote → il reçoit un audio jouable.

### Contraintes acceptées (limites d'Android, pas du projet)

- **Pas d'insertion directe de l'audio dans le champ de message.** L'API `commitContent`
  (utilisée par les claviers GIF) n'accepte que des images ; aucune app de chat n'accepte
  un `audio/*` injecté par un clavier. L'envoi passe donc par la feuille de partage Android.
- **Pas de pré-ciblage de la conversation actuelle.** WhatsApp n'expose pas d'API pour ça ;
  l'utilisateur re-sélectionne son contact dans la feuille de partage (1 tap, contact en récents).
- Glide typing : possible plus tard via la lib optionnelle "swypelibs" (fermée) de HeliBoard.

### Hors scope

- Publication Play Store, licence/droit d'auteur des sons (usage perso, distribution entre potes).
- iOS. Version anglaise de l'UI (français uniquement pour l'instant).
- Claviers concurrents, thèmes supplémentaires, autre source de sons que myinstants
  (l'interface `SoundSource` existe pour ça le jour venu, mais une seule implémentation).

## 2. Décisions structurantes

| Décision | Choix | Raison |
|---|---|---|
| Base du clavier | Fork de **HeliBoard** (GPL-3.0) | Fork maintenu d'OpenBoard/AOSP, le seul clavier open-source proche de l'expérience SwiftKey. La frappe reste intacte. |
| Mécanisme d'envoi | Fichier audio via `ACTION_SEND` | Seule façon de faire entendre le son au destinataire dans le chat. |
| Source des sons | **myinstants.com**, scraping HTML (Jsoup) derrière l'interface `SoundSource` | Pas d'API officielle. Le scrape est le risque n°1 → isolé dans une seule classe + testé contre fixtures. |
| Aperçu audio | `MediaPlayer` en streaming direct sur l'URL mp3 | Pas de téléchargement complet juste pour écouter. |
| Build | **GitHub Actions** (cloud) | User refuse Android Studio / SDK local. 0 Go sur le PC (juste git). APK récupéré dans le navigateur du téléphone. |
| Tests | Unitaires sur parser + stockage ; le reste à la main sur le téléphone | L'UX d'un clavier ne se teste pas en unitaire. |
| Stockage favoris/récents | Fichier JSON local (`SoundStore`) | YAGNI : pas besoin de Room pour deux listes. |

## 3. Architecture

Tout le nouveau code vit dans un seul paquet ; HeliBoard n'est touché qu'en deux points
d'insertion minimaux.

```
clavier/                                  ← repo = fork HeliBoard
└── app/src/main/java/helium314/keyboard/
    ├── keyboard/emoji/                   ← (existant) modèle de notre panneau
    ├── keyboard/sounds/                  ← ★ NOUVEAU
    │   ├── SoundsPalettesView.kt         ← panneau UI : recherche + onglets + grille
    │   ├── SoundSource.kt                ← interface : search(query), trending(), mediaUrl
    │   ├── MyInstantsSource.kt           ← implémentation Jsoup sur myinstants.com
    │   ├── SoundItem.kt                  ← data : titre, url média, page
    │   ├── SoundStore.kt                 ← favoris + récents (JSON dans le stockage app)
    │   └── SoundDownloader.kt            ← téléchargement mp3 + URI FileProvider
    ├── latin/InputView.java              ← (MODIF 1) enregistrer SoundsPalettesView à côté
    │                                        d'emoji/clipboard dans le switch de panneaux
    ├── latin/LatinIME.java               ← (MODIF 2) router l'action "envoyer" du panneau
    └── res/values-fr/strings.xml         ← libellés FR du panneau
```

### Composants

- **SoundsPalettesView** — vue du panneau (même patron que `EmojiPalettesView`) :
  barre de recherche, 3 onglets (Populaire / Favoris / Récents), grille de cellules.
  Une cellule = titre du son + bouton ▶ (aperçu) + bouton ➤ (envoyer) + ⭐ (favori).
  Un seul aperçu à la fois : un nouveau ▶ coupe le précédent.
- **SoundSource / MyInstantsSource** — `search(query)` interroge
  `https://www.myinstants.com/fr/search/?name=<q>` et parse le HTML (Jsoup) ;
  `trending()` parse la page d'accueil. Retourne `List<SoundItem>`.
- **SoundDownloader** — télécharge le mp3 (OkHttp) dans `cacheDir/sounds/`,
  expose une URI `content://` via `FileProvider`, et construit l'`Intent`
  `ACTION_SEND` (`type: audio/*`, `EXTRA_STREAM`).
- **SoundStore** — favoris + récents (20 max) persistés en JSON via `SharedPreferences`.

### Dépendances ajoutées

- `jsoup` (parsing HTML, pur Java)
- `okhttp` (téléchargement mp3)

Rien d'autre. La stack de frappe de HeliBoard est intacte.

### Modifications du manifest

- Ajout de la permission **INTERNET** (HeliBoard est 100 % offline aujourd'hui — seule
  modification des permissions).
- Déclaration du `FileProvider` avec `cacheDir/sounds/` en partage.

### Configuration build & signature

- Keystore de signature **commité dans le repo** (projet perso, aucune valeur de sécurité).
  Raison : le keystore debug de GitHub Actions change d'un runner à l'autre, ce qui
  obligerait à désinstaller/réinstaller l'app. Un keystore commité garantit une
  signature stable → mise à jour de l'APK par simple installation par-dessus.

## 4. UX du panneau

```
┌─────────────────────────────────┐
│ [🔍 recherche un son...    ] ✕  │
│ [Populaire] [Favoris] [Récents] │
│ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ │
│ │bruh │ │bonk │ │wow  │ │ ⟳   │ │
│ │ ▶ ➤ │ │ ▶ ➤ │ │ ▶ ➤ │ │     │ │
│ └─────┘ └─────┘ └─────┘ └─────┘ │
│  ▶ aperçu   ➤ envoyer   ⭐ favori │
└─────────────────────────────────┘
```

1. Bouton 🔊 dans la toolbar du clavier (à côté du bouton emoji) → ouvre le panneau.
2. Une touche du clavier, le bouton clavier, ou ✕ → retour à la frappe (le panneau
   conserve son état : recherche en cours, onglet actif).
3. ▶ = aperçu en streaming. ➤ = téléchargement puis feuille de partage Android.
4. Après le partage, le retour sur l'app remet le clavier tel quel (panneau encore ouvert).
5. Bouton clavier présent dans le panneau pour re-taper du texte sans fermer le panneau.

## 5. Gestion d'erreurs

| Cas | Comportement |
|---|---|
| Pas de réseau / timeout | Message inline dans le panneau + bouton « Réessayer » |
| myinstants change son HTML | Le parser casse → message d'erreur inline. Réparation = 1 classe (`MyInstantsSource`) + le test unitaire (fixture) échoue avant l'utilisateur |
| Son qui refuse de jouer | Toast discret, le panneau reste utilisable |
| Échec téléchargement à l'envoi | Toast + le bouton ➤ redevient actif (réessayer) |
| Lien mort (fichier disparu du site) | Toast « Son indisponible » |

Rien ne fait planter le clavier : toute erreur reste confinée dans le panneau.

## 6. Build & distribution (GitHub Actions)

- **Repo GitHub public** (code GPL-3.0 de toute façon, minutes Actions illimitées).
- Workflow `.github/workflows/build.yml` : à chaque push sur `main` → JDK 17 →
  `./gradlew assembleRelease` (signé avec le keystore commité) → l'APK est joint à une
  **Release GitHub continue** (tag `dev`, écrasée à chaque build). Raison : télécharger
  un *artifact* d'Actions exige un compte GitHub ; une Release est téléchargeable depuis
  n'importe quel navigateur, sans login — c'est le chemin téléphone → APK.
- Côté PC : `git` uniquement. Aucun SDK, aucun Studio, aucun émulateur.
- Côté téléphone : télécharger l'APK depuis github.com → Actions → artifact →
  installer (autoriser « sources inconnues », une seule fois).
- Coût : ~15 min d'attente par version → on groupe les changements par lots.

## 7. Tests

- **Unitaires** (intégrés au module de test HeliBoard existant) :
  - `MyInstantsSource` : parse de 2 fixtures HTML sauvegardées (page de recherche,
    page d'accueil) → vérifie titres + URLs. Zéro réseau dans les tests.
  - `SoundStore` : ajout/suppression favori, limite des récents, persistance.
- **Manuel sur téléphone** : parcours complet de la vision (rechercher → écouter →
  envoyer → le pote entend le son), retour au clavier, rotation, app sans réseau.

## 8. Jalons

| Jalon | Contenu | Résultat visible |
|---|---|---|
| **M0** | Repo GitHub + workflow Actions + APK HeliBoard *tel quel* installé sur le téléphone | Un vrai clavier complet sur le tel, build cloud fonctionnel |
| **M1** | Panneau vide branché : bouton 🔊 → panneau s'ouvre/se ferme | Le squelette de la fonctionnalité |
| **M2** | Recherche + grille + aperçu ▶ | Ça cherche et ça joue |
| **M3** | Envoi : téléchargement + ACTION_SEND → WhatsApp | **La vision complète** |
| **M4** | Favoris ⭐, Récents, onglet Populaire, finitions | Confort d'usage quotidien |

## 9. Risques

1. **HTML myinstants change** → isolé dans `MyInstantsSource`, détecté par test fixture.
2. **Build CI (NDK/C++ de HeliBoard)** → réglé en premier (M0) avant tout code nouveau.
3. **Attente de 15 min par itération** → lots de changements, jalons bien découpés.
4. **HeliBoard évolue** (rebase difficile) → projet perso : pas de rebase prévu, on garde notre fork.

---

## Addendum (2026-08-30, post-device-test) — architecture finale

Le test réel a fait évoluer deux choix de la spec initiale :

1. **Recherche dans le clavier (pas d'activity, pas d'EditText).** L'approche « activity plein
écran » (inspirée d'EmojiSearchActivity) a été rejetée au test : le clavier disparaît et la
fenêtre IME n'a pas de focus système. Architecture finale, style GIF-keyboard : le panneau
sons est un enfant du LinearLayout externe de `main_keyboard_frame`, AU-DESSUS du clavier qui
reste visible. La saisie est routée par le pipeline du clavier lui-même :
`KeyboardActionListenerImpl.onCodeInput` intercepte les codes positifs (≠ CODE_ENTER) et
`onTextInput` quand le panneau est affiché, et alimente une barre de requête NON-focusable
(`appendSearchChar`/`backspaceSearch`), recherche debouncée 350 ms. `LatinIME.onComputeInset`
déduit la hauteur du panneau pour garder la conversation visible. La frappe normale est
octet-pour-octet inchangée quand le panneau est fermé (garde `isShowingSoundsPalettes()`).

2. **URLs myinstants.** Le site a supprimé ses pages `/fr/` : `trending()` passe par la racine
`https://www.myinstants.com/` (redirection géo suivie par Jsoup) ; `search()` garde
`/fr/search/?name=` (toujours valide). Les erreurs réseau sont affichées avec leur motif
exact (classe+message) + hint « réessayer ».

3. **Affichage.** Résultats en grille 3 colonnes (tuiles titre + ▶ ➤ ⭐), via GridLayoutManager.

Autres correctifs issus du cycle de retours : interop `@JvmStatic` (Kotlin object ↔ Java),
`SoundStore` en singleton process-wide (`SoundStores`), génération tokens `@Volatile`,
toasts via `KeyboardSwitcher.showToast` (fiabilité API 33+), plafond de téléchargement 15 Mo
et `catch Throwable`, keystore commité + Release GitHub `dev` (build CI unique).
