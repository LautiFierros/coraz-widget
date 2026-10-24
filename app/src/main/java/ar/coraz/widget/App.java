package ar.coraz.widget;

import android.app.Application;

/** Guarda cualquier cierre inesperado en el diagnóstico antes de dejar caer la app. */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previo = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            try { Api.log(this, "CRASH en " + th.getName(), ex); } catch (Throwable ignored) {}
            if (previo != null) previo.uncaughtException(th, ex);
        });
    }
}
