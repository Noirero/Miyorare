package org.koitharu.kotatsu.scrobbling.discord.sdk;

import android.app.Activity;
import com.discord.socialsdk.DiscordSocialSdkInit;

public final class DiscordSdkActivity {
    private DiscordSdkActivity() {}

    public static void attach(Activity activity) {
        DiscordSocialSdkInit.setEngineActivity(activity);
    }
}
