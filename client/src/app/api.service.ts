import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Book, BookDraft, BookFilters, Stats } from './models';

/** Accès à l'API REST du serveur Express. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  listBooks(filters: BookFilters): Promise<Book[]> {
    const params = new HttpParams({ fromObject: { ...filters } });
    return this.call(this.http.get<Book[]>('/api/books', { params }));
  }

  getBook(id: number): Promise<Book> {
    return this.call(this.http.get<Book>(`/api/books/${id}`));
  }

  createBook(book: Partial<BookDraft>): Promise<Book> {
    return this.call(this.http.post<Book>('/api/books', book));
  }

  updateBook(id: number, changes: Partial<BookDraft>): Promise<Book> {
    return this.call(this.http.patch<Book>(`/api/books/${id}`, changes));
  }

  deleteBook(id: number): Promise<void> {
    return this.call(this.http.delete<void>(`/api/books/${id}`));
  }

  validateAll(): Promise<{ validated: number }> {
    return this.call(this.http.post<{ validated: number }>('/api/books/validate-all', {}));
  }

  stats(): Promise<Stats> {
    return this.call(this.http.get<Stats>('/api/stats'));
  }

  /** Convertit l'Observable en Promise et extrait le message d'erreur du serveur. */
  private async call<T>(obs: import('rxjs').Observable<T>): Promise<T> {
    try {
      return await firstValueFrom(obs);
    } catch (err) {
      if (err instanceof HttpErrorResponse) {
        throw new Error(err.error?.detail ?? err.message);
      }
      throw err;
    }
  }
}
