/**
 * Import d'une vidéo d'armoire : extraction d'images (ffmpeg) + reconnaissance des livres par Claude.
 *
 * En ligne de commande :
 *   npm run import-video -- chemin/video.mp4 [--interval 1.5] [--shelf-hint "Armoire salon"]
 */
import { execFile } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";
import Anthropic from "@anthropic-ai/sdk";
import { db, findDuplicate, FRAMES_DIR, insertBook } from "./db.js";

const run = promisify(execFile);

const MODEL = "claude-opus-5-5";
const FRAMES_PER_REQUEST = 6;

interface DetectedBook {
  title: string;
  author: string | null;
  publisher: string | null;
  year: number | null;
  isbn: string | null;
  genre: string | null;
  language: string | null;
  shelf: string | null;
  frame_index: number;
  confidence: number;
  already_listed: boolean;
}

const nullable = (type: string) => ({ type: [type, "null"] });

const BOOK_SCHEMA = {
  type: "object",
  properties: {
    books: {
      type: "array",
      items: {
        type: "object",
        properties: {
          title: { type: "string" },
          author: nullable("string"),
          publisher: nullable("string"),
          year: nullable("integer"),
          isbn: nullable("string"),
          genre: nullable("string"),
          language: nullable("string"),
          shelf: nullable("string"),
          frame_index: { type: "integer" },
          confidence: { type: "number" },
          already_listed: { type: "boolean" },
        },
        required: [
          "title", "author", "publisher", "year", "isbn", "genre", "language",
          "shelf", "frame_index", "confidence", "already_listed",
        ],
        additionalProperties: false,
      },
    },
  },
  required: ["books"],
  additionalProperties: false,
};

const SYSTEM_PROMPT = `Tu catalogues les livres d'une armoire filmée par son propriétaire. \
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
N'invente jamais un titre illisible ; ignore les livres totalement illisibles.`;

/** Extrait une image toutes les `interval` secondes, en supprimant les images quasi identiques. */
export async function extractFrames(video: string, outDir: string, interval = 1.5, maxWidth = 1568): Promise<string[]> {
  fs.mkdirSync(outDir, { recursive: true });
  try {
    await run("ffmpeg", [
      "-hide_banner", "-loglevel", "error", "-i", video,
      "-vf", `fps=1/${interval},mpdecimate,scale='min(${maxWidth},iw)':-2`,
      "-fps_mode", "vfr", "-q:v", "3", path.join(outDir, "frame_%05d.jpg"),
    ]);
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code === "ENOENT") {
      throw new Error("ffmpeg est requis pour extraire les images de la vidéo.");
    }
    throw err;
  }
  return fs.readdirSync(outDir).filter((f) => f.startsWith("frame_")).sort().map((f) => path.join(outDir, f));
}

async function identifyBooks(
  client: Anthropic, frames: string[], known: string[], shelfHint: string | null,
): Promise<DetectedBook[]> {
  const content: Anthropic.Beta.BetaContentBlockParam[] = [];
  frames.forEach((frame, i) => {
    content.push({ type: "text", text: `Image ${i} :` });
    content.push({
      type: "image",
      source: { type: "base64", media_type: "image/jpeg", data: fs.readFileSync(frame).toString("base64") },
    });
  });
  const knownTxt = known.slice(-400).map((k) => `- ${k}`).join("\n") || "(aucun)";
  const context = shelfHint ? `Emplacement indiqué par l'utilisateur : ${shelfHint}\n` : "";
  content.push({ type: "text", text: `${context}Livres déjà catalogués :\n${knownTxt}\n\nListe les livres visibles sur ces images.` });

  const response = await client.beta.messages.create({
    model: MODEL,
    max_tokens: 16000,
    system: SYSTEM_PROMPT,
    output_config: { effort: "medium", format: { type: "json_schema", schema: BOOK_SCHEMA } },
    betas: ["server-side-fallback-2026-07-01"],
    fallbacks: "default",
    messages: [{ role: "user", content }],
  });
  if (response.stop_reason === "refusal") throw new Error("La requête a été refusée par le modèle.");
  if (response.stop_reason === "max_tokens") {
    throw new Error("Réponse tronquée (trop de livres dans le lot) : réduisez FRAMES_PER_REQUEST.");
  }
  const text = response.content.find((b) => b.type === "text");
  if (!text || text.type !== "text") throw new Error("Réponse inattendue du modèle.");
  return (JSON.parse(text.text) as { books: DetectedBook[] }).books;
}

