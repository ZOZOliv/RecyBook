/** Serveur Express RecyBook : API REST + service de l'application Angular compilée. */
import fs from "node:fs";
import path from "node:path";
import express, { type Request, type Response, type NextFunction } from "express";
import {
  BOOK_FIELDS, db, FRAMES_DIR, getBook, insertBook, ROOT, toBook, updateBook, type BookInput,
} from "./db.js";
import { booksWithoutCoverSearch, clearCachedCover, coverFile, queueCoverSearch, searchCover } from "./covers.js";

const app = express();
app.use(express.json());
db();

class HttpError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

const STATUSES = new Set(["a_verifier", "valide"]);

/** Valide et nettoie le corps d'une requête livre (création ou modification). */
function parseBook(body: unknown, partial: boolean): Partial<BookInput> {
  if (!body || typeof body !== "object") throw new HttpError(400, "Corps JSON attendu");
  const src = body as Record<string, unknown>;
  const out: Record<string, unknown> = {};
  for (const f of BOOK_FIELDS) {
    if (!(f in src) || f === "confidence" || f === "source_frame" || f === "cover_status") continue;
    const v = src[f];
    if (v === null || v === "") { out[f] = null; continue; }
    if (f === "year" || f === "position") {
      const n = Number(v);
      if (!Number.isInteger(n)) throw new HttpError(400, `${f} doit être un entier`);
      out[f] = n;
    } else if (f === "is_read") {
      out[f] = Boolean(v);
    } else if (f === "cover_url") {
      const url = String(v).trim();
      if (!/^https?:\/\/\S+$/i.test(url)) throw new HttpError(400, "L'URL de la couverture doit commencer par http(s)://");
      out[f] = url;
    } else {
      out[f] = String(v).trim();
    }
  }
  if (out.status !== undefined && !STATUSES.has(String(out.status))) throw new HttpError(400, "Statut invalide");
  if (!partial && !out.title) throw new HttpError(400, "Le titre est obligatoire");
  if (partial && "title" in out && !out.title) throw new HttpError(400, "Le titre ne peut pas être vide");
  return out as Partial<BookInput>;
}

const idParam = (req: Request) => {
  const id = Number(req.params.id);
  if (!Number.isInteger(id)) throw new HttpError(400, "Identifiant invalide");
  return id;
};

// ---------- Livres ----------
const api = express.Router();

api.get("/books", (req, res) => {
  const q = String(req.query.q ?? "");
  const orders: Record<string, string> = {
    position: "COALESCE(shelf, ''), position, id",
    title: "title COLLATE NOCASE",
    author: "author COLLATE NOCASE, title COLLATE NOCASE",
    recent: "id DESC",
  };
  const order = orders[String(req.query.sort ?? "position")] ?? "id";
  const where: string[] = [];
  const params: string[] = [];
  if (q) {
    where.push("(title LIKE ? OR author LIKE ? OR isbn LIKE ? OR notes LIKE ?)");
    params.push(...Array(4).fill(`%${q}%`));
  }
  for (const col of ["status", "shelf", "genre"] as const) {
    const v = req.query[col];
    if (v) { where.push(`${col} = ?`); params.push(String(v)); }
  }
  const sql = `SELECT * FROM books${where.length ? " WHERE " + where.join(" AND ") : ""} ORDER BY ${order}`;
  res.json((db().prepare(sql).all(...params) as Record<string, unknown>[]).map(toBook));
});

api.get("/books/:id", (req, res) => {
  const book = getBook(idParam(req));
  if (!book) throw new HttpError(404, "Livre introuvable");
  res.json(book);
});

api.post("/books", (req, res) => {
  const data = parseBook(req.body, false) as BookInput;
  const id = insertBook({ status: "valide", ...data, cover_status: data.cover_url ? "found" : null });
  if (!data.cover_url) queueCoverSearch(id);
  res.status(201).json(getBook(id));
});

/** Champs qui identifient le livre : s'ils changent, la couverture est recherchée à nouveau. */
const IDENTITY_FIELDS = ["title", "author", "publisher", "isbn"] as const;

