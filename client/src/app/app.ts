import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: `
    <header>
      <h1>📚 RecyBook</h1>
    </header>
    <main>
      <router-outlet />
    </main>
  `,
})
export class App {}
