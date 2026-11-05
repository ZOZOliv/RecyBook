"""API web RecyBook (FastAPI)."""
from __future__ import annotations

import csv
import io
import shutil
from pathlib import Path
from typing import Optional

from fastapi import BackgroundTasks, FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from . import db
from .video_import import FRAMES_DIR, run_import

ROOT = Path(__file__).resolve().parent.parent
UPLOADS_DIR = db.DATA_DIR / "uploads"

app = FastAPI(title="RecyBook")
db.init_db()


class BookIn(BaseModel):
    title: str
    author: Optional[str] = None
    publisher: Optional[str] = None
    year: Optional[int] = None
    isbn: Optional[str] = None
    genre: Optional[str] = None
    language: Optional[str] = None
    shelf: Optional[str] = None
    position: Optional[int] = None
    status: str = "valide"
    is_read: bool = False
    notes: Optional[str] = None


class BookPatch(BaseModel):
    title: Optional[str] = None
    author: Optional[str] = None
    publisher: Optional[str] = None
    year: Optional[int] = None
    isbn: Optional[str] = None
    genre: Optional[str] = None
    language: Optional[str] = None
    shelf: Optional[str] = None
    position: Optional[int] = None
    status: Optional[str] = None
    is_read: Optional[bool] = None
    notes: Optional[str] = None


# ---------- Livres ----------

@app.get("/api/books")
def list_books(q: str = "", status: str = "", shelf: str = "", genre: str = "", sort: str = "position"):
    order = {
        "position": "COALESCE(shelf, ''), position, id",
        "title": "title COLLATE NOCASE",
        "author": "author COLLATE NOCASE, title COLLATE NOCASE",
        "recent": "id DESC",
    }.get(sort, "id")
    where, params = [], []
    if q:
        where.append("(title LIKE ? OR author LIKE ? OR isbn LIKE ? OR notes LIKE ?)")
        params += [f"%{q}%"] * 4
    for col, val in (("status", status), ("shelf", shelf), ("genre", genre)):
        if val:
            where.append(f"{col} = ?")
            params.append(val)
    sql = "SELECT * FROM books" + (" WHERE " + " AND ".join(where) if where else "") + f" ORDER BY {order}"
    with db.connect() as conn:
        return [db.row_to_dict(r) for r in conn.execute(sql, params)]


@app.get("/api/books/{book_id}")
def get_book(book_id: int):
    with db.connect() as conn:
        book = db.row_to_dict(conn.execute("SELECT * FROM books WHERE id = ?", (book_id,)).fetchone())
    if not book:
        raise HTTPException(404, "Livre introuvable")
    return book


@app.post("/api/books", status_code=201)
def create_book(book: BookIn):
    with db.connect() as conn:
        book_id = db.insert_book(conn, book.model_dump())
    return get_book(book_id)


@app.patch("/api/books/{book_id}")
def update_book(book_id: int, patch: BookPatch):
    changes = patch.model_dump(exclude_unset=True)
    if not changes:
        return get_book(book_id)
    current = get_book(book_id)
    if "is_read" in changes:
        changes["is_read"] = int(changes["is_read"])
    merged = {**current, **changes}
    changes["norm_key"] = db.norm_key(merged["title"], merged["author"])
    sets = ", ".join(f"{k} = ?" for k in changes) + ", updated_at = datetime('now')"
    with db.connect() as conn:
        conn.execute(f"UPDATE books SET {sets} WHERE id = ?", [*changes.values(), book_id])
    return get_book(book_id)


@app.delete("/api/books/{book_id}", status_code=204)
def delete_book(book_id: int):
    with db.connect() as conn:
        conn.execute("DELETE FROM books WHERE id = ?", (book_id,))


@app.post("/api/books/validate-all")
def validate_all():
    with db.connect() as conn:
        n = conn.execute("UPDATE books SET status = 'valide' WHERE status = 'a_verifier'").rowcount
    return {"validated": n}


