import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

// ============================================================
// widget-ventas-hoy (v1, 2026-10-23) — backend del widget Android "CORAZ Ventas"
// GET  header x-widget-token: <token>   (o ?t=<token>)
// Devuelve las ventas del día (hora ARG) con el mismo criterio que el panel de ventas
// del dashboard: canceladas fuera de totales, pack dividido por ML no cuenta como cancelada,
// lista de ventas individuales con stock (depósito + Full) y motivo de cancelación.
// Tokens hasheados (sha256) en widget_accesos. Revocar: update widget_accesos set activo=false.
// Solo lectura. No usa el JWT de Supabase (verify_jwt=false; auth propia por token).
// ============================================================

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const svc = createClient(SUPABASE_URL, SERVICE);

const J = (b: unknown, s = 200) =>
  new Response(JSON.stringify(b), { status: s, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });

async function sha256(txt: string): Promise<string> {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(txt));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function acceso(t: string | null): Promise<boolean> {
  if (!t || t.length < 20 || t.length > 100) return false;
  const { data } = await svc.from("widget_accesos").select("id, activo").eq("token_hash", await sha256(t)).maybeSingle();
  if (!data || !data.activo) return false;
  await svc.from("widget_accesos").update({ last_used_at: new Date().toISOString() }).eq("id", data.id);
  return true;
}

const esCancelada = (s: unknown) => String(s ?? "").toLowerCase().includes("cancel");

// Mismo diccionario que dashboard-ventas (motivos de cancelación en criollo).
const QUIEN: Record<string, string> = { buyer: "Comprador", seller: "Vos", meli: "Mercado Libre" };
const MOTIVOS: Record<string, { texto: string; real?: boolean }> = {
  pack_splitted: { texto: "ML dividió el carrito (sigue en otra orden)", real: false },
  buyer_cancel_express: { texto: "El comprador se arrepintió" },
  mediations: { texto: "Cancelada por reclamo" },
  shipment_not_delivered: { texto: "El envío no se entregó" },
  fraud: { texto: "ML: sospecha de fraude" },
  unknown: { texto: "ML no informó la causa" },
  feedback_shipment_other: { texto: "La cancelaste vos: problema con el envío" },
  feedback_shipment_wrong_cost: { texto: "La cancelaste vos: costo de envío mal calculado" },
  feedback_shipment_high_cost: { texto: "La cancelaste vos: envío muy caro" },
  feedback_delivery_denied: { texto: "El comprador rechazó el paquete" },
  feedback_unknown: { texto: "La cancelaste vos" },
};
const GRUPOS: Record<string, string> = {
  buyer: "La canceló el comprador", seller: "La cancelaste vos", mediations: "Cancelada por reclamo",
  shipment: "Problema con el envío", delivery: "Problema con la entrega", fraud: "ML: sospecha de fraude",
  internal: "Cancelada por Mercado Libre",
};
// deno-lint-ignore no-explicit-any
function motivo(cd: any): { texto: string; quien: string | null; real: boolean } {
  if (!cd || cd.sin_detalle) return { texto: "ML no informó el motivo", quien: null, real: true };
  const m = cd.code ? MOTIVOS[String(cd.code)] : undefined;
  const texto = m?.texto ?? (cd.group && GRUPOS[cd.group] ? GRUPOS[cd.group] : (cd.description ?? "Motivo no identificado"));
  return { texto, quien: QUIEN[String(cd.requested_by ?? "")] ?? null, real: m?.real !== false };
}

// Fecha ARG (UTC-3, sin horario de verano) → límites UTC
function diaArg(offsetDias = 0): { ini: Date; fin: Date; fecha: string } {
  const ahoraArg = new Date(Date.now() - 3 * 3600_000 + offsetDias * 86400_000);
  const fecha = ahoraArg.toISOString().slice(0, 10);
  const ini = new Date(`${fecha}T00:00:00.000-03:00`);
  const fin = new Date(`${fecha}T23:59:59.999-03:00`);
  return { ini, fin, fecha };
}

type Linea = {
  ml_order_id: string; sku: string; sale_date: string; units: number;
  gross_amount: number; net_amount: number; status: string; tipo_envio: string | null;
  // deno-lint-ignore no-explicit-any
  cancel_detail: any;
};

function totales(rows: Linea[]) {
  let gross = 0, net = 0, units = 0, ventas = 0, canc = 0, cancGross = 0;
  for (const r of rows) {
    if (esCancelada(r.status)) {
      if (motivo(r.cancel_detail).real) { canc++; cancGross += Number(r.gross_amount ?? 0); }
      continue;
    }
    gross += Number(r.gross_amount ?? 0); net += Number(r.net_amount ?? 0);
    units += Number(r.units ?? 0); ventas++;
  }
  return { gross, net, units, ventas, ticket: ventas ? gross / ventas : 0, canceladas: canc, canceladas_gross: cancGross };
}

