package ar.coraz.widget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Acceso al endpoint widget-ventas-hoy + caché local + formato de números estilo AR. */
final class Api {
    static final String URL_ENDPOINT = "https://kvewsxrfogosjwehookj.supabase.co/functions/v1/widget-ventas-hoy";
    static final String URL_DASHBOARD = "https://lautifierros.github.io/coraz-dashboard/";

    private static final String PREFS = "coraz_widget";
    private static final String K_TOKEN = "token";
    private static final String K_JSON = "json";
    private static final String K_OK_AT = "ok_at";
    private static final String K_ERROR = "error";

    private Api() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String token(Context c) { return prefs(c).getString(K_TOKEN, ""); }
    static void setToken(Context c, String t) { prefs(c).edit().putString(K_TOKEN, t.trim()).apply(); }

    static String cachedJson(Context c) { return prefs(c).getString(K_JSON, null); }
    static long okAt(Context c) { return prefs(c).getLong(K_OK_AT, 0); }
    static String lastError(Context c) { return prefs(c).getString(K_ERROR, null); }

    /** Descarga, valida y guarda en caché. Devuelve null si salió bien, o el mensaje de error. */
    static String refresh(Context c) {
        String token = token(c);
        if (token.isEmpty()) {
            prefs(c).edit().putString(K_ERROR, "Falta el token: abrí la app CORAZ Ventas").apply();
            return "Falta el token";
        }
        try {
            String body = get(token);
            JSONObject j = new JSONObject(body);
            if (!j.optBoolean("ok", false)) throw new Exception(j.optString("error", "respuesta inválida"));
            prefs(c).edit()
                    .putString(K_JSON, body)
                    .putLong(K_OK_AT, System.currentTimeMillis())
                    .remove(K_ERROR)
                    .apply();
            return null;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            prefs(c).edit().putString(K_ERROR, msg).apply();
            return msg;
        }
    }

    private static String get(String token) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(URL_ENDPOINT).openConnection();
        con.setConnectTimeout(12000);
        con.setReadTimeout(15000);
        con.setRequestProperty("x-widget-token", token);
        con.setRequestProperty("Accept", "application/json");
        int code = con.getResponseCode();
        InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
        String body = in == null ? "" : readAll(in);
        con.disconnect();
        if (code != 200) {
            String err = "HTTP " + code;
            try { err = new JSONObject(body).optString("error", err); } catch (Exception ignored) {}
            throw new Exception(err);
        }
        return body;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString(StandardCharsets.UTF_8.name());
    }

    // ---------- diagnóstico (últimos eventos, visibles en la app) ----------
    private static final String K_DIAG = "diag";

    static synchronized void log(Context c, String evento) {
        String previo = prefs(c).getString(K_DIAG, "");
        String linea = hora(System.currentTimeMillis()) + " " + evento;
        String nuevo = linea + (previo.isEmpty() ? "" : "\n" + previo);
        if (nuevo.length() > 3000) nuevo = nuevo.substring(0, 3000);
        prefs(c).edit().putString(K_DIAG, nuevo).commit();
    }

    static void log(Context c, String donde, Throwable t) {
        StringBuilder sb = new StringBuilder("✗ ").append(donde).append(": ").append(t);
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(4, st.length); i++) sb.append("\n    at ").append(st[i]);
        if (t.getCause() != null) sb.append("\n    causa: ").append(t.getCause());
        log(c, sb.toString());
    }

    static String diag(Context c) { return prefs(c).getString(K_DIAG, "(sin eventos)"); }
    static void limpiarDiag(Context c) { prefs(c).edit().remove(K_DIAG).apply(); }

    // ---------- formato ----------
    private static final DecimalFormat ENTERO;
    static {
        DecimalFormatSymbols s = new DecimalFormatSymbols(new Locale("es", "AR"));
        s.setGroupingSeparator('.');
        s.setDecimalSeparator(',');
        ENTERO = new DecimalFormat("#,##0", s);
    }

    static String ars(double v) { return "$" + ENTERO.format(Math.round(v)); }
    static String num(double v) { return ENTERO.format(Math.round(v)); }

    static String fechaHoy() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("America/Argentina/Buenos_Aires"));
        return f.format(new Date());
    }

    static String hora(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("America/Argentina/Buenos_Aires"));
        return f.format(new Date(millis));
    }
}
