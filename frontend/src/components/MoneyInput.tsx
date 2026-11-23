import {
  forwardRef,
  useEffect,
  useId,
  useState,
  type InputHTMLAttributes,
} from "react";
import { centsToInput, formatCents, parseEuros } from "../lib/money";

interface Props extends Omit<
  InputHTMLAttributes<HTMLInputElement>,
  "value" | "onChange" | "type"
> {
  label: string;
  /** Céntimos o null si está vacío / es inválido. */
  valueCents: number | null;
  onChangeCents: (cents: number | null) => void;
  allowNegative?: boolean;
  error?: string;
}

/** Entrada de importes: el usuario escribe "12,50"; el estado guarda céntimos enteros. */
export const MoneyInput = forwardRef<HTMLInputElement, Props>(
  function MoneyInput(
    {
      label,
      valueCents,
      onChangeCents,
      allowNegative = false,
      error,
      id,
      ...rest
    },
    ref,
  ) {
    const auto = useId();
    const inputId = id ?? auto;
    const [text, setText] = useState(
      valueCents === null ? "" : centsToInput(valueCents),
    );
    const [invalid, setInvalid] = useState(false);

    // Sincroniza cuando el padre cambia el valor desde fuera (botones de importe rápido, reset)
    useEffect(() => {
      const current = text.trim() === "" ? null : parseEuros(text);
      if (current !== valueCents) {
        setText(valueCents === null ? "" : centsToInput(valueCents));
        setInvalid(false);
      }
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [valueCents]);
    function change(raw: string) {
      const clean = raw.replace(/[^\d.,\-€\s]/g, "");
      setText(clean);
      if (clean.trim() === "") {
        setInvalid(false);
        onChangeCents(null);
        return;
      }
      const c = parseEuros(clean);
      const ok = c !== null && (allowNegative || c >= 0);
      setInvalid(!ok);
      onChangeCents(ok ? c : null);
    }

    const shown =
      error ?? (invalid ? "Importe no válido (máximo 2 decimales)" : undefined);
    return (
      <div className="field">
        <label htmlFor={inputId}>{label}</label>
        <div className="money-wrap">
          <input
            {...rest}
            ref={ref}
            id={inputId}
            type="text"
            inputMode="decimal"
            autoComplete="off"
            className="money"
            value={text}
            onChange={(e) => change(e.target.value)}
            onBlur={() => {
              if (valueCents !== null) setText(centsToInput(valueCents));
            }}
            aria-invalid={shown ? true : undefined}
            aria-describedby={shown ? `${inputId}-err` : undefined}
          />
          <span aria-hidden="true">€</span>
        </div>
        {valueCents !== null && !shown && (
          <small className="muted">{formatCents(valueCents)}</small>
        )}
        {shown && (
          <small id={`${inputId}-err`} className="field-error" role="alert">
            {shown}
          </small>
        )}
      </div>
    );
  },
);
