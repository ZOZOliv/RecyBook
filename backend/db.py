"""Accès SQLite pour RecyBook."""
from __future__ import annotations

import os
import sqlite3
import unicodedata
import re
from contextlib import contextmanager
from pathlib import Path

DATA_DIR = Path(os.environ.get("RECYBOOK_DATA", Path(__file__).resolve().parent.parent / "data"))
DB_PATH = DATA_DIR / "recybook.db"

BOOK_FIELDS = [
    "title", "author", "publisher", "year", "isbn", "genre", "language",
    "shelf", "position", "status", "is_read", "notes", "confidence", "source_frame",
]

SCHEMA = """
CREATE TABLE IF NOT EXISTS books (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    title        TEXT NOT NULL,
    author       TEXT,
    publisher    TEXT,
    year         INTEGER,
    isbn         TEXT,
    genre        TEXT,
    language     TEXT,
    shelf        TEXT,            -- étagère / rangée dans l'armoire
    position     INTEGER,         -- ordre d'apparition sur l'étagère
    status       TEXT NOT NULL DEFAULT 'a_verifier',  -- a_verifier | valide
    is_read      INTEGER NOT NULL DEFAULT 0,
    notes        TEXT,
    confidence   REAL,            -- confiance de la détection (0-1)
    source_frame TEXT,            -- image extraite de la vidéo
    import_id    INTEGER,
    norm_key     TEXT,
    created_at   TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_books_norm ON books(norm_key);

CREATE TABLE IF NOT EXISTS imports (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    filename    TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'en_attente',  -- en_attente | en_cours | termine | erreur
    progress    REAL NOT NULL DEFAULT 0,
    message     TEXT,
    frames      INTEGER DEFAULT 0,
    books_added INTEGER DEFAULT 0,
    created_at  TEXT NOT NULL DEFAULT (datetime('now'))
);
"""


def normalize(text: str | None) -> str:
    if not text:
        return ""
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    text = re.sub(r"[^a-z0-9 ]", " ", text.lower())
    return re.sub(r"\s+", " ", text).strip()


def norm_key(title: str | None, author: str | None) -> str:
    return f"{normalize(title)}|{normalize(author)}"


@contextmanager
def connect():
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def init_db() -> None:
    with connect() as conn:
        conn.executescript(SCHEMA)


def row_to_dict(row: sqlite3.Row | None) -> dict | None:
    if row is None:
        return None
    d = dict(row)
    if "is_read" in d:
        d["is_read"] = bool(d["is_read"])
    d.pop("norm_key", None)
    return d


def find_duplicate(conn: sqlite3.Connection, title: str, author: str | None) -> sqlite3.Row | None:
    """Doublon = même titre normalisé, et même auteur si les deux sont connus."""
    nt = normalize(title)
    na = normalize(author)
    for row in conn.execute("SELECT * FROM books WHERE norm_key LIKE ?", (nt + "|%",)):
        other = row["norm_key"].split("|", 1)[1]
        if not na or not other or _same_author(na, other):
            return row
    return None


def _same_author(a: str, b: str) -> bool:
    """« albert camus » == « a camus » : on compare les mots significatifs (noms)."""
    if a == b or a in b or b in a:
        return True
    ta = {t for t in a.split() if len(t) > 2}
    tb = {t for t in b.split() if len(t) > 2}
    return bool(ta & tb)


def insert_book(conn: sqlite3.Connection, data: dict, import_id: int | None = None) -> int:
    cols = [f for f in BOOK_FIELDS if f in data and data[f] is not None]
    values = [int(data[c]) if c == "is_read" else data[c] for c in cols]
    cols += ["norm_key", "import_id"]
    values += [norm_key(data.get("title"), data.get("author")), import_id]
    cur = conn.execute(
        f"INSERT INTO books ({', '.join(cols)}) VALUES ({', '.join('?' * len(cols))})", values
    )
    return cur.lastrowid
