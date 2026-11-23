import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm, type Resolver } from "react-hook-form";
import { z } from "zod";
import { api, friendly } from "../../api/client";
import type { Customer } from "../../api/types";
import { useToast } from "../../components/Toast";

const base = z.object({
  name: z
    .string()
    .trim()
    .min(1, "Obligatorio")
    .max(200, "Máximo 200 caracteres"),
  phone: z
    .string()
    .trim()
    .max(30)
    .regex(/^[0-9+()\-. ]*$/, "Solo dígitos, + ( ) - .")
    .optional()
    .or(z.literal("")),
  document: z
    .string()
    .trim()
    .max(30)
    .regex(/^[A-Za-z0-9\-./]*$/, "Formato no válido")
    .optional()
    .or(z.literal("")),
  notes: z.string().trim().max(1000).optional().or(z.literal("")),
});
// Al crear, el código lo asigna el servidor (C001, C002, ...); al editar sigue siendo obligatorio.
const createSchema = base.extend({ code: z.string().trim().max(40).optional().or(z.literal("")) });
const editSchema = base.extend({
  code: z.string().trim().min(1, "Obligatorio").max(40, "Máximo 40 caracteres"),
});
type Values = z.infer<typeof editSchema>;

export function CustomerForm({
  customer,
  onSaved,
  onCancel,
}: {
  customer?: Customer;
  onSaved: (c: Customer) => void;
  onCancel?: () => void;
}) {
  const qc = useQueryClient();
  const { notify } = useToast();
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<Values>({
    resolver: zodResolver(customer ? editSchema : createSchema) as Resolver<Values>,
    defaultValues: {
      code: customer?.code ?? "",
      name: customer?.name ?? "",
      phone: customer?.phone ?? "",
      document: customer?.document ?? "",
      notes: customer?.notes ?? "",
    },
  });

  const m = useMutation({
    mutationFn: (v: Values) =>
      customer
        ? api<Customer>(`/customers/${customer.id}`, {
            method: "PUT",
            body: v,
            ifMatch: customer.version,
          })
        : api<Customer>("/customers", { body: { ...v, code: undefined } }),
    onSuccess: (c) => {
      qc.invalidateQueries({ queryKey: ["customers"] });
      qc.invalidateQueries({ queryKey: ["customer"] });
      qc.invalidateQueries({ queryKey: ["suggest"] });
      notify(customer ? "Cliente actualizado." : "Cliente creado.");
      onSaved(c);
    },
    onError: (e) => notify(friendly(e), "error"),
  });

  const field = (
    name: keyof Values,
    label: string,
    props: Record<string, unknown> = {},
  ) => (
    <div className="field">
      <label htmlFor={`cf-${name}`}>{label}</label>
      <input
        id={`cf-${name}`}
        {...register(name)}
        {...props}
        aria-invalid={errors[name] ? true : undefined}
        aria-describedby={errors[name] ? `cf-${name}-err` : undefined}
      />
      {errors[name] && (
        <small id={`cf-${name}-err`} className="field-error" role="alert">
          {errors[name]?.message}
        </small>
      )}
    </div>
  );

  return (
    <form
      className="stack"
      onSubmit={handleSubmit((v) => !m.isPending && m.mutate(v))}
      noValidate
    >
      <div className="grid-2">
        {customer &&
          field("code", "Código (no se puede modificar)", {
            readOnly: true,
            "aria-readonly": true,
            tabIndex: -1,
          })}
        {field("name", "Nombre", { autoFocus: !customer })}
        {field("phone", "Teléfono", { inputMode: "tel" })}
        {field("document", "Documento (DNI/NIF)")}
      </div>
      {!customer && (
        <p className="muted">El código del cliente se asigna automáticamente.</p>
      )}
      {field("notes", "Notas")}
      <div className="actions">
        {onCancel && (
          <button type="button" className="btn secondary" onClick={onCancel}>
            Cancelar
          </button>
        )}
        <button type="submit" className="btn primary" disabled={m.isPending}>
          {m.isPending
            ? "Guardando…"
            : customer
              ? "Guardar cambios"
              : "Crear cliente"}
        </button>
      </div>
    </form>
  );
}
