"""Charge dans la base les livres déjà identifiés depuis la vidéo de l'armoire.

    python -m backend.seed            # ajoute les livres absents (sans doublon)
"""
from __future__ import annotations

import json
import shutil
from pathlib import Path

from . import db
from .video_import import FRAMES_DIR

SEED_DIR = Path(__file__).resolve().parent.parent / "seed"
TAG = "armoire_video"


def load(seed_file: Path = SEED_DIR / "armoire_video.json") -> dict:
    db.init_db()
    data = json.loads(seed_file.read_text(encoding="utf-8"))
    frames_dest = FRAMES_DIR / TAG
    frames_dest.mkdir(parents=True, exist_ok=True)
    for frame in (SEED_DIR / "frames").glob("*.jpg"):
        shutil.copy2(frame, frames_dest / frame.name)

    added = skipped = 0
    with db.connect() as conn:
        for book in data["books"]:
            if db.find_duplicate(conn, book["title"], book.get("author"), book.get("publisher")):
                skipped += 1
                continue
            db.insert_book(conn, {
                **book,
                "source_frame": f"{TAG}/{book['source_frame']}" if book.get("source_frame") else None,
                "status": "a_verifier",
            })
            added += 1
    return {"added": added, "skipped": skipped}


if __name__ == "__main__":
    print(load())
