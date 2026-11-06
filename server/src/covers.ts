/**
 * Couvertures officielles des livres.
 *
 * Recherche dans Open Library puis Google Books (par ISBN, puis titre + auteur), et garde l'image
 * en cache dans data/covers/ : le navigateur ne charge que des images servies par RecyBook.
 *
 *   npm run covers            # cherche les couvertures manquantes de tous les livres
 *   npm run covers -- --all   # recommence la recherche pour tous les livres
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { COVERS_DIR, db, getBook, normalize, type Book } from "./db.js";

const OPENLIBRARY = process.env.OPENLIBRARY_URL ?? "https://openlibrary.org";
const OPENLIBRARY_COVERS = process.env.OPENLIBRARY_COVERS_URL ?? "https://covers.openlibrary.org";
const GOOGLE_BOOKS = process.env.GOOGLE_BOOKS_URL ?? "https://www.googleapis.com/books/v1";
const GOOGLE_KEY = process.env.GOOGLE_BOOKS_API_KEY;
/** Open Library demande d'identifier l'application qui interroge son API. */
const USER_AGENT = "RecyBook/2.0 (gestion de bibliotheque personnelle)";
const TIMEOUT_MS = 10_000;
/** Pause entre deux livres, pour rester raisonnable avec les API publiques. */
const DELAY_MS = Number(process.env.COVERS_DELAY_MS ?? 400);

const EXTENSIONS: Record<string, string> = {
  "image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp", "image/gif": ".gif",
};

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function getJson<T>(url: string): Promise<T | null> {
  try {
    const res = await fetch(url, { headers: { "User-Agent": USER_AGENT }, signal: AbortSignal.timeout(TIMEOUT_MS) });
    return res.ok ? ((await res.json()) as T) : null;
  } catch {
    return null;
  }
}

/** Les titres correspondent si la plupart des mots significatifs du titre cherché sont présents. */
function titleMatches(wanted: string, found: string | undefined): boolean {
  if (!found) return false;
  const words = normalize(wanted).split(" ").filter((w) => w.length > 2);
  const have = new Set(normalize(found).split(" "));
  if (!words.length) return normalize(found).includes(normalize(wanted));
  return words.filter((w) => have.has(w)).length / words.length >= 0.6;
}

/** Titre sans précision d'édition : « Pérou, Bolivie 2007-2008 » -> « Pérou, Bolivie ». */
function searchableTitle(title: string): string {
  return title.replace(/\(.*?\)/g, "").replace(/\b(19|20)\d{2}(-(19|20)?\d{2})?\b/g, "").replace(/\s+/g, " ").trim();
}

/** Auteur utilisable pour la recherche (une collection comme « Le Guide du Routard » n'en est pas un). */
function searchableAuthor(author: string | null): string | null {
  return author && !/guide/i.test(author) ? author : null;
}

interface OpenLibraryDoc { title?: string; cover_i?: number; language?: string[] }

async function fromOpenLibrary(book: Book): Promise<string | null> {
  const title = searchableTitle(book.title);
  const author = searchableAuthor(book.author);
  const attempts: Record<string, string>[] = [];
  if (author) attempts.push({ title, author });
  attempts.push({ q: [title, book.author, book.publisher].filter(Boolean).join(" ") });
  for (const params of attempts) {
    const qs = new URLSearchParams({ ...params, fields: "title,cover_i,language", limit: "10" });
    const data = await getJson<{ docs?: OpenLibraryDoc[] }>(`${OPENLIBRARY}/search.json?${qs}`);
    const docs = (data?.docs ?? []).filter((d) => d.cover_i && titleMatches(title, d.title));
    // Préfère une édition française
    const doc = docs.find((d) => d.language?.includes("fre")) ?? docs[0];
    if (doc) return `${OPENLIBRARY_COVERS}/b/id/${doc.cover_i}-L.jpg`;
  }
  return null;
}

interface GoogleVolume { volumeInfo?: { title?: string; imageLinks?: Record<string, string> } }

async function fromGoogleBooks(book: Book): Promise<string | null> {
  const title = searchableTitle(book.title);
  const author = searchableAuthor(book.author);
  const terms = book.isbn
    ? [`isbn:${book.isbn.replace(/[^0-9X]/gi, "")}`]
    : [`intitle:${title}`, ...(author ? [`inauthor:${author}`] : [])];
  const qs = new URLSearchParams({ q: terms.join(" "), maxResults: "10", printType: "books" });
  if (GOOGLE_KEY) qs.set("key", GOOGLE_KEY);
  const data = await getJson<{ items?: GoogleVolume[] }>(`${GOOGLE_BOOKS}/volumes?${qs}`);
  for (const item of data?.items ?? []) {
    const links = item.volumeInfo?.imageLinks;
    const url = links?.thumbnail ?? links?.smallThumbnail;
    if (url && (book.isbn || titleMatches(title, item.volumeInfo?.title))) {
      return url.replace(/^http:\/\/(books\.google\.)/, "https://$1").replace(/&edge=curl/, "");
    }
  }
  return null;
}

