# 📚 RecyBook

Application web de gestion de livres. La base est **initialisée une seule fois à partir d'une vidéo** de votre
armoire / bibliothèque, puis gérée depuis l'interface web.

- **Client** : Angular 22 (composants autonomes, signals, sans zone.js)
- **Serveur** : Express 5 en TypeScript, base SQLite via le module `node:sqlite` intégré à Node (aucune dépendance native)
- **Initialisation depuis une vidéo** (ligne de commande) : ffmpeg + Claude (`claude-opus-5-5`, vision) via le SDK `@anthropic-ai/sdk`

## Installation

Prérequis : Node.js 22.13 ou plus récent.

```bash
npm install          # installe aussi server/ et client/
npm run seed         # initialise la base avec les 37 livres de la vidéo de l'armoire
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
npm run build        # compile Angular (client/dist/) et le serveur (server/dist/)
npm start            # Express sert l'API et l'application sur http://localhost:3000
```

### Docker

```bash
docker build -t recybook .
docker run -d -p 8080:3000 -v recybook-data:/app/data --name recybook recybook
```

Ouvrez <http://localhost:8080>. Au premier démarrage, la base est initialisée avec les 37 livres de `seed/` ;
le volume `recybook-data` conserve la base et vos modifications d'un conteneur à l'autre.
L'image ne contient pas ffmpeg : pour initialiser la base depuis une autre vidéo, lancez
`npm run import-video` hors de Docker, puis copiez le dossier `data/` dans le volume.

Variables d'environnement : `PORT` (défaut 3000), `RECYBOOK_DATA` (dossier des données, défaut `data/`),
`GOOGLE_BOOKS_API_KEY` (facultatif, voir « Couvertures »), `RECYBOOK_COVERS=off` (désactive la recherche
automatique des couvertures, par exemple hors ligne).

## Initialisation de la base

L'import vidéo ne sert qu'à **remplir la base au départ** ; il n'est pas proposé dans l'interface web.

### Avec la vidéo de l'armoire (déjà analysée)

Les 37 livres reconnus dans la vidéo `PXL_20260731_114114534.TS.mp4` (armoire vitrée, 2 étagères) sont
fournis dans `seed/armoire_video.json`, avec les images sources dans `seed/frames/` :

```bash
npm run seed
```

Relancer la commande ne crée pas de doublons. Les livres arrivent avec le statut « à vérifier » : relisez en
priorité ceux dont la note signale une tranche peu lisible.

### Avec une autre vidéo

Nécessite `ffmpeg` et une clé API Anthropic :

```bash
export ANTHROPIC_API_KEY=sk-ant-...
npm run import-video -- ma_video.mp4 --shelf-hint "Armoire salon" [--interval 1.5]
```

1. `ffmpeg` extrait une image toutes les 1,5 s et supprime les images quasi identiques.
2. Les images sont envoyées par lots de 6 à Claude, qui lit les tranches et couvertures et renvoie pour
   chaque livre : titre, auteur, éditeur, année, ISBN, genre, langue, étagère, et sa confiance dans la lecture
   (sortie JSON structurée). Une requête refusée par les filtres de sécurité est relancée automatiquement sur
   un autre modèle (`fallbacks: "default"`).
3. Les doublons (même livre vu sur plusieurs images, ou déjà en base) sont éliminés.
4. Les livres sont ajoutés avec le statut **« à vérifier »** et l'image d'où ils viennent.

## Couvertures

Les vignettes affichent la **couverture officielle** de chaque livre, cherchée automatiquement :

1. par ISBN, s'il est renseigné (Open Library) ;
2. par titre et auteur dans [Open Library](https://openlibrary.org), en préférant une édition française ;
3. sinon dans Google Books.

Un résultat dont le titre ne correspond pas est écarté, pour éviter d'afficher la couverture d'un autre livre.

La recherche se fait **en arrière-plan** au démarrage du serveur (pour les livres jamais cherchés, par exemple
après `npm run seed`), à l'ajout d'un livre, et quand son titre, son auteur, son éditeur ou son ISBN change.
Les images sont gardées en cache dans `data/covers/` : le navigateur ne charge que des images servies par
RecyBook.

Dans la fiche d'un livre, vous pouvez relancer la recherche (« Rechercher la couverture ») ou coller l'URL d'une
couverture de votre choix. Pour tout chercher d'un coup et voir la liste des livres sans couverture :

```bash
npm run covers            # livres jamais cherchés
npm run covers -- --all   # tous les livres
```

L'API Google Books limite le nombre de requêtes anonymes ; avec beaucoup de livres, définissez
`GOOGLE_BOOKS_API_KEY` (clé gratuite sur Google Cloud).

## Fonctionnalités

- Recherche (titre, auteur, ISBN, notes), filtres par statut / étagère / genre, tri
- Ajout, modification, suppression ; couverture officielle de chaque livre
- Case « Lu », validation livre par livre ou en une fois
- Export CSV (compatible Excel)

## Structure

```
client/                 application Angular
  src/app/library/      bibliothèque (liste, filtres, statistiques)
  src/app/book-dialog/  fiche livre
  src/app/api.service.ts
server/                 serveur Express
  src/index.ts          API REST + service de l'application compilée
  src/db.ts             schéma SQLite et dédoublonnage
  src/videoImport.ts    initialisation depuis une vidéo (ffmpeg + Claude), en ligne de commande
  src/seed.ts           initialisation avec les livres de seed/
  src/covers.ts         recherche et cache des couvertures (Open Library, Google Books)
seed/                   livres identifiés dans la vidéo de l'armoire + images
data/                   base SQLite, couvertures en cache, images extraites (non versionné)
```

## API

| Méthode | Route | Rôle |
|---|---|---|
| GET | `/api/books?q=&status=&shelf=&genre=&sort=` | liste filtrée (`sort` : position, title, author, recent) |
| GET / PATCH / DELETE | `/api/books/:id` | lire, modifier, supprimer un livre |
| POST | `/api/books` | ajouter un livre |
| POST | `/api/books/validate-all` | valider tous les livres « à vérifier » |
| GET | `/api/books/:id/cover` | image de la couverture (404 si aucune) |
| POST | `/api/books/:id/cover/search` | relancer la recherche de couverture |
| GET | `/api/stats` | compteurs, étagères et genres |
| GET | `/api/export.csv` | export CSV |
