"""Import d'une vidéo d'armoire/bibliothèque : extraction d'images + reconnaissance des livres par Claude.

Utilisation en ligne de commande :
    python -m backend.video_import chemin/vers/video.mp4 [--interval 1.5] [--shelf-hint "Armoire salon"]
"""
from __future__ import annotations

import argparse
import base64
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Callable

import anthropic

from . import db

MODEL = "claude-opus-5-5"
FRAMES_PER_REQUEST = 6
FRAMES_DIR = db.DATA_DIR / "frames"

BOOK_SCHEMA = {
    "type": "object",
    "properties": {
        "books": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "title": {"type": "string"},
                    "author": {"type": ["string", "null"]},
                    "publisher": {"type": ["string", "null"]},
                    "year": {"type": ["integer", "null"]},
                    "isbn": {"type": ["string", "null"]},
                    "genre": {"type": ["string", "null"]},
                    "language": {"type": ["string", "null"]},
                    "shelf": {"type": ["string", "null"]},
                    "frame_index": {"type": "integer"},
                    "confidence": {"type": "number"},
                    "already_listed": {"type": "boolean"},
                },
                "required": [
                    "title", "author", "publisher", "year", "isbn", "genre", "language",
                    "shelf", "frame_index", "confidence", "already_listed",
                ],
                "additionalProperties": False,
            },
        }
    },
    "required": ["books"],
    "additionalProperties": False,
}

SYSTEM_PROMPT = """Tu catalogues les livres d'une armoire filmée par son propriétaire. \
On te donne des images successives extraites de la vidéo (la caméra balaie les étagères).

Pour chaque livre dont le titre est lisible (tranche, couverture ou dos), renvoie une entrée :
- title / author / publisher : tels qu'écrits sur le livre, orthographe corrigée seulement si \
tu reconnais l'ouvrage avec certitude. Titre seul si l'auteur n'est pas visible et que tu ne le connais pas.
- year, isbn : uniquement si visibles ou si tu connais l'édition avec certitude, sinon null.
- genre : ton estimation courte en français (roman, policier, BD, essai, jeunesse, cuisine…), ou null.
- language : code ISO 639-1 de la langue du titre (fr, en…).
- shelf : l'étagère où se trouve le livre, numérotée depuis le haut ("Étagère 1", "Étagère 2"…), \
seulement si on peut la déduire de l'image, sinon null.
- frame_index : numéro de l'image (0-based dans ce lot) où le livre est le plus lisible.
- confidence : entre 0 et 1, ta confiance dans la lecture du titre et de l'auteur.
- already_listed : true si le livre figure déjà dans la liste « déjà catalogués » fournie.

Les images se chevauchent : ne liste qu'une fois un livre visible sur plusieurs images du lot. \
N'invente jamais un titre illisible ; ignore les livres totalement illisibles."""


def extract_frames(video: Path, out_dir: Path, interval: float = 1.5, max_width: int = 1568) -> list[Path]:
    """Extrait une image toutes les `interval` secondes, en supprimant les images quasi identiques."""
    if not shutil.which("ffmpeg"):
        raise RuntimeError("ffmpeg est requis pour extraire les images de la vidéo.")
    out_dir.mkdir(parents=True, exist_ok=True)
    vf = f"fps=1/{interval},mpdecimate,scale='min({max_width},iw)':-2"
    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(video),
        "-vf", vf, "-fps_mode", "vfr", "-q:v", "3", str(out_dir / "frame_%05d.jpg"),
    ]
    subprocess.run(cmd, check=True)
    return sorted(out_dir.glob("frame_*.jpg"))


