import { Component, DestroyRef, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../api.service';
import { BookDialog } from '../book-dialog/book-dialog';
import { Book, BookFilters, STATUS_LABELS, Stats, coverSrc } from '../models';

@Component({
  selector: 'app-library',
  imports: [FormsModule, BookDialog],
  templateUrl: './library.html',
})
export class Library {
  private readonly api = inject(ApiService);

  protected readonly statusLabels = STATUS_LABELS;
  protected readonly coverSrc = coverSrc;
  private coverPoll: ReturnType<typeof setTimeout> | undefined;
  protected readonly books = signal<Book[]>([]);
  protected readonly stats = signal<Stats | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  /** Livre ouvert dans la fiche : un livre existant, 'new' pour un ajout, ou null. */
  protected readonly editing = signal<Book | 'new' | null>(null);

  protected readonly filters = signal<BookFilters>({ q: '', status: '', shelf: '', genre: '', sort: 'position' });
  /** Recherche tapée, appliquée aux filtres après une courte pause. */
  protected readonly search = signal('');

  protected readonly statCards = computed(() => {
    const s = this.stats();
    return s
      ? [
          { label: 'Livres', value: s.total },
          { label: 'À vérifier', value: s.a_verifier },
          { label: 'Lus', value: s.lus },
          { label: 'Auteurs', value: s.auteurs },
        ]
      : [];
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.coverPoll));
    // Recharge la liste à chaque changement de filtre.
    effect(() => {
      const f = this.filters();
      untracked(() => this.loadBooks(f));
    });
    // Applique la recherche 250 ms après la dernière frappe.
    effect((onCleanup) => {
      const q = this.search();
      const timer = setTimeout(() => this.filters.update((f) => (f.q === q ? f : { ...f, q })), 250);
      onCleanup(() => clearTimeout(timer));
    });
    this.loadStats();
  }

  protected setFilter<K extends keyof BookFilters>(key: K, value: BookFilters[K]): void {
    this.filters.update((f) => ({ ...f, [key]: value }));
  }

  private async loadBooks(filters: BookFilters): Promise<void> {
    try {
      const books = await this.api.listBooks(filters);
      this.books.set(books);
      this.error.set(null);
      // Les couvertures sont cherchées en arrière-plan par le serveur : on rafraîchit tant qu'il en reste.
      clearTimeout(this.coverPoll);
      if (books.some((b) => b.cover_status === null)) {
        this.coverPoll = setTimeout(() => this.loadBooks(this.filters()), 3000);
      }
    } catch (err) {
      this.error.set((err as Error).message);
    } finally {
      this.loading.set(false);
    }
  }

  private async loadStats(): Promise<void> {
    try {
      this.stats.set(await this.api.stats());
    } catch (err) {
      this.error.set((err as Error).message);
    }
  }

  protected refresh(): void {
    this.loadBooks(this.filters());
    this.loadStats();
  }

  protected async toggleRead(book: Book, event: Event): Promise<void> {
    const isRead = (event.target as HTMLInputElement).checked;
    const updated = await this.api.updateBook(book.id, { is_read: isRead });
    this.books.update((list) => list.map((b) => (b.id === book.id ? updated : b)));
    this.loadStats();
  }

  protected async validate(book: Book): Promise<void> {
    await this.api.updateBook(book.id, { status: 'valide' });
    this.refresh();
  }

  protected async validateAll(): Promise<void> {
    if (!confirm('Valider tous les livres « à vérifier » ?')) return;
    await this.api.validateAll();
    this.refresh();
  }

  protected onDialogClosed(changed: boolean): void {
    this.editing.set(null);
    if (changed) this.refresh();
  }
}