Deno.serve(async (req) => {
  try {
    if (req.method !== "GET") return J({ ok: false, error: "método no permitido" }, 405);
    const url = new URL(req.url);
    const t = req.headers.get("x-widget-token") ?? url.searchParams.get("t");
    if (!(await acceso(t))) return J({ ok: false, error: "token inválido o revocado" }, 401);

    const hoy = diaArg(0);
    const ayer = diaArg(-1);
    const ayerMismaHora = new Date(Date.now() - 86400_000);
    const cols = "ml_order_id, sku, sale_date, units, gross_amount, net_amount, status, tipo_envio, cancel_detail:raw_json->order_summary->cancel_detail";

    const [qHoy, qAyer] = await Promise.all([
      svc.from("ml_ventas_lineas").select(cols).gte("sale_date", hoy.ini.toISOString()).lte("sale_date", hoy.fin.toISOString()).order("sale_date", { ascending: false }).limit(300),
      svc.from("ml_ventas_lineas").select(cols).gte("sale_date", ayer.ini.toISOString()).lte("sale_date", ayer.fin.toISOString()).limit(300),
    ]);
    if (qHoy.error) throw new Error(qHoy.error.message);
    if (qAyer.error) throw new Error(qAyer.error.message);
    const hoyRows = (qHoy.data ?? []) as Linea[];
    const ayerRows = (qAyer.data ?? []) as Linea[];

    const tHoy = totales(hoyRows);
    const tAyerCompleto = totales(ayerRows);
    const tAyerHora = totales(ayerRows.filter((r) => new Date(r.sale_date) <= ayerMismaHora));

    // Stock por SKU: depósito (stock_sku, ya resuelve kits) + Full (stock_full sumado por SKU)
    const skus = [...new Set(hoyRows.map((r) => String(r.sku)))];
    const stock = new Map<string, { dep: number | null; kit: boolean; full: number | null }>();
    if (skus.length) {
      const [st, kits, full] = await Promise.all([
        svc.from("stock_sku").select("sku, stock_actual").in("sku", skus),
        svc.from("sku_composicion").select("kit_sku").in("kit_sku", skus),
        svc.from("stock_full").select("sku, stock_ml").in("sku", skus),
      ]);
      const setKits = new Set(((kits.data ?? []) as { kit_sku: string }[]).map((k) => k.kit_sku));
      for (const s of skus) stock.set(s, { dep: null, kit: setKits.has(s), full: null });
      for (const r of (st.data ?? []) as { sku: string; stock_actual: number }[]) stock.get(r.sku)!.dep = Number(r.stock_actual ?? 0);
      for (const r of (full.data ?? []) as { sku: string; stock_ml: number | null }[]) {
        const s = stock.get(String(r.sku));
        if (s) s.full = (s.full ?? 0) + Number(r.stock_ml ?? 0);
      }
    }

    const ventas = hoyRows.map((r) => {
      const canc = esCancelada(r.status);
      const m = canc ? motivo(r.cancel_detail) : null;
      const st = stock.get(String(r.sku));
      const hora = new Date(new Date(r.sale_date).getTime() - 3 * 3600_000).toISOString().slice(11, 16);
      return {
        orden: String(r.ml_order_id),
        sku: String(r.sku),
        hora,
        unidades: Number(r.units ?? 0),
        monto: Number(r.gross_amount ?? 0),
        envio: r.tipo_envio ?? null,
        cancelada: canc,
        cancelada_real: canc ? m!.real : false,
        motivo: m ? m.texto + (m.quien ? ` · ${m.quien}` : "") : null,
        stock: st ? st.dep : null,
        stock_kit: st ? st.kit : false,
        stock_full: st ? st.full : null,
        link: `https://vendedores.mercadolibre.com.ar/ventas/omni/listado?sort=DATE_CLOSED_DESC&search=${r.ml_order_id}&limit=50&offset=0`,
      };
    });

    const pct = (a: number, b: number) => (b === 0 ? null : ((a - b) / b) * 100);
    return J({
      ok: true,
      fecha: hoy.fecha,
      generado_at: new Date().toISOString(),
      hoy: tHoy,
      ayer_misma_hora: tAyerHora,
      ayer_completo: tAyerCompleto,
      delta_pct: pct(tHoy.gross, tAyerHora.gross),
      ventas,
    });
  } catch (e) {
    return J({ ok: false, error: String(e instanceof Error ? e.message : e) }, 500);
  }
});
