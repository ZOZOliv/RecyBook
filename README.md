# 📚 RecyBook

Application web de gestion de livres, avec **remplissage automatique de la base à partir d'une vidéo** de votre armoire / bibliothèque.

- **Client** : Angular 22 (composants autonomes, signals, sans zone.js)
- **Serveur** : Express 5 en TypeScript, base SQLite via le module `node:sqlite` intégré à Node (aucune dépendance native)
- **Reconnaissance des livres** : Claude (`claude-opus-5-5`, vision) via le SDK `@anthropic-ai/sdk`

## Installation

Prérequis : Node.js 22.13 ou plus récent, `ffmpeg` (pour l'import vidéo), une clé API Anthropic.

```bash
npm install          # installe aussi server/ et client/
npm run seed         # charge les 37 livres identifiés dans la vidéo de l'armoire
export ANTHROPIC_API_KEY=sk-ant-...   # nécessaire seulement pour importer de nouvelles vidéos
```

### Développement

```bash
npm run dev
```

Lance le serveur Express sur <http://localhost:3000> (rechargé à chaque modification) et le serveur de
développement Angular sur <http://localhost:4200>, qui redirige `/api` et `/frames` vers Express.
Ouvrez <http://localhost:4200>.

### Production

```bash
npm run build        # compile Angular dans client/dist/
npm start            # Express sert l'API et l'application sur http://localhost:3000
```

Variables d'environnement : `PORT` (défaut 3000), `RECYBOOK_DATA` (dossier des données, défaut `data/`),
`ANTHROPIC_API_KEY`.

## Fonctionnement de l'import vidéo

1. `ffmpeg` extrait une image toutes les 1,5 s (réglable) et supprime les images quasi identiques.
2. Les images sont envoyées par lots de 6 à Claude, qui lit les tranches et couvertures et renvoie pour
   chaque livre : titre, auteur, éditeur, année, ISBN, genre, langue, étagère, et sa confiance dans la lecture
   (sortie JSON structurée).
3. Les doublons (même livre vu sur plusieurs images, ou déjà en base) sont éliminés.
4. Les livres sont ajoutés avec le statut **« à vérifier »**, avec l'image source. Vous pouvez les corriger
   puis les valider dans l'interface.

Depuis l'interface (onglet « Importer une vidéo ») ou en ligne de commande :

```bash
npm run import-video -- ma_video.mp4 --shelf-hint "Armoire salon"
```

Si une requête est refusée par les filtres de sécurité de Claude, elle est automatiquement relancée sur un
autre modèle (`fallbacks: "default"`).

## Livres de la vidéo de l'armoire

Les 37 livres reconnus dans la vidéo `PXL_20260731_114114534.TS.mp4` (armoire vitrée, 2 étagères) sont
fournis dans `seed/armoire_video.json`, avec les images sources dans `seed/frames/`. `npm run seed` les charge
sans créer de doublons. Ils arrivent avec le statut « à vérifier » : relisez en priorité ceux dont la note
signale une tranche peu lisible.

## Fonctionnalités

- Recherche (titre, auteur, ISBN, notes), filtres par statut / étagère / genre, tri
- Ajout, modification, suppression ; fiche avec l'image de la vidéo où le livre apparaît
- Case « Lu », validation livre par livre ou en une fois
- Export CSV (compatible Excel)

## Structure

```
client/                 application Angular
  src/app/library/      bibliothèque (liste, filtres, statistiques)
  src/app/book-dialog/  fiche livre
  src/app/import/       envoi d'une vidéo et suivi de l'analyse
  src/app/api.service.ts
server/                 serveur Express
  src/index.ts          API REST + service de l'application compilée
  src/db.ts             schéma SQLite et dédoublonnage
  src/videoImport.ts    extraction des images + reconnaissance par Claude
  src/seed.ts           chargement des livres de seed/
seed/                   livres identifiés dans la vidéo de l'armoire + images
data/                   base SQLite, vidéos importées, images extraites (non versionné)
```

## API

| Méthode | Route | Rôle |
|---|---|---|
| GET | `/api/books?q=&status=&shelf=&genre=&sort=` | liste filtrée (`sort` : position, title, author, recent) |
| GET / PATCH / DELETE | `/api/books/:id` | lire, modifier, supprimer un livre |
| POST | `/api/books` | ajouter un livre |
| POST | `/api/books/validate-all` | valider tous les livres « à vérifier » |
| GET | `/api/stats` | compteurs, étagères et genres |
| GET | `/api/export.csv` | export CSV |
| POST | `/api/imports` | envoyer une vidéo (multipart : `video`, `shelf_hint`, `interval`) |
| GET | `/api/imports`, `/api/imports/:id` | suivi des imports |
