// Written for Thoughts. License is GPLv3.

package org.tbend.thoughts.shortcut;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** This separate package keeps the power-button camera gesture available while Thoughts is foregrounded. */
public final class CameraNoteActivity extends Activity {
    private static final ComponentName QUICK_NOTE = new ComponentName(
            "org.tbend.thoughts",
            "com.gitlab.mudlej.MjPdfReader.ui.notes.QuickNoteActivity");

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            startActivity(noteIntent());
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(this, "Install or update Thoughts to use this shortcut.", Toast.LENGTH_LONG).show();
        } finally {
            finish();
        }
    }

    static Intent noteIntent() {
        return new Intent()
                .setComponent(QUICK_NOTE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    }
}
