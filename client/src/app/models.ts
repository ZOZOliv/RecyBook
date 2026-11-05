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
}

/** Champs modifiables d'un livre (création ou édition). */
export type BookDraft = Omit<Book, 'id' | 'confidence' | 'source_frame'>;

export interface Stats {
  total: number;
  a_verifier: number;
  lus: number;
  auteurs: number;
  shelves: string[];
  genres: string[];
}

export interface VideoImport {
  id: number;
  filename: string;
  status: 'en_attente' | 'en_cours' | 'termine' | 'erreur';
  progress: number;
  message: string | null;
  frames: number;
  books_added: number;
  created_at: string;
}

export interface BookFilters {
  q: string;
  status: string;
  shelf: string;
  genre: string;
  sort: 'position' | 'title' | 'author' | 'recent';
}

export const STATUS_LABELS: Record<BookStatus, string> = {
  a_verifier: 'À vérifier',
  valide: 'Validé',
};
