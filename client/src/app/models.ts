export type BookStatus = 'a_verifier' | 'valide';

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
  status: BookStatus;
  is_read: boolean;
  notes: string | null;
  confidence: number | null;
  source_frame: string | null;
  /** URL de la couverture officielle. */
  cover_url: string | null;
  /** null = recherche en attente, found = trouvée, absent = introuvable. */
  cover_status: 'found' | 'absent' | null;
  updated_at: string;
}

/** Champs modifiables d'un livre (création ou édition). */
export type BookDraft = Omit<Book, 'id' | 'confidence' | 'source_frame' | 'cover_status' | 'updated_at'>;

export interface Stats {
  total: number;
  a_verifier: number;
  lus: number;
  auteurs: number;
  shelves: string[];
  genres: string[];
}

export interface BookFilters {
  q: string;
  status: string;
  shelf: string;
  genre: string;
  sort: 'position' | 'title' | 'author' | 'recent';
}

/** Adresse de la couverture servie par RecyBook ; le paramètre force le rechargement après modification. */
export const coverSrc = (book: Book) => `/api/books/${book.id}/cover?v=${encodeURIComponent(book.cover_url ?? '')}`;

export const STATUS_LABELS: Record<BookStatus, string> = {
  a_verifier: 'À vérifier',
  valide: 'Validé',
};