def _image_block(path: Path) -> dict:
    data = base64.standard_b64encode(path.read_bytes()).decode()
    return {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": data}}


def identify_books(
    client: anthropic.Anthropic, frames: list[Path], known: list[str], shelf_hint: str | None
) -> list[dict]:
    content: list[dict] = []
    for i, frame in enumerate(frames):
        content.append({"type": "text", "text": f"Image {i} :"})
        content.append(_image_block(frame))
    known_txt = "\n".join(f"- {k}" for k in known[-400:]) or "(aucun)"
    context = f"Emplacement indiqué par l'utilisateur : {shelf_hint}\n" if shelf_hint else ""
    content.append({
        "type": "text",
        "text": f"{context}Livres déjà catalogués :\n{known_txt}\n\nListe les livres visibles sur ces images.",
    })

    response = client.beta.messages.create(
        model=MODEL,
        max_tokens=16000,
        system=SYSTEM_PROMPT,
        output_config={"effort": "medium", "format": {"type": "json_schema", "schema": BOOK_SCHEMA}},
        betas=["server-side-fallback-2026-07-01"],
        fallbacks="default",
        messages=[{"role": "user", "content": content}],
    )
    if response.stop_reason == "refusal":
        raise RuntimeError("La requête a été refusée par le modèle.")
    if response.stop_reason == "max_tokens":
        raise RuntimeError("Réponse tronquée (trop de livres dans le lot) : réduisez FRAMES_PER_REQUEST.")
    text = next(b.text for b in response.content if b.type == "text")
    return json.loads(text)["books"]


def run_import(
    video: Path,
    import_id: int | None = None,
    interval: float = 1.5,
    shelf_hint: str | None = None,
    on_progress: Callable[[float, str], None] | None = None,
) -> dict:
    """Traite la vidéo et insère les livres détectés (statut « à vérifier »)."""
    progress = on_progress or (lambda p, m: print(f"[{p:5.1%}] {m}", file=sys.stderr))
    db.init_db()
    client = anthropic.Anthropic()

    tag = f"import_{import_id}" if import_id else video.stem
    frames_dir = FRAMES_DIR / tag
    with tempfile.TemporaryDirectory() as tmp:
        progress(0.02, "Extraction des images de la vidéo…")
        frames = extract_frames(video, Path(tmp), interval=interval)
        if not frames:
            raise RuntimeError("Aucune image n'a pu être extraite de la vidéo.")
        frames_dir.mkdir(parents=True, exist_ok=True)
        kept = []
        for f in frames:
            dest = frames_dir / f.name
            shutil.move(str(f), dest)
            kept.append(dest)
    frames = kept
    progress(0.08, f"{len(frames)} images extraites, reconnaissance des livres…")

    with db.connect() as conn:
        known = [
            f"{r['title']} — {r['author'] or '?'}"
            for r in conn.execute("SELECT title, author FROM books ORDER BY id")
        ]

    added, skipped = 0, 0
    batches = [frames[i:i + FRAMES_PER_REQUEST] for i in range(0, len(frames), FRAMES_PER_REQUEST)]
    for b, batch in enumerate(batches):
        try:
            books = identify_books(client, batch, known, shelf_hint)
        except anthropic.RateLimitError:
            raise RuntimeError("Limite de requêtes atteinte côté API Claude, réessayez plus tard.")
        except (anthropic.AuthenticationError, TypeError) as exc:
            if isinstance(exc, TypeError) and "authentication" not in str(exc):
                raise
            raise RuntimeError("Clé API Anthropic invalide ou absente : définissez ANTHROPIC_API_KEY.")
        with db.connect() as conn:
            position = conn.execute("SELECT COALESCE(MAX(position), 0) FROM books").fetchone()[0]
            for book in books:
                title = (book.get("title") or "").strip()
                if not title or book.get("already_listed") or db.find_duplicate(conn, title, book.get("author")):
                    skipped += 1
                    continue
                idx = min(max(book.get("frame_index", 0), 0), len(batch) - 1)
                position += 1
                db.insert_book(conn, {
                    **{k: book.get(k) for k in ("title", "author", "publisher", "year", "isbn", "genre", "language")},
                    "title": title,
                    "shelf": " – ".join(x for x in (shelf_hint, book.get("shelf")) if x) or None,
                    "position": position,
                    "confidence": book.get("confidence"),
                    "source_frame": f"{tag}/{batch[idx].name}",
                    "status": "a_verifier",
                }, import_id=import_id)
                known.append(f"{title} — {book.get('author') or '?'}")
                added += 1
        progress(0.08 + 0.92 * (b + 1) / len(batches),
                 f"Lot {b + 1}/{len(batches)} : {added} livres ajoutés, {skipped} doublons ignorés")

    return {"frames": len(frames), "added": added, "skipped": skipped}


def main() -> None:
    parser = argparse.ArgumentParser(description="Remplit la base RecyBook depuis une vidéo d'étagères.")
    parser.add_argument("video", type=Path)
    parser.add_argument("--interval", type=float, default=1.5, help="secondes entre deux images (défaut 1.5)")
    parser.add_argument("--shelf-hint", help="nom de l'armoire / emplacement, ex. « Armoire salon »")
    args = parser.parse_args()
    result = run_import(args.video, interval=args.interval, shelf_hint=args.shelf_hint)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
