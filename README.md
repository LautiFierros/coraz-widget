# CORAZ Ventas — widget Android

Widget de pantalla de inicio con las **ventas del día** de Mercado Libre, igual que el panel de ventas de [coraz-dashboard](https://lautifierros.github.io/coraz-dashboard/):

- Encabezado: facturado del día, ▲/▼ vs **ayer a la misma hora**, ventas · unidades · ticket, neto y canceladas.
- Lista **deslizable** con cada venta: SKU · hora, monto, #orden · tipo de envío, badge de stock (depósito + Full) y motivo si se canceló. Tocar una venta abre la orden en ML; tocar el encabezado abre el dashboard.
- Refresco: botón ⟳, cada ~15 min mientras el celular está en uso, y cada 30 min por el sistema.

## Cómo funciona
- Backend: Edge Function `widget-ventas-hoy` (Supabase `kvewsxrfogosjwehookj`), solo lectura, auth por token en header `x-widget-token`.
- Tokens: tabla `widget_accesos` (guarda solo el sha256). Revocar: `update widget_accesos set activo = false where id = …;`
- Sin dependencias externas: Java + SDK de Android.

## Build
GitHub Actions (`.github/workflows/build-apk.yml`) compila en cada push a `main` y publica el APK como Release (`coraz-ventas.apk`). Firma: la llave fija NO está en el repo; va como secreto `KEYSTORE_B64` (Settings → Secrets and variables → Actions). Con el secreto, cada versión se instala encima de la anterior; sin él, se firma con llave debug y para actualizar hay que desinstalar antes.

## Instalar
1. Bajar `coraz-ventas.apk` del último Release e instalarlo (permitir "instalar apps desconocidas" para el navegador/Archivos).
2. Abrir **CORAZ Ventas**, pegar el token, "Guardar y probar".
3. "Agregar widget al inicio" (o mantener presionado el inicio → Widgets → CORAZ Ventas).
