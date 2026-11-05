import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <header>
      <h1>📚 RecyBook</h1>
      <nav>
        <a class="tab" routerLink="/bibliotheque" routerLinkActive="active">Bibliothèque</a>
        <a class="tab" routerLink="/import" routerLinkActive="active">Importer une vidéo</a>
      </nav>
    </header>
    <main>
      <router-outlet />
    </main>
  `,
})
export class App {}