/** Cherche l'URL d'une couverture officielle (Open Library, puis Google Books). */
export async function findCoverUrl(book: Book): Promise<string | null> {
  if (book.isbn) {
    const isbn = book.isbn.replace(/[^0-9X]/gi, "");
    const url = `${OPENLIBRARY_COVERS}/b/isbn/${isbn}-L.jpg?default=false`;
    if (await downloadImage(url)) return url;
  }
  return (await fromOpenLibrary(book)) ?? (await fromGoogleBooks(book));
}

/** Télécharge une image ; null si ce n'est pas une vraie image (Open Library renvoie 1×1 px quand il n'a rien). */
async function downloadImage(url: string): Promise<{ data: Buffer; ext: string } | null> {
  try {
    const res = await fetch(url, { headers: { "User-Agent": USER_AGENT }, signal: AbortSignal.timeout(TIMEOUT_MS) });
    const type = (res.headers.get("content-type") ?? "").split(";")[0].trim();
    if (!res.ok || !EXTENSIONS[type]) return null;
    const data = Buffer.from(await res.arrayBuffer());
    return data.length > 1000 ? { data, ext: EXTENSIONS[type] } : null;
  } catch {
    return null;
  }
}

// ---------- Cache local ----------

export function cachedCover(id: number): string | null {
  if (!fs.existsSync(COVERS_DIR)) return null;
  const file = fs.readdirSync(COVERS_DIR).find((f) => path.parse(f).name === String(id));
  return file ? path.join(COVERS_DIR, file) : null;
}

export function clearCachedCover(id: number): void {
  const file = cachedCover(id);
  if (file) fs.rmSync(file, { force: true });
}

/** Renvoie le fichier en cache de la couverture, en le téléchargeant si besoin. */
export async function coverFile(book: Book): Promise<string | null> {
  const cached = cachedCover(book.id);
  if (cached) return cached;
  if (!book.cover_url) return null;
  const image = await downloadImage(book.cover_url);
  if (!image) return null;
  fs.mkdirSync(COVERS_DIR, { recursive: true });
  const file = path.join(COVERS_DIR, `${book.id}${image.ext}`);
  fs.writeFileSync(file, image.data);
  return file;
}

function setCover(id: number, url: string | null): void {
  db().prepare("UPDATE books SET cover_url = ?, cover_status = ? WHERE id = ?").run(url, url ? "found" : "absent", id);
}

/** Cherche la couverture d'un livre, l'enregistre et la met en cache. Renvoie le livre à jour. */
export async function searchCover(id: number): Promise<Book | undefined> {
  const book = getBook(id);
  if (!book) return undefined;
  clearCachedCover(id);
  const url = await findCoverUrl(book);
  setCover(id, url);
  const updated = getBook(id);
  if (updated?.cover_url && !(await coverFile(updated))) setCover(id, null);
  return getBook(id);
}

// ---------- File d'attente en arrière-plan ----------

const queue: number[] = [];
let running = false;

/** Ajoute des livres à chercher en arrière-plan, un à la fois. */
export function queueCoverSearch(...ids: number[]): void {
  if (process.env.RECYBOOK_COVERS === "off") return;
  for (const id of ids) if (!queue.includes(id)) queue.push(id);
  if (!running) void drain();
}

async function drain(): Promise<void> {
  running = true;
  try {
    while (queue.length) {
      const id = queue.shift()!;
      const book = getBook(id);
      if (book && book.cover_status === null) {
        await searchCover(id).catch((err) => console.error(`Couverture du livre ${id} :`, err));
        await sleep(DELAY_MS);
      }
    }
  } finally {
    running = false;
  }
}

/** Livres dont la couverture n'a jamais été cherchée. */
export function booksWithoutCoverSearch(): number[] {
  return (db().prepare("SELECT id FROM books WHERE cover_status IS NULL ORDER BY id").all() as { id: number }[]).map((r) => r.id);
}

// ---------- Ligne de commande ----------
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const all = process.argv.includes("--all");
  const ids = all
    ? (db().prepare("SELECT id FROM books ORDER BY id").all() as { id: number }[]).map((r) => r.id)
    : booksWithoutCoverSearch();
  let found = 0;
  const missing: string[] = [];
  for (const [i, id] of ids.entries()) {
    const book = await searchCover(id);
    if (book?.cover_status === "found") found++;
    else if (book) missing.push(`${book.title}${book.author ? ` (${book.author})` : ""}`);
    process.stderr.write(`\r${i + 1}/${ids.length}`);
    await sleep(DELAY_MS);
  }
  console.log(`\n${found} couvertures trouvées sur ${ids.length} livres.`);
  if (missing.length) console.log(`Sans couverture :\n- ${missing.join("\n- ")}`);
}
