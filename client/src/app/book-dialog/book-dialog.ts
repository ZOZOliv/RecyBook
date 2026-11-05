import { Component, ElementRef, afterNextRender, inject, input, output, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../api.service';
import { Book, BookDraft } from '../models';

const EMPTY: BookDraft = {
  title: '', author: null, publisher: null, year: null, isbn: null, genre: null, language: null,
  shelf: null, position: null, status: 'valide', is_read: false, notes: null,
};

/** Fiche d'un livre (ajout / modification / suppression) dans une boîte de dialogue native. */
@Component({
  selector: 'app-book-dialog',
  imports: [FormsModule],
  templateUrl: './book-dialog.html',
})
export class BookDialog {
  private readonly api = inject(ApiService);
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');

  /** Livre à modifier, ou null pour un ajout. */
  readonly book = input<Book | null>(null);
  /** Émis à la fermeture ; true si la base a été modifiée. */
  readonly closed = output<boolean>();

  protected draft: BookDraft = { ...EMPTY };
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    afterNextRender(() => {
      const book = this.book();
      if (book) {
        const { id: _id, confidence: _c, source_frame: _s, ...fields } = book;
        this.draft = { ...EMPTY, ...fields };
      }
      this.dialog().nativeElement.showModal();
    });
  }

  /** Les champs vides sont envoyés comme null. */
  private payload(): Partial<BookDraft> {
    const out: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(this.draft)) {
      out[key] = typeof value === 'string' ? value.trim() || null : value ?? null;
    }
    return out as Partial<BookDraft>;
  }

  protected async save(): Promise<void> {
    this.saving.set(true);
    this.error.set(null);
    try {
      const book = this.book();
      if (book) await this.api.updateBook(book.id, this.payload());
      else await this.api.createBook(this.payload());
      this.close(true);
    } catch (err) {
      this.error.set((err as Error).message);
    } finally {
      this.saving.set(false);
    }
  }

  protected async remove(): Promise<void> {
    const book = this.book();
    if (!book || !confirm('Supprimer ce livre ?')) return;
    await this.api.deleteBook(book.id);
    this.close(true);
  }

  protected close(changed = false): void {
    this.dialog().nativeElement.close();
    this.closed.emit(changed);
  }
}
