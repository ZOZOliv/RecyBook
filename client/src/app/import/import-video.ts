import { Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../api.service';
import { VideoImport } from '../models';

/** Envoi d'une vidéo d'armoire et suivi de l'analyse. */
@Component({
  selector: 'app-import-video',
  imports: [FormsModule],
  templateUrl: './import-video.html',
})
export class ImportVideo {
  private readonly api = inject(ApiService);

  protected readonly imports = signal<VideoImport[]>([]);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  protected file: File | null = null;
  protected shelfHint = '';
  protected interval = 1.5;
  private pollTimer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    this.loadImports();
    inject(DestroyRef).onDestroy(() => clearTimeout(this.pollTimer));
  }

  protected isRunning(i: VideoImport): boolean {
    return i.status === 'en_cours' || i.status === 'en_attente';
  }

  private async loadImports(): Promise<void> {
    clearTimeout(this.pollTimer);
    try {
      const list = await this.api.listImports();
      this.imports.set(list);
      if (list.some((i) => this.isRunning(i))) this.pollTimer = setTimeout(() => this.loadImports(), 2000);
    } catch (err) {
      this.error.set((err as Error).message);
    }
  }

  protected onFile(event: Event): void {
    this.file = (event.target as HTMLInputElement).files?.[0] ?? null;
  }

  protected async submit(form: HTMLFormElement): Promise<void> {
    if (!this.file) return;
    const data = new FormData();
    data.append('video', this.file);
    data.append('shelf_hint', this.shelfHint);
    data.append('interval', String(this.interval));
    this.sending.set(true);
    this.error.set(null);
    try {
      await this.api.startImport(data);
      form.reset();
      this.file = null;
      this.shelfHint = '';
      this.interval = 1.5;
      this.loadImports();
    } catch (err) {
      this.error.set((err as Error).message);
    } finally {
      this.sending.set(false);
    }
  }
}
