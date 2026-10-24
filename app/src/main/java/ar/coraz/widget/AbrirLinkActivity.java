package ar.coraz.widget;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Trampolín invisible para los toques en la lista del widget.
 * Android 14+ prohíbe PendingIntent mutables con intent implícito (ACTION_VIEW sin destino),
 * así que la plantilla de la lista apunta acá (intent explícito) y esta pantalla abre el link.
 */
public class AbrirLinkActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Uri uri = getIntent() != null ? getIntent().getData() : null;
        if (uri != null) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {}
        }
        finish();
    }
}
