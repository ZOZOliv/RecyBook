# 📚 RecyBook

Application web de gestion de livres, avec **remplissage automatique de la base à partir d'une vidéo** de votre armoire / bibliothèque.

## Fonctionnement de l'import vidéo

1. `ffmpeg` extrait une image toutes les 1,5 s (réglable) et supprime les images quasi identiques.
2. Les images sont envoyées par lots de 6 à Claude (vision), qui lit les tranches et couvertures et renvoie pour chaque livre : titre, auteur, éditeur, année, ISBN, genre, langue, étagère, et sa confiance dans la lecture.
3. Les doublons (même livre vu sur plusieurs images, ou déjà en base) sont éliminés.
4. Les livres sont ajoutés avec le statut **« à vérifier »**, avec l'image source. Vous pouvez les corriger puis les valider dans l'interface.

## Installation

Prérequis : Python 3.10+, `ffmpeg`, une clé API Anthropic.

```bash
pip install -r requirements.txt
export ANTHROPIC_API_KEY=sk-ant-...
uvicorn backend.app:app --reload
```

Ouvrez ensuite <http://localhost:8000>.

## Utilisation

- **Importer une vidéo** : onglet « Importer une vidéo », choisissez le fichier, indiquez éventuellement le nom de l'armoire. La progression s'affiche en direct.
- En ligne de commande :
  ```bash
  python -m backend.video_import ma_video.mp4 --shelf-hint "Armoire salon"
  ```
- **Bibliothèque** : recherche (titre, auteur, ISBN, notes), filtres par statut / étagère / genre, tri, ajout et modification manuels, case « Lu », validation en un clic, export CSV (compatible Excel).

## Conseils pour filmer

- Filmez lentement, étagère par étagère, de gauche à droite.
- Bonne lumière, sans reflet ; les tranches doivent être nettes et lisibles à l'écran.
- Une vidéo par armoire si vous en avez plusieurs (utilisez le champ « nom de l'armoire »).

## Structure

```
backend/
  app.py           API FastAPI + service des fichiers du frontend
  db.py            schéma SQLite et dédoublonnage
  video_import.py  extraction des images + reconnaissance par Claude
frontend/          interface web (HTML / CSS / JS sans dépendance)
data/              base SQLite, vidéos importées, images extraites (non versionné)
```