api.patch("/books/:id", (req, res) => {
  const id = idParam(req);
  const current = getBook(id);
  if (!current) throw new HttpError(404, "Livre introuvable");
  const changes = parseBook(req.body, true);
  let research = false;
  if ("cover_url" in changes) {
    // URL saisie (ou effacée) par l'utilisateur
    if (changes.cover_url !== current.cover_url) {
      clearCachedCover(id);
      changes.cover_status = changes.cover_url ? "found" : "absent";
    }
  } else if (IDENTITY_FIELDS.some((f) => f in changes && (changes[f] ?? null) !== current[f])) {
    clearCachedCover(id);
    Object.assign(changes, { cover_url: null, cover_status: null });
    research = true;
  }
  const book = updateBook(id, changes);
  if (research) queueCoverSearch(id);
  res.json(book);
});

api.delete("/books/:id", (req, res) => {
  const id = idParam(req);
  db().prepare("DELETE FROM books WHERE id = ?").run(id);
  clearCachedCover(id);
  res.status(204).end();
});

// Couverture officielle, servie depuis le cache local (téléchargée au premier affichage)
api.get("/books/:id/cover", async (req, res) => {
  const book = getBook(idParam(req));
  const file = book && (await coverFile(book));
  if (!file) throw new HttpError(404, "Pas de couverture");
  res.sendFile(file, { maxAge: "7d" });
});

// Relance la recherche de couverture et attend le résultat
api.post("/books/:id/cover/search", async (req, res) => {
  const book = await searchCover(idParam(req));
  if (!book) throw new HttpError(404, "Livre introuvable");
  res.json(book);
});

api.post("/books/validate-all", (_req, res) => {
  const r = db().prepare("UPDATE books SET status = 'valide' WHERE status = 'a_verifier'").run();
  res.json({ validated: Number(r.changes) });
});

api.get("/stats", (_req, res) => {
  const one = (sql: string) => Number((db().prepare(sql).get() as { n: number }).n);
  const list = (sql: string) => (db().prepare(sql).all() as { v: string }[]).map((r) => r.v);
  res.json({
    total: one("SELECT COUNT(*) AS n FROM books"),
    a_verifier: one("SELECT COUNT(*) AS n FROM books WHERE status = 'a_verifier'"),
    lus: one("SELECT COUNT(*) AS n FROM books WHERE is_read = 1"),
    auteurs: one("SELECT COUNT(DISTINCT author) AS n FROM books WHERE author IS NOT NULL"),
    shelves: list("SELECT DISTINCT shelf AS v FROM books WHERE shelf IS NOT NULL ORDER BY shelf"),
    genres: list("SELECT DISTINCT genre AS v FROM books WHERE genre IS NOT NULL ORDER BY genre"),
  });
});

api.get("/export.csv", (_req, res) => {
  const cols = ["id", ...BOOK_FIELDS];
  const cell = (v: unknown) => {
    const s = v === null || v === undefined ? "" : String(v);
    return /[";\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  const rows = db().prepare(`SELECT ${cols.join(", ")} FROM books ORDER BY shelf, position, id`).all() as Record<string, unknown>[];
  const csv = [cols.join(";"), ...rows.map((r) => cols.map((c) => cell(r[c])).join(";"))].join("\r\n");
  res.setHeader("Content-Type", "text/csv; charset=utf-8");
  res.setHeader("Content-Disposition", "attachment; filename=recybook.csv");
  res.send("﻿" + csv);
});

app.use("/api", api);

// Images extraites des vidéos (express.static refuse les chemins hors du dossier)
app.use("/frames", express.static(FRAMES_DIR, { fallthrough: false }));

// Application Angular compilée (client/dist/client/browser)
const clientDist = path.join(ROOT, "client", "dist", "client", "browser");
if (fs.existsSync(clientDist)) {
  app.use(express.static(clientDist));
  app.get(/^(?!\/(api|frames)\/).*/, (_req, res) => res.sendFile(path.join(clientDist, "index.html")));
}

app.use((err: unknown, _req: Request, res: Response, _next: NextFunction) => {
  const status = err instanceof HttpError ? err.status : (err as { status?: number }).status ?? 500;
  if (status >= 500) console.error(err);
  res.status(status).json({ detail: err instanceof Error ? err.message : "Erreur serveur" });
});

const port = Number(process.env.PORT ?? 3000);
app.listen(port, () => {
  console.log(`RecyBook : http://localhost:${port}`);
  // Cherche en arrière-plan les couvertures jamais cherchées (ex. après npm run seed)
  const pending = booksWithoutCoverSearch();
  if (pending.length && process.env.RECYBOOK_COVERS !== "off") {
    console.log(`Recherche des couvertures de ${pending.length} livres en arrière-plan…`);
    queueCoverSearch(...pending);
  }
});
