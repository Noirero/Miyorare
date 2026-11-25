package org.noirero.miyorare.presenceprototype;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.discord.socialsdk.DiscordSocialSdkInit;

import java.nio.charset.StandardCharsets;

public final class PresenceActivity extends Activity {
    private static final String TITLE = "Komik Uji Miyorare";
    private static final String COVER_URL = "https://raw.githubusercontent.com/Noirero/Miyorare/9f9eea04c663b62e89b307af96622ffcfcaa156b/assets/icon.png";
    private static final String SOURCE_URL = "https://github.com/Noirero/Miyorare";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private int chapter = 12;
    private long startedAtSeconds;
    private boolean publishing;
    private final Runnable callbackPump = new Runnable() {
        @Override
        public void run() {
            int result = pump();
            if (publishing && result != 0) {
                publishing = false;
                status.setText(result > 0
                        ? "Kartu dikirim. Periksa profil ini dari akun Discord lain."
                        : "Pengiriman gagal. Periksa login Discord dan konfigurasi aplikasi uji.");
            }
            handler.postDelayed(this, 100);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DiscordSocialSdkInit.setEngineActivity(this);
        System.loadLibrary("miyorare_discord_presence");
        initialize(BuildConfig.DISCORD_APPLICATION_ID);

        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(padding, padding, padding, padding);
        if (Build.VERSION.SDK_INT >= 30) {
            layout.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets edges = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(padding + edges.left, padding + edges.top,
                        padding + edges.right, padding + edges.bottom);
                return insets;
            });
        }

        TextView sample = new TextView(this);
        sample.setText(TITLE + " · Bab 12");
        layout.addView(sample);
        Button show = new Button(this);
        show.setText("Tampilkan kartu uji");
        show.setOnClickListener(view -> publishCard());
        layout.addView(show);
        Button next = new Button(this);
        next.setText("Ganti bab");
        next.setOnClickListener(view -> {
            chapter++;
            sample.setText(TITLE + " · Bab " + chapter);
            if (startedAtSeconds != 0) publishCard();
        });
        layout.addView(next);
        Button hide = new Button(this);
        hide.setText("Hapus kartu uji");
        hide.setOnClickListener(view -> clearCard());
        layout.addView(hide);
        status = new TextView(this);
        status.setText("Gunakan akun uji Discord yang sudah login di HP ini.");
        layout.addView(status);
        setContentView(layout);
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(callbackPump);
    }

    @Override
    protected void onStop() {
        clearCard();
        handler.removeCallbacks(callbackPump);
        pump();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void publishCard() {
        if (startedAtSeconds == 0) startedAtSeconds = System.currentTimeMillis() / 1000;
        publishing = true;
        status.setText("Mengirim kartu uji…");
        publish(utf8(TITLE), utf8("Bab " + chapter), utf8(COVER_URL), utf8(SOURCE_URL), startedAtSeconds);
    }

    private void clearCard() {
        if (startedAtSeconds == 0) return;
        clear();
        startedAtSeconds = 0;
        publishing = false;
        status.setText("Permintaan hapus dikirim. Periksa lagi dari akun lain.");
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static native void initialize(long applicationId);
    private static native void publish(byte[] title, byte[] chapter, byte[] coverUrl, byte[] sourceUrl, long startedAtSeconds);
    private static native int pump();
    private static native void clear();
    private static native void shutdown();
}
