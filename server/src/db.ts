/** Accès SQLite (module node:sqlite intégré à Node 22, sans dépendance native). */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { DatabaseSync } from "node:sqlite";

const here = path.dirname(fileURLToPath(import.meta.url));
export const ROOT = path.resolve(here, "..", "..");
export const DATA_DIR = path.resolve(process.env.RECYBOOK_DATA ?? path.join(ROOT, "data"));
export const FRAMES_DIR = path.join(DATA_DIR, "frames");
export const UPLOADS_DIR = path.join(DATA_DIR, "uploads");

export const BOOK_FIELDS = [
  "title", "author", "publisher", "year", "isbn", "genre", "language",
  "shelf", "position", "status", "is_read", "notes", "confidence", "source_frame",
] as const;
export type BookField = (typeof BOOK_FIELDS)[number];

export interface Book {
  id: number;
  title: string;
  author: string | null;
  publisher: string | null;
  year: number | null;
  isbn: string | null;
  genre: string | null;
  language: string | null;
  shelf: string | null;
  position: number | null;
  status: "a_verifier" | "valide";
  is_read: boolean;
  notes: string | null;
  confidence: number | null;
  source_frame: string | null;
  import_id: number | null;
  created_at: string;
  updated_at: string;
}

export type BookInput = Partial<Omit<Book, "id" | "import_id" | "created_at" | "updated_at">> & { title: string };

const SCHEMA = `
CREATE TABLE IF NOT EXISTS books (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    title        TEXT NOT NULL,
    author       TEXT,
    publisher    TEXT,
    year         INTEGER,
    isbn         TEXT,
    genre        TEXT,
    language     TEXT,
    shelf        TEXT,
    position     INTEGER,
    status       TEXT NOT NULL DEFAULT 'a_verifier',
    is_read      INTEGER NOT NULL DEFAULT 0,
    notes        TEXT,
    confidence   REAL,
    source_frame TEXT,
    import_id    INTEGER,
    norm_key     TEXT,
    created_at   TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at   TEXT NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX IF NOT EXISTS idx_books_norm ON books(norm_key);

CREATE TABLE IF NOT EXISTS imports (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    filename    TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'en_attente',
    progress    REAL NOT NULL DEFAULT 0,
    message     TEXT,
    frames      INTEGER DEFAULT 0,
    books_added INTEGER DEFAULT 0,
    created_at  TEXT NOT NULL DEFAULT (datetime('now'))
);
`;

let instance: DatabaseSync | null = null;

export function db(): DatabaseSync {
  if (!instance) {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    instance = new DatabaseSync(path.join(DATA_DIR, "recybook.db"));
    instance.exec("PRAGMA journal_mode = WAL;");
    instance.exec(SCHEMA);
  }
  return instance;
}

export function normalize(text: string | null | undefined): string {
  if (!text) return "";
  return text
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9 ]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

export const normKey = (title?: string | null, author?: string | null) =>
  `${normalize(title)}|${normalize(author)}`;

/** « albert camus » == « a camus » : on compare les mots significatifs (noms). */
function sameName(a: string, b: string): boolean {
  if (a === b || a.includes(b) || b.includes(a)) return true;
  const ta = new Set(a.split(" ").filter((t) => t.length > 2));
  return b.split(" ").some((t) => t.length > 2 && ta.has(t));
}

type Row = Record<string, unknown>;

/**
 * Doublon = même titre normalisé, même auteur si les deux sont connus,
 * et même éditeur si les deux sont connus (deux guides « Pérou » d'éditeurs différents ≠ doublon).
 */
export function findDuplicate(title: string, author?: string | null, publisher?: string | null): Row | undefined {
  const nt = normalize(title) + "|";
  const na = normalize(author);
  const np = normalize(publisher);
  const rows = db().prepare("SELECT * FROM books WHERE substr(norm_key, 1, ?) = ?").all(nt.length, nt) as Row[];
  return rows.find((row) => {
    const other = String(row.norm_key).split("|", 2)[1];
    const otherPub = normalize(row.publisher as string | null);
    if (np && otherPub && !sameName(np, otherPub)) return false;
    return !na || !other || sameName(na, other);
  });
}

export function toBook(row: Row | undefined): Book | undefined {
  if (!row) return undefined;
  const { norm_key: _ignored, ...rest } = row;
  return { ...rest, is_read: Boolean(rest.is_read) } as unknown as Book;
}

type SqlValue = string | number | null;

export function insertBook(data: BookInput, importId: number | null = null): number {
  const cols: string[] = [];
  const values: SqlValue[] = [];
  for (const f of BOOK_FIELDS) {
    const v = data[f];
    if (v === undefined || v === null) continue;
    cols.push(f);
    values.push(f === "is_read" ? (v ? 1 : 0) : (v as SqlValue));
  }
  cols.push("norm_key", "import_id");
  values.push(normKey(data.title, data.author), importId);
  const res = db()
    .prepare(`INSERT INTO books (${cols.join(", ")}) VALUES (${cols.map(() => "?").join(", ")})`)
    .run(...values);
  return Number(res.lastInsertRowid);
}

export function getBook(id: number): Book | undefined {
  return toBook(db().prepare("SELECT * FROM books WHERE id = ?").get(id) as Row | undefined);
}

export function updateBook(id: number, changes: Partial<BookInput>): Book | undefined {
  const current = getBook(id);
  if (!current) return undefined;
  const entries = Object.entries(changes).filter(([k, v]) => (BOOK_FIELDS as readonly string[]).includes(k) && v !== undefined);
  if (!entries.length) return current;
  const merged = { ...current, ...changes };
  const values: SqlValue[] = entries.map(([k, v]) => (k === "is_read" ? (v ? 1 : 0) : (v as SqlValue)));
  const sets = [...entries.map(([k]) => `${k} = ?`), "norm_key = ?", "updated_at = datetime('now')"];
  db()
    .prepare(`UPDATE books SET ${sets.join(", ")} WHERE id = ?`)
    .run(...values, normKey(merged.title, merged.author), id);
  return getBook(id);
}
