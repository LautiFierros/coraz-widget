package ar.coraz.widget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/** Pantalla de configuración: pegar el token, probar la conexión y agregar el widget al inicio. */
public class MainActivity extends Activity {

    private EditText input;
    private TextView estado;
    private TextView diag;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int pad = dp(20);
        int bg = getColor(R.color.bg) | 0xFF000000;

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        scroll.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad * 2, pad, pad);
        scroll.addView(col);

        col.addView(texto("CORAZ VENTAS", 12, R.color.muted, true));
        col.addView(texto("Widget de ventas del día", 22, R.color.text, false));
        col.addView(espacio(14));
        col.addView(texto("1. Pegá el token del widget (te lo pasa Claude).\n"
                + "2. Tocá «Guardar y probar».\n"
                + "3. Agregá el widget al inicio y deslizá la lista para ver cada venta.", 14, R.color.muted, false));
        col.addView(espacio(18));

        input = new EditText(this);
        input.setHint("token");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextColor(getColor(R.color.text));
        input.setHintTextColor(getColor(R.color.muted));
        input.setText(Api.token(this));
        col.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(boton("Pegar del portapapeles", false, v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                input.setText(t == null ? "" : t.toString().trim());
            }
        }));
        col.addView(boton("Guardar y probar", true, v -> guardarYProbar()));

        estado = texto("", 14, R.color.text, false);
        estado.setPadding(0, dp(12), 0, dp(12));
        col.addView(estado);

        col.addView(boton("Agregar widget al inicio", false, v -> pinWidget()));
        col.addView(boton("Abrir dashboard", false, v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Api.URL_DASHBOARD)))));

        // ---------- Diagnóstico ----------
        col.addView(espacio(24));
        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        col.addView(texto("DIAGNÓSTICO · v" + version, 12, R.color.muted, true));
        col.addView(boton("Actualizar widget ahora", false, v -> forzarWidget()));
        diag = texto("", 11, R.color.muted, true);
        diag.setLetterSpacing(0f);
        diag.setTextIsSelectable(true);
        diag.setPadding(0, dp(10), 0, dp(10));
        col.addView(diag);
        col.addView(boton("Limpiar diagnóstico", false, v -> { Api.limpiarDiag(this); refrescarDiag(); }));

        setContentView(scroll);
        refrescarDiag();
        if (!Api.token(this).isEmpty()) mostrarCache();
    }

    private void guardarYProbar() {
        String t = input.getText().toString().trim();
        if (t.isEmpty()) { estado.setText("Pegá el token primero."); return; }
        Api.setToken(this, t);
        estado.setText("Probando…");
        new Thread(() -> {
            String err = Api.refresh(getApplicationContext());
            runOnUiThread(() -> {
                refrescarDiag();
                if (err != null) {
                    estado.setText("✗ " + err);
                    estado.setTextColor(getColor(R.color.red));
                } else {
                    estado.setTextColor(getColor(R.color.green));
                    mostrarCache();
                    VentasWidgetProvider.pedirRefresco(this);
                }
            });
        }).start();
    }

    /** Descarga y redibuja el widget desde la app (primer plano), mostrando qué pasa en cada paso. */
    private void forzarWidget() {
        Api.log(this, "forzado desde la app");
        refrescarDiag();
        new Thread(() -> {
            String err = Api.refresh(getApplicationContext());
            runOnUiThread(() -> {
                Api.log(this, err == null ? "datos OK (app)" : "datos ERROR (app): " + err);
                try {
                    String r = VentasWidgetProvider.renderTodos(this, false);
                    AppWidgetManager m = AppWidgetManager.getInstance(this);
                    m.notifyAppWidgetViewDataChanged(
                            m.getAppWidgetIds(new ComponentName(this, VentasWidgetProvider.class)), R.id.lista);
                    Api.log(this, "forzado: " + r);
                } catch (Throwable t) {
                    Api.log(this, "forzado", t);
                }
                refrescarDiag();
                mostrarCache();
            });
        }).start();
    }

    private void refrescarDiag() {
        if (diag != null) diag.setText(Api.diag(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refrescarDiag();
    }

    private void mostrarCache() {
        String json = Api.cachedJson(this);
        if (json == null) return;
        try {
            JSONObject hoy = new JSONObject(json).getJSONObject("hoy");
            estado.setText("✓ Conectado · hoy " + Api.ars(hoy.optDouble("gross", 0))
                    + " en " + hoy.optInt("ventas", 0) + " ventas"
                    + (Api.okAt(this) > 0 ? " (act. " + Api.hora(Api.okAt(this)) + ")" : ""));
            estado.setTextColor(getColor(R.color.green));
        } catch (Exception ignored) {}
    }

    private void pinWidget() {
        AppWidgetManager m = getSystemService(AppWidgetManager.class);
        if (m != null && m.isRequestPinAppWidgetSupported()) {
            m.requestPinAppWidget(new ComponentName(this, VentasWidgetProvider.class), null, null);
        } else {
            estado.setText("Tu launcher no permite agregarlo desde acá: mantené presionado el inicio → Widgets → CORAZ Ventas.");
            estado.setTextColor(getColor(R.color.amber));
        }
    }

    // ---------- helpers de UI ----------
    private TextView texto(String s, float sp, int color, boolean mono) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(getColor(color));
        if (mono) { t.setTypeface(Typeface.MONOSPACE); t.setLetterSpacing(0.1f); }
        return t;
    }

    private Button boton(String s, boolean primario, android.view.View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setTextColor(getColor(primario ? R.color.bg : R.color.text));
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(12));
        d.setColor(getColor(primario ? R.color.amber : R.color.card));
        d.setStroke(dp(1), getColor(primario ? R.color.amber : R.color.border));
        btn.setBackground(d);
        btn.setGravity(Gravity.CENTER);
        btn.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(10);
        btn.setLayoutParams(lp);
        return btn;
    }

    private android.view.View espacio(int h) {
        android.view.View v = new android.view.View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
