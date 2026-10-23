package ar.coraz.widget;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Alimenta la lista deslizable del widget con las ventas del día (mismo criterio que "Ventas individuales"). */
public class VentasWidgetService extends RemoteViewsService {

    // Umbrales del badge de stock, iguales al dashboard (STOCK_CFG): 0 = rojo, ≤3 = ámbar.
    private static final int STOCK_CRITICO = 0;
    private static final int STOCK_BAJO = 3;

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext());
    }

    static class Factory implements RemoteViewsFactory {
        private final Context c;
        private final List<JSONObject> ventas = new ArrayList<>();

        Factory(Context c) { this.c = c; }

        @Override public void onCreate() {}
        @Override public void onDestroy() { ventas.clear(); }

        @Override
        public void onDataSetChanged() {
            ventas.clear();
            String json = Api.cachedJson(c);
            if (json == null) return;
            try {
                JSONArray arr = new JSONObject(json).optJSONArray("ventas");
                if (arr == null) return;
                for (int i = 0; i < arr.length(); i++) ventas.add(arr.getJSONObject(i));
            } catch (Exception ignored) {}
        }

        @Override public int getCount() { return ventas.size(); }

        @Override
        public RemoteViews getViewAt(int pos) {
            RemoteViews rv = new RemoteViews(c.getPackageName(), R.layout.widget_fila);
            if (pos < 0 || pos >= ventas.size()) return rv;
            JSONObject v = ventas.get(pos);

            int text = c.getColor(R.color.text), muted = c.getColor(R.color.muted),
                    red = c.getColor(R.color.red), amber = c.getColor(R.color.amber), green = c.getColor(R.color.green);

            boolean canc = v.optBoolean("cancelada", false);
            boolean cancReal = v.optBoolean("cancelada_real", false);
            int unidades = v.optInt("unidades", 1);

            // Línea 1: SKU · hora (× unidades)            monto
            String l1 = v.optString("sku") + " · " + v.optString("hora") + (unidades > 1 ? "  ×" + unidades : "");
            String monto = Api.ars(v.optDouble("monto", 0));
            if (canc) {
                rv.setTextViewText(R.id.f_sku, tachar(l1));
                rv.setTextViewText(R.id.f_monto, tachar(monto));
                rv.setTextColor(R.id.f_sku, cancReal ? red : muted);
                rv.setTextColor(R.id.f_monto, muted);
            } else {
                rv.setTextViewText(R.id.f_sku, l1);
                rv.setTextViewText(R.id.f_monto, monto);
                rv.setTextColor(R.id.f_sku, text);
                rv.setTextColor(R.id.f_monto, text);
            }

            // Línea 2: #orden · tipo de envío              stock
            String envio = v.isNull("envio") ? "" : " · " + v.optString("envio");
            rv.setTextViewText(R.id.f_sub, "#" + v.optString("orden") + envio);

            String stockTxt;
            int stockColor;
            if (v.isNull("stock")) {
                stockTxt = "stock s/d";
                stockColor = muted;
            } else {
                int n = v.optInt("stock", 0);
                stockTxt = "stock " + n + (v.optBoolean("stock_kit", false) ? " kits" : "");
                stockColor = n <= STOCK_CRITICO ? red : (n <= STOCK_BAJO ? amber : green);
            }
            if (!v.isNull("stock_full")) stockTxt += " · full " + v.optInt("stock_full", 0);
            rv.setTextViewText(R.id.f_stock, stockTxt);
            rv.setTextColor(R.id.f_stock, stockColor);

            // Motivo de cancelación (pack dividido por ML va apagado: no es cancelación real)
            if (canc) {
                String etiqueta = cancReal ? "Cancelada: " : "No es cancelación: ";
                rv.setTextViewText(R.id.f_motivo, etiqueta + v.optString("motivo", "sin motivo"));
                rv.setTextColor(R.id.f_motivo, cancReal ? red : muted);
                rv.setViewVisibility(R.id.f_motivo, View.VISIBLE);
            } else {
                rv.setViewVisibility(R.id.f_motivo, View.GONE);
            }

            // Tocar la fila → abre la orden en Mercado Libre
            Intent fill = new Intent();
            fill.setData(Uri.parse(v.optString("link", Api.URL_DASHBOARD)));
            rv.setOnClickFillInIntent(R.id.fila, fill);
            return rv;
        }

        private static SpannableString tachar(String s) {
            SpannableString sp = new SpannableString(s);
            sp.setSpan(new StrikethroughSpan(), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return sp;
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int pos) { return pos; }
        @Override public boolean hasStableIds() { return false; }
    }
}
