import { useQuery } from "@tanstack/react-query";
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from "react";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { api } from "../api/client";
import type { Me } from "../api/types";
import { Banners } from "./Banners";

const NAV = [
  { to: "/", label: "Caja", icon: "◉", end: true },
  { to: "/clientes", label: "Clientes", icon: "☰" },
  { to: "/movimientos", label: "Movimientos", icon: "⇄" },
  { to: "/panel", label: "Panel", icon: "▤" },
  { to: "/importar", label: "Importar", icon: "⇪" },
  { to: "/auditoria", label: "Auditoría", icon: "✓" },
  { to: "/ajustes", label: "Ajustes", icon: "⚙" },
];

const TITLES: [RegExp, string][] = [
  [/^\/$/, "Caja"],
  [/^\/clientes\/\d+/, "Cliente"],
  [/^\/clientes/, "Clientes"],
  [/^\/movimientos/, "Movimientos"],
  [/^\/panel/, "Panel"],
  [/^\/importar/, "Importar"],
  [/^\/auditoria/, "Auditoría"],
  [/^\/ajustes/, "Ajustes"],
  [/^\/ticket/, "Ticket"],
];

export function Layout() {
  const [menu, setMenu] = useState(false);
  const loc = useLocation();
  const nav = useNavigate();
  const search = useRef<HTMLInputElement>(null);
  const openBtn = useRef<HTMLButtonElement>(null);
  const main = useRef<HTMLElement>(null);
  const workspace = useRef<HTMLDivElement>(null);
  const first = useRef(true);

  // `inert` aún no es un atributo booleano tipado en React 18: se fija por propiedad del DOM
  useEffect(() => {
    if (workspace.current) workspace.current.inert = menu;
  }, [menu]);

  // Al cerrar hay que quitar `inert` ANTES de enfocar el botón (un elemento inert no admite foco)
  const closeMenu = useCallback(() => {
    if (workspace.current) workspace.current.inert = false;
    setMenu(false);
    openBtn.current?.focus();
  }, []);
  const { data: me } = useQuery({
    queryKey: ["me"],
    queryFn: () => api<Me>("/me"),
  });

  const title =
    TITLES.find(([re]) => re.test(loc.pathname))?.[1] ?? "No encontrado";

  // título del documento, foco en el contenido y cierre del menú móvil al cambiar de vista
  useEffect(() => {
    document.title = `${title} · Papelería`;
    setMenu(false);
    if (first.current) {
      first.current = false;
      return;
    }
    // No robar el foco si la vista ya lo ha tomado (p. ej. el buscador de la caja con autoFocus)
    if (!main.current?.contains(document.activeElement)) {
      main.current?.focus({ preventScroll: true });
    }
    window.scrollTo(0, 0);
  }, [loc.pathname, title]);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        search.current?.focus();
      } else if (e.key === "Escape" && menu) {
        closeMenu();
      }
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [menu, closeMenu]);

  function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const q = search.current?.value.trim() ?? "";
    nav(q ? `/clientes?q=${encodeURIComponent(q)}` : "/clientes");
  }

  return (
    <>
      <aside
        className={`sidebar ${menu ? "open" : ""}`}
        id="sidebar"
        aria-label="Navegación"
      >
        <div className="brand">
          Papelería<small>Saldo de clientes</small>
        </div>
        <button type="button" className="menu-close" onClick={closeMenu}>
          Cerrar menú
        </button>
        <nav aria-label="Principal">
          {NAV.map((n) => (
            <NavLink key={n.to} to={n.to} end={n.end}>
              <span aria-hidden="true">{n.icon}</span> {n.label}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <small className="muted">Sesión</small>
          <div>{me?.name ?? "…"}</div>
        </div>
      </aside>
      <div className="workspace" ref={workspace}>
        <header className="topbar">
          <button
            ref={openBtn}
            type="button"
            className="menu-open"
            aria-controls="sidebar"
            aria-expanded={menu}
            onClick={() => setMenu(true)}
          >
            Menú
          </button>
          <form className="search" role="search" onSubmit={submit}>
            <label htmlFor="global-search" className="sr-only">
              Buscar clientes
            </label>
            <input
              id="global-search"
              ref={search}
              type="search"
              placeholder="Buscar cliente…"
              autoComplete="off"
            />
            <kbd aria-hidden="true">Ctrl K</kbd>
          </form>
        </header>
        <Banners />
        <main id="main" ref={main} tabIndex={-1}>
          <Outlet />
        </main>
      </div>
    </>
  );
}
