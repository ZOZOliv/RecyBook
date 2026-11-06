/**
 * Charge dans la base les livres déjà identifiés depuis la vidéo de l'armoire (npm run seed).
 * Option --if-empty : ne fait rien si la base contient déjà des livres (démarrage du conteneur Docker).
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { db, findDuplicate, FRAMES_DIR, insertBook, ROOT, type BookInput } from "./db.js";

const SEED_DIR = path.join(ROOT, "seed");
const TAG = "armoire_video";

export function loadSeed(seedFile = path.join(SEED_DIR, "armoire_video.json")) {
  const data = JSON.parse(fs.readFileSync(seedFile, "utf-8")) as { books: BookInput[] };
  const dest = path.join(FRAMES_DIR, TAG);
  fs.mkdirSync(dest, { recursive: true });
  for (const f of fs.readdirSync(path.join(SEED_DIR, "frames"))) {
    if (f.endsWith(".jpg")) fs.copyFileSync(path.join(SEED_DIR, "frames", f), path.join(dest, f));
  }
  let added = 0;
  let skipped = 0;
  for (const book of data.books) {
    if (findDuplicate(book.title, book.author, book.publisher)) {
      skipped++;
      continue;
    }
    insertBook({
      ...book,
      source_frame: book.source_frame ? `${TAG}/${book.source_frame}` : null,
      status: "a_verifier",
    });
    added++;
  }
  return { added, skipped };
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const count = Number((db().prepare("SELECT COUNT(*) AS n FROM books").get() as { n: number }).n);
  if (process.argv.includes("--if-empty") && count > 0) {
    console.log(`Base déjà initialisée (${count} livres), rien à charger.`);
  } else {
    console.log(loadSeed());
  }
}
