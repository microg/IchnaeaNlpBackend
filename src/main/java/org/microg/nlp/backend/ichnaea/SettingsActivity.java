/*
 * SPDX-FileCopyrightText: 2015 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.nlp.backend.ichnaea;

import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.util.Log;

public class SettingsActivity extends PreferenceActivity {
    private static final String TAG = "IchnaeaPreferences";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "Preferences paused, reloading backend settings");
        BackendService.reloadInstanceSettings();
    }
}
