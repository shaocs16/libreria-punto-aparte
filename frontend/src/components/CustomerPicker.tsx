import { useQuery } from "@tanstack/react-query";
import { useEffect, useId, useRef, useState } from "react";
import { api, qs } from "../api/client";
import type { Suggestion } from "../api/types";
import { useDebounce } from "../hooks/useDebounce";
import { formatCents } from "../lib/money";

/** Combobox accesible con autocompletado (debounce 150 ms, flechas + Enter + Escape). */
export function CustomerPicker({
  onPick,
  autoFocus,
}: {
  onPick: (s: Suggestion) => void;
  autoFocus?: boolean;
}) {
  const [text, setText] = useState("");
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const q = useDebounce(text.trim(), 150);
  const listId = useId();
  const input = useRef<HTMLInputElement>(null);

  const { data = [], isFetching } = useQuery({
    queryKey: ["suggest", q],
    queryFn: ({ signal }) =>
      api<Suggestion[]>(`/customers/suggest${qs({ q })}`, { signal }),
    enabled: q.length > 0,
    staleTime: 10_000,
  });

  useEffect(() => setActive(0), [data]);

  function pick(s: Suggestion) {
    onPick(s);
    setText("");
    setOpen(false);
  }

  const showList = open && q.length > 0;
  return (
    <div className="picker">
      <label htmlFor={`${listId}-in`}>
        Buscar cliente (nombre, código, teléfono o documento)
      </label>
      <input
        ref={input}
        id={`${listId}-in`}
        role="combobox"
        aria-expanded={showList}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={
          showList && data[active] ? `${listId}-${data[active].id}` : undefined
        }
        autoComplete="off"
        autoFocus={autoFocus}
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          setOpen(true);
        }}
        onBlur={() => setTimeout(() => setOpen(false), 120)}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") {
            e.preventDefault();
            setOpen(true);
            setActive((a) => Math.min(a + 1, Math.max(data.length - 1, 0)));
          } else if (e.key === "ArrowUp") {
            e.preventDefault();
            setActive((a) => Math.max(a - 1, 0));
          } else if (e.key === "Enter" && showList && data[active]) {
            e.preventDefault();
            pick(data[active]);
          } else if (e.key === "Escape") {
            setOpen(false);
          }
        }}
      />
      {showList && (
        <ul id={listId} role="listbox" className="suggestions">
          {data.map((s, i) => (
            <li
              key={s.id}
              id={`${listId}-${s.id}`}
              role="option"
              aria-selected={i === active}
              className={i === active ? "active" : ""}
              onMouseDown={(e) => {
                e.preventDefault();
                pick(s);
              }}
            >
              <span>
                <strong>{s.name}</strong>{" "}
                <small className="muted">
                  {s.code}
                  {s.phone ? ` · ${s.phone}` : ""}
                </small>
              </span>
              <span className="num">{formatCents(s.balanceCents)}</span>
            </li>
          ))}
          {!isFetching && data.length === 0 && (
            <li className="empty" role="option" aria-selected="false">
              Sin resultados
            </li>
          )}
        </ul>
      )}
    </div>
  );
}