@app.get("/api/stats")
def stats():
    with db.connect() as conn:
        one = lambda sql: conn.execute(sql).fetchone()[0]
        return {
            "total": one("SELECT COUNT(*) FROM books"),
            "a_verifier": one("SELECT COUNT(*) FROM books WHERE status = 'a_verifier'"),
            "lus": one("SELECT COUNT(*) FROM books WHERE is_read = 1"),
            "auteurs": one("SELECT COUNT(DISTINCT author) FROM books WHERE author IS NOT NULL"),
            "shelves": [r[0] for r in conn.execute(
                "SELECT DISTINCT shelf FROM books WHERE shelf IS NOT NULL ORDER BY shelf")],
            "genres": [r[0] for r in conn.execute(
                "SELECT DISTINCT genre FROM books WHERE genre IS NOT NULL ORDER BY genre")],
        }


@app.get("/api/export.csv")
def export_csv():
    cols = ["id", *db.BOOK_FIELDS]
    buf = io.StringIO()
    writer = csv.writer(buf, delimiter=";")
    writer.writerow(cols)
    with db.connect() as conn:
        for r in conn.execute(f"SELECT {', '.join(cols)} FROM books ORDER BY shelf, position, id"):
            writer.writerow(list(r))
    return StreamingResponse(
        iter(["﻿" + buf.getvalue()]), media_type="text/csv",
        headers={"Content-Disposition": "attachment; filename=recybook.csv"},
    )


# ---------- Import vidéo ----------

def _process_import(import_id: int, path: Path, interval: float, shelf_hint: str | None):
    def progress(p: float, msg: str):
        with db.connect() as conn:
            conn.execute("UPDATE imports SET status='en_cours', progress=?, message=? WHERE id=?",
                         (p, msg, import_id))
    try:
        result = run_import(path, import_id=import_id, interval=interval,
                            shelf_hint=shelf_hint, on_progress=progress)
        with db.connect() as conn:
            conn.execute(
                "UPDATE imports SET status='termine', progress=1, frames=?, books_added=?, message=? WHERE id=?",
                (result["frames"], result["added"],
                 f"{result['added']} livres ajoutés, {result['skipped']} doublons ignorés", import_id))
    except Exception as exc:  # noqa: BLE001 - l'erreur est remontée à l'interface
        with db.connect() as conn:
            conn.execute("UPDATE imports SET status='erreur', message=? WHERE id=?", (str(exc), import_id))


@app.post("/api/imports", status_code=202)
def create_import(
    background: BackgroundTasks,
    video: UploadFile = File(...),
    interval: float = Form(1.5),
    shelf_hint: str = Form(""),
):
    UPLOADS_DIR.mkdir(parents=True, exist_ok=True)
    safe_name = Path(video.filename or "video.mp4").name
    with db.connect() as conn:
        import_id = conn.execute("INSERT INTO imports (filename) VALUES (?)", (safe_name,)).lastrowid
    dest = UPLOADS_DIR / f"{import_id}_{safe_name}"
    with dest.open("wb") as f:
        shutil.copyfileobj(video.file, f)
    background.add_task(_process_import, import_id, dest, max(0.3, interval), shelf_hint or None)
    return get_import(import_id)


@app.get("/api/imports")
def list_imports():
    with db.connect() as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM imports ORDER BY id DESC")]


@app.get("/api/imports/{import_id}")
def get_import(import_id: int):
    with db.connect() as conn:
        row = conn.execute("SELECT * FROM imports WHERE id = ?", (import_id,)).fetchone()
    if not row:
        raise HTTPException(404, "Import introuvable")
    return dict(row)


@app.get("/frames/{path:path}")
def frame(path: str):
    target = (FRAMES_DIR / path).resolve()
    if not target.is_relative_to(FRAMES_DIR.resolve()) or not target.is_file():
        raise HTTPException(404)
    return FileResponse(target)


app.mount("/", StaticFiles(directory=ROOT / "frontend", html=True), name="frontend")
