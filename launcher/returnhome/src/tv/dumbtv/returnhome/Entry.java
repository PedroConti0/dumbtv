package tv.dumbtv.returnhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Standalone app-drawer shortcut that returns to the dumbtv HOME activity. */
public final class Entry extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage("tv.dumbtv.home")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(home);
        finish();
    }
}
