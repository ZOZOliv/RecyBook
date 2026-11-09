# pibooth pour Android (Java)

Portage Android du photomaton [pibooth](https://github.com/pibooth/pibooth)
(Python / pygame / gPhoto2, Linux), pour tablette, **avec pilotage d'un
reflex ou d'un hybride en USB**. Écrit en **Java** (pas de Kotlin dans le
code de l'application) avec le build Gradle en Groovy.

## Ce qui est porté

| pibooth (Linux)                         | pibooth Android                                              |
|-----------------------------------------|--------------------------------------------------------------|
| `booth.py` + `states.py` (machine à états) | `MainActivity` : wait → choose → chosen → preview → capture → processing → print → finish, + failsafe |
| `camera/gphoto.py` (libgphoto2)         | `camera/DslrCamera` + `ptp/*` : pilote PTP/USB écrit en Java (API USB Host d'Android) |
| `camera/opencv.py` / `rpi.py`           | `camera/TabletCamera` (CameraX, caméra avant ou arrière)      |
| `camera/hybrid.py`                      | `camera/HybridCamera` : aperçu tablette + prise reflex        |
| `pictures/factory.py` (PIL / OpenCV)    | `picture/PictureFactory` (Canvas) : mêmes mises en page 1 à 4 photos, marges, textes, fond, calque PNG, recadrage, orientation auto |
| `printer.py` (CUPS)                     | `printer/BoothPrinter` : IPP direct (sans dialogue) ou service d'impression Android |
| `counters.py`                           | `Counters` (taken / printed / forgotten / remaining_duplicates) |
| `config/parser.py` + menu               | `BoothConfig` + écran `SettingsActivity` (mêmes options, mêmes noms) |
| `language.py`                           | `res/values` (anglais) et `res/values-fr` (français)          |
| Boutons GPIO                            | écran tactile, clavier / télécommande USB ou Bluetooth (Espace/Entrée/Vol+ = photo, P/Vol− = impression) |
| Polices et pictos                       | repris tels quels (`assets/fonts`, `assets/images`)           |

Les photos sont enregistrées comme dans pibooth, dans la galerie :
`Images/pibooth/AAAA-MM-JJ-hh-mm-ss_pibooth.jpg`, les prises brutes dans
`Images/pibooth/raw/<date>/` et les photos « oubliées » dans `Images/pibooth/forget/`.

## Reflex / hybride en USB

Pas de libgphoto2 : l'application parle directement le protocole **PTP**
(ISO 15740) sur USB, via l'API USB Host d'Android.

| Marque              | Visée en direct | Déclenchement                          | État |
|---------------------|-----------------|----------------------------------------|------|
| Canon EOS (reflex, R) | oui (`GetViewFinderData`) | `RemoteReleaseOn/Off` avec AF, repli sans AF | testé sur simulateur |
| Nikon D / Z         | oui (`StartLiveView` / `GetLiveViewImg`) | `InitiateCaptureRecInMedia` ou `Capture` | testé sur simulateur |
| Autres PTP (Fuji, Olympus, Panasonic, Pentax, Sony en mode PC distant…) | non → aperçu par la tablette (mode hybride automatique) | `InitiateCapture` standard | dépend du boîtier |

- Si l'appareil enregistre en RAW+JPEG, seul le JPEG est téléchargé.
- Option Canon « prise de vue en RAM » : pas besoin de carte SD.
- Option « effacer les photos de l'appareil après transfert » (`delete_internal_memory`).
- L'orientation EXIF est respectée ; rotation et miroir réglables.
- Au branchement d'un appareil photo, Android propose d'ouvrir pibooth (et
  accorde l'accès USB). Débranché en cours de séquence → écran « Oups », puis
  retour à la caméra de la tablette en mode automatique.

### Matériel

- Une tablette Android 8.0+ avec **USB OTG** (mode hôte).
- Un câble ou adaptateur OTG vers le câble USB du boîtier.
- Pour une soirée entière : un **hub USB-C alimenté / adaptateur OTG avec charge**,
  sinon la tablette se décharge (et certains boîtiers tirent du courant).
- Régler l'appareil en mode **PTP** (et non MTP / stockage de masse) s'il le
  propose ; Sony : « PC distant ». Désactiver la mise en veille automatique du boîtier.
- Mettre l'objectif en AF (ou désactiver l'option autofocus si la mise au point échoue en faible lumière).

## Impression

- **Réseau (IPP, recommandé)** : impression sans boîte de dialogue, comme CUPS
  sur pibooth. Adresse du type `ipp://192.168.1.20:631/ipp/print`
  (imprimante AirPrint / IPP Everywhere / Mopria) ou
  `ipp://serveur:631/printers/MonImprimante` (imprimante partagée par un CUPS,
  par exemple une Canon Selphy branchée sur un Raspberry Pi).
  Support optionnel : `na_index-4x6_4x6in` pour du 10×15.
- **Service d'impression Android** : passe par la boîte de dialogue système
  (pas adapté à une borne en libre-service, mais fonctionne avec n'importe quelle imprimante installée).
- Options reprises : `printer_delay`, `auto_print`, `max_pages`, `max_duplicates`, `pictures_per_page`.

## Construire l'APK

Prérequis : Android Studio (Koala ou plus récent) ou le SDK Android + JDK 17.

```bash
cd pibooth-android
./gradlew assembleDebug          # APK : app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Ou ouvrir le dossier `pibooth-android` dans Android Studio et lancer ▶.

## Utilisation en borne

- Écran plein écran, toujours allumé, orientation paysage.
- **Réglages** : appui long sur l'engrenage (coin haut droit) ou touche Échap.
  Un code peut être exigé (Réglages → Général → Code des réglages).
- Le bouton Retour ne quitte pas l'application (Réglages → Quitter pibooth).
- Pour verrouiller la tablette sur l'application : Paramètres Android →
  Sécurité → **Épinglage d'écran**, puis épingler pibooth.

## Différences avec pibooth

- Pas de système de plugins (pluggy) : les fonctions « core » sont intégrées.
- Effets : `none`, `grayscale`, `sepia`, `vintage`, `invert` (les filtres PIL
  `blur`, `contour`, … n'existent pas tels quels sous Android).
- Pas d'animation de la photo précédente (`animate`).
- Langue : celle de la tablette (français ou anglais).
- Les couleurs acceptent `#RRGGBB` ou le format pibooth `(r, g, b)`.

## Licence

pibooth est sous licence MIT (© Vincent Verdeil et Antoine Rousseaux) ; le
texte est repris dans `app/src/main/assets/PIBOOTH_LICENSE.txt`. Polices :
Amatic, Dancing Script et Monoid sous OFL (textes joints dans `app/src/main/assets/fonts`), Roboto sous Apache 2.0.
