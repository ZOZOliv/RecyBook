const $ = (sel) => document.querySelector(sel);
const api = async (url, opts = {}) => {
  const res = await fetch(url, opts);
  if (!res.ok) throw new Error((await res.json().catch(() => ({}))).detail || res.statusText);
  return res.status === 204 ? null : res.json();
};
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const STATUS = { a_verifier: "À vérifier", valide: "Validé" };

// ---------- Navigation ----------
document.querySelectorAll(".tab").forEach((btn) => btn.addEventListener("click", () => {
  document.querySelectorAll(".tab").forEach((b) => b.classList.toggle("active", b === btn));
  $("#view-library").hidden = btn.dataset.view !== "library";
  $("#view-import").hidden = btn.dataset.view !== "import";
  if (btn.dataset.view === "import") loadImports(); else refresh();
}));

// ---------- Bibliothèque ----------
async function loadStats() {
  const s = await api("/api/stats");
  $("#stats").innerHTML = [
    ["Livres", s.total], ["À vérifier", s.a_verifier], ["Lus", s.lus], ["Auteurs", s.auteurs],
  ].map(([l, v]) => `<div class="stat"><b>${v}</b><span class="muted">${l}</span></div>`).join("");
  fillSelect("#filter-shelf", s.shelves, "Toutes les étagères");
  fillSelect("#filter-genre", s.genres, "Tous les genres");
}

function fillSelect(sel, values, label) {
  const el = $(sel), cur = el.value;
  el.innerHTML = `<option value="">${label}</option>` + values.map((v) => `<option>${esc(v)}</option>`).join("");
  el.value = values.includes(cur) ? cur : "";
}

async function loadBooks() {
  const params = new URLSearchParams({
    q: $("#search").value, status: $("#filter-status").value, shelf: $("#filter-shelf").value,
    genre: $("#filter-genre").value, sort: $("#sort").value,
  });
  const books = await api(`/api/books?${params}`);
  $("#empty").hidden = books.length > 0;
  $("#books tbody").innerHTML = books.map((b) => `
    <tr data-id="${b.id}">
      <td class="thumb">${b.source_frame ? `<img loading="lazy" src="/frames/${esc(b.source_frame)}" alt="">` : ""}</td>
      <td><strong>${esc(b.title)}</strong>${b.year ? ` <span class="muted">(${b.year})</span>` : ""}</td>
      <td>${esc(b.author)}</td>
      <td>${esc(b.genre)}</td>
      <td>${esc(b.shelf)}</td>
      <td><span class="badge ${b.status}">${STATUS[b.status] || esc(b.status)}</span></td>
      <td><input type="checkbox" class="read-toggle" ${b.is_read ? "checked" : ""} aria-label="Lu"></td>
      <td>${b.status === "a_verifier" ? '<button class="validate">Valider</button>' : ""}</td>
    </tr>`).join("");
}

const refresh = () => Promise.all([loadStats(), loadBooks()]);

let searchTimer;
$("#search").addEventListener("input", () => { clearTimeout(searchTimer); searchTimer = setTimeout(loadBooks, 250); });
["#filter-status", "#filter-shelf", "#filter-genre", "#sort"].forEach((s) => $(s).addEventListener("change", loadBooks));

$("#books tbody").addEventListener("click", async (e) => {
  const tr = e.target.closest("tr");
  if (!tr) return;
  const id = tr.dataset.id;
  if (e.target.classList.contains("read-toggle")) {
    await api(`/api/books/${id}`, patchOpts({ is_read: e.target.checked }));
    return loadStats();
  }
  if (e.target.classList.contains("validate")) {
    await api(`/api/books/${id}`, patchOpts({ status: "valide" }));
    return refresh();
  }
  openDialog(await api(`/api/books/${id}`));
});

const patchOpts = (body) => ({ method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

$("#btn-validate-all").addEventListener("click", async () => {
  if (!confirm("Valider tous les livres « à vérifier » ?")) return;
  await api("/api/books/validate-all", { method: "POST" });
  refresh();
});

// ---------- Fiche livre ----------
const dialog = $("#book-dialog"), form = $("#book-form");
let editingId = null;

function openDialog(book = null) {
  editingId = book?.id ?? null;
  form.reset();
  $("#dialog-title").textContent = book ? "Modifier le livre" : "Ajouter un livre";
  $("#btn-delete").hidden = !book;
  const img = $("#frame-preview");
  img.hidden = !book?.source_frame;
  if (book?.source_frame) img.src = `/frames/${book.source_frame}`;
  if (book) for (const el of form.elements) {
    if (!el.name) continue;
    if (el.type === "checkbox") el.checked = !!book[el.name];
    else el.value = book[el.name] ?? "";
  }
  if (!book) form.elements.status.value = "valide";
  dialog.showModal();
}

$("#btn-add").addEventListener("click", () => openDialog());
$("#btn-cancel").addEventListener("click", () => dialog.close());

form.addEventListener("submit", async (e) => {
  e.preventDefault();
  const data = {};
  for (const el of form.elements) {
    if (!el.name) continue;
    if (el.type === "checkbox") data[el.name] = el.checked;
    else if (el.type === "number") data[el.name] = el.value === "" ? null : Number(el.value);
    else data[el.name] = el.value.trim() || null;
  }
  try {
    if (editingId) await api(`/api/books/${editingId}`, patchOpts(data));
    else await api("/api/books", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(data) });
    dialog.close();
    refresh();
  } catch (err) { alert(err.message); }
});

$("#btn-delete").addEventListener("click", async () => {
  if (!editingId || !confirm("Supprimer ce livre ?")) return;
  await api(`/api/books/${editingId}`, { method: "DELETE" });
  dialog.close();
  refresh();
});

// ---------- Import vidéo ----------
let pollTimer;
async function loadImports() {
  const imports = await api("/api/imports");
  $("#imports").innerHTML = imports.map((i) => `
    <li>
      <strong>${esc(i.filename)}</strong> — <span class="muted">${esc(i.created_at)}</span><br>
      ${i.status === "en_cours" || i.status === "en_attente" ? `<progress value="${i.progress}" max="1"></progress>` : ""}
      <span class="${i.status === "erreur" ? "badge a_verifier" : "muted"}">${esc(i.message || i.status)}</span>
    </li>`).join("") || '<li class="muted">Aucun import pour le moment.</li>';
  clearTimeout(pollTimer);
  if (imports.some((i) => i.status === "en_cours" || i.status === "en_attente")) pollTimer = setTimeout(loadImports, 2000);
}

$("#import-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const btn = e.target.querySelector("button[type=submit]");
  btn.disabled = true; btn.textContent = "Envoi de la vidéo…";
  try {
    await api("/api/imports", { method: "POST", body: new FormData(e.target) });
    e.target.reset();
    loadImports();
  } catch (err) { alert(err.message); }
  finally { btn.disabled = false; btn.textContent = "Lancer l'analyse"; }
});

refresh();