export interface ImportOptions {
  importId?: number | null;
  interval?: number;
  shelfHint?: string | null;
  onProgress?: (progress: number, message: string) => void;
}

/** Traite la vidéo et insère les livres détectés (statut « à vérifier »). */
export async function runImport(video: string, opts: ImportOptions = {}) {
  const { importId = null, interval = 1.5, shelfHint = null } = opts;
  const progress = opts.onProgress ?? ((p, m) => console.error(`[${(p * 100).toFixed(1).padStart(5)}%] ${m}`));
  const client = new Anthropic();

  const tag = importId ? `import_${importId}` : path.parse(video).name;
  const framesDir = path.join(FRAMES_DIR, tag);
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "recybook-"));
  let frames: string[];
  try {
    progress(0.02, "Extraction des images de la vidéo…");
    const extracted = await extractFrames(video, tmp, interval);
    if (!extracted.length) throw new Error("Aucune image n'a pu être extraite de la vidéo.");
    fs.mkdirSync(framesDir, { recursive: true });
    frames = extracted.map((f) => {
      const dest = path.join(framesDir, path.basename(f));
      fs.copyFileSync(f, dest);
      return dest;
    });
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
  progress(0.08, `${frames.length} images extraites, reconnaissance des livres…`);

  const known = (db().prepare("SELECT title, author FROM books ORDER BY id").all() as { title: string; author: string | null }[])
    .map((r) => `${r.title} — ${r.author ?? "?"}`);

  let added = 0;
  let skipped = 0;
  const batches: string[][] = [];
  for (let i = 0; i < frames.length; i += FRAMES_PER_REQUEST) batches.push(frames.slice(i, i + FRAMES_PER_REQUEST));

  for (const [b, batch] of batches.entries()) {
    let books: DetectedBook[];
    try {
      books = await identifyBooks(client, batch, known, shelfHint);
    } catch (err) {
      if (err instanceof Anthropic.AuthenticationError) {
        throw new Error("Clé API Anthropic invalide ou absente : définissez ANTHROPIC_API_KEY.");
      }
      if (err instanceof Anthropic.RateLimitError) {
        throw new Error("Limite de requêtes atteinte côté API Claude, réessayez plus tard.");
      }
      if (err instanceof Error && /authentication|api.?key/i.test(err.message) && !(err instanceof Anthropic.APIError)) {
        throw new Error("Clé API Anthropic absente : définissez ANTHROPIC_API_KEY.");
      }
      throw err;
    }
    let position = Number((db().prepare("SELECT COALESCE(MAX(position), 0) AS p FROM books").get() as { p: number }).p);
    for (const book of books) {
      const title = (book.title ?? "").trim();
      if (!title || book.already_listed || findDuplicate(title, book.author, book.publisher)) {
        skipped++;
        continue;
      }
      const idx = Math.min(Math.max(book.frame_index ?? 0, 0), batch.length - 1);
      insertBook({
        title,
        author: book.author,
        publisher: book.publisher,
        year: book.year,
        isbn: book.isbn,
        genre: book.genre,
        language: book.language,
        shelf: [shelfHint, book.shelf].filter(Boolean).join(" – ") || null,
        position: ++position,
        confidence: book.confidence,
        source_frame: `${tag}/${path.basename(batch[idx])}`,
        status: "a_verifier",
      }, importId);
      known.push(`${title} — ${book.author ?? "?"}`);
      added++;
    }
    progress(0.08 + (0.92 * (b + 1)) / batches.length,
      `Lot ${b + 1}/${batches.length} : ${added} livres ajoutés, ${skipped} doublons ignorés`);
  }
  return { frames: frames.length, added, skipped };
}

// ---- Ligne de commande ----
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const opt = (name: string) => {
    const i = args.indexOf(name);
    return i >= 0 ? args.splice(i, 2)[1] : undefined;
  };
  const interval = Number(opt("--interval") ?? 1.5);
  const shelfHint = opt("--shelf-hint") ?? null;
  const video = args[0];
  if (!video) {
    console.error('Usage : npm run import-video -- video.mp4 [--interval 1.5] [--shelf-hint "Armoire salon"]');
    process.exit(1);
  }
  runImport(path.resolve(video), { interval, shelfHint })
    .then((r) => console.log(JSON.stringify(r)))
    .catch((e) => { console.error(e instanceof Error ? e.message : e); process.exit(1); });
}
