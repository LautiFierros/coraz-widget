package ar.coraz.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.widget.RemoteViews;

import org.json.JSONObject;

/**
 * Widget "CORAZ · Ventas de hoy".
 * - Encabezado: facturado del día, variación vs ayer a la misma hora, ventas/unidades/ticket/neto/canceladas.
 * - Lista deslizable con cada venta (VentasWidgetService). Tocar una venta abre la orden en ML.
 * - Refresco: botón ⟳, alarma inexacta cada 15 min (no despierta el celular) y updatePeriodMillis (30 min).
 */
public class VentasWidgetProvider extends AppWidgetProvider {

    static final String ACTION_REFRESH = "ar.coraz.widget.REFRESH";
    private static final long CADA_15_MIN = AlarmManager.INTERVAL_FIFTEEN_MINUTES;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        programarAlarma(context);
        renderTodos(context, false);
        refrescarAsync(context, goAsync());
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_REFRESH.equals(intent.getAction())) {
            renderTodos(context, true);
            refrescarAsync(context, goAsync());
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    public void onEnabled(Context context) { programarAlarma(context); }

    @Override
    public void onDisabled(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(refreshIntent(context));
    }

    // ------------------------------------------------------------------

    static void pedirRefresco(Context context) {
        Intent i = new Intent(context, VentasWidgetProvider.class).setAction(ACTION_REFRESH);
        context.sendBroadcast(i);
    }

    private static void refrescarAsync(Context context, PendingResult pr) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                Api.refresh(app);
                renderTodos(app, false);
                AppWidgetManager m = AppWidgetManager.getInstance(app);
                m.notifyAppWidgetViewDataChanged(ids(app), R.id.lista);
            } finally {
                if (pr != null) pr.finish();
            }
        }).start();
    }

    private static void programarAlarma(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + CADA_15_MIN, CADA_15_MIN, refreshIntent(context));
    }

    private static PendingIntent refreshIntent(Context context) {
        Intent i = new Intent(context, VentasWidgetProvider.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(context, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int[] ids(Context context) {
        return AppWidgetManager.getInstance(context)
                .getAppWidgetIds(new ComponentName(context, VentasWidgetProvider.class));
    }

    static void renderTodos(Context context, boolean actualizando) {
        AppWidgetManager m = AppWidgetManager.getInstance(context);
        for (int id : ids(context)) m.updateAppWidget(id, render(context, id, actualizando));
    }

    private static RemoteViews render(Context c, int widgetId, boolean actualizando) {
        RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_ventas);
        int muted = c.getColor(R.color.muted), green = c.getColor(R.color.green),
                red = c.getColor(R.color.red), amber = c.getColor(R.color.amber);

        // Lista deslizable
        Intent svc = new Intent(c, VentasWidgetService.class);
        svc.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        rv.setRemoteAdapter(R.id.lista, svc);
        rv.setEmptyView(R.id.lista, R.id.vacio);

        // Tocar una venta → abre la orden en ML (cada fila completa la URL)
        PendingIntent abrirLink = PendingIntent.getActivity(c, 2, new Intent(Intent.ACTION_VIEW),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        rv.setPendingIntentTemplate(R.id.lista, abrirLink);

        // ⟳
        rv.setOnClickPendingIntent(R.id.btn_refresh, PendingIntent.getBroadcast(c, 3,
                new Intent(c, VentasWidgetProvider.class).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        boolean sinToken = Api.token(c).isEmpty();
        Intent destinoHeader = sinToken
                ? new Intent(c, MainActivity.class)
                : new Intent(Intent.ACTION_VIEW, Uri.parse(Api.URL_DASHBOARD));
        rv.setOnClickPendingIntent(R.id.header, PendingIntent.getActivity(c, 4, destinoHeader,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        if (sinToken) {
            rv.setTextViewText(R.id.facturado, "$ —");
            rv.setTextViewText(R.id.delta, "");
            rv.setTextViewText(R.id.stats, "Tocá acá y pegá el token en la app");
            rv.setTextViewText(R.id.actualizado, "");
            rv.setTextViewText(R.id.vacio, "Sin configurar");
            return rv;
        }

        // Estado de actualización
        String err = Api.lastError(c);
        long okAt = Api.okAt(c);
        if (actualizando) {
            rv.setTextViewText(R.id.actualizado, "actualizando…");
            rv.setTextColor(R.id.actualizado, muted);
        } else if (err != null) {
            rv.setTextViewText(R.id.actualizado, "⚠ " + (okAt > 0 ? Api.hora(okAt) : "sin datos"));
            rv.setTextColor(R.id.actualizado, amber);
        } else {
            rv.setTextViewText(R.id.actualizado, okAt > 0 ? "act. " + Api.hora(okAt) : "");
            rv.setTextColor(R.id.actualizado, muted);
        }

        String json = Api.cachedJson(c);
        if (json == null) {
            rv.setTextViewText(R.id.stats, err != null ? "Error: " + err : "Cargando…");
            rv.setTextViewText(R.id.vacio, err != null ? "No se pudo cargar" : "Cargando…");
            return rv;
        }

        try {
            JSONObject j = new JSONObject(json);
            JSONObject hoy = j.getJSONObject("hoy");
            // Si la caché es de otro día (ej. el celu estuvo sin red a la medianoche), se avisa.
            String fecha = j.optString("fecha", "");
            boolean esHoy = fecha.equals(Api.fechaHoy());
            rv.setTextViewText(R.id.titulo, esHoy || fecha.length() < 10
                    ? "VENTAS HOY" : "VENTAS DEL " + fecha.substring(8) + "/" + fecha.substring(5, 7));

            rv.setTextViewText(R.id.facturado, Api.ars(hoy.optDouble("gross", 0)));

            if (j.isNull("delta_pct")) {
                rv.setTextViewText(R.id.delta, "ayer a esta hora: $0");
                rv.setTextColor(R.id.delta, muted);
            } else {
                double d = j.getDouble("delta_pct");
                rv.setTextViewText(R.id.delta, (d >= 0 ? "▲ " : "▼ ") + Api.num(Math.abs(d)) + "% vs ayer a esta hora");
                rv.setTextColor(R.id.delta, d >= 0 ? green : red);
            }

            int ventas = hoy.optInt("ventas", 0);
            int canc = hoy.optInt("canceladas", 0);
            String stats = ventas + (ventas == 1 ? " venta" : " ventas")
                    + " · " + Api.num(hoy.optDouble("units", 0)) + " u"
                    + " · ticket " + Api.ars(hoy.optDouble("ticket", 0))
                    + "\nneto " + Api.ars(hoy.optDouble("net", 0))
                    + (canc > 0 ? " · " + canc + (canc == 1 ? " cancelada" : " canceladas") : "");
            rv.setTextViewText(R.id.stats, stats);
            rv.setTextViewText(R.id.vacio, "Sin ventas todavía hoy");
        } catch (Exception e) {
            rv.setTextViewText(R.id.stats, "Datos con formato inesperado");
        }
        return rv;
    }
}
