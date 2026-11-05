import { Routes } from '@angular/router';
import { Library } from './library/library';
import { ImportVideo } from './import/import-video';

export const routes: Routes = [
  { path: 'bibliotheque', component: Library, title: 'RecyBook – Bibliothèque' },
  { path: 'import', component: ImportVideo, title: 'RecyBook – Importer une vidéo' },
  { path: '**', redirectTo: 'bibliotheque' },
];
