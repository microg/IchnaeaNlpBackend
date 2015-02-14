/*
 * Copyright 2013-2015 µg Project Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.microg.nlp.backend.ichnaea;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.text.Html;
import android.text.Spanned;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.microg.nlp.api.LocationBackendService;
import org.microg.nlp.api.LocationHelper;
import org.microg.nlp.api.WiFiBackendHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Set;

public class BackendService extends LocationBackendService implements WiFiBackendHelper.Listener {

    private static final String TAG = "IchnaeaBackendService";
    private static final String SERVICE_URL = "https://location.services.mozilla.com/v1/%s?key=%s";
    private static final String SERVICE_TYPE_SUBMIT = "geosubmit";
    private static final String SERVICE_TYPE_LOCATE = "geolocate";
    private static final String API_KEY = "068ab754-c06b-473d-a1e5-60e7b1a2eb77";
    private static final String PROVIDER = "ichnaea";
    private static final int RATE_LIMIT_MS = 5000;
    private static final long SWITCH_ON_FRESHNESS_CLIFF_MS = 30000; // 30 seconds
    public static final int LOCATION_ACCURACY_THRESHOLD = 50;

    private WiFiBackendHelper backendHelper;
    private boolean running = false;
    private Set<WiFiBackendHelper.WiFi> wiFis;
    private Thread thread;
    private long lastRequest = 0;
    private String nickname = "µg User";
    private boolean submit = false;

    @Override
    public void onCreate() {
        super.onCreate();
        backendHelper = new WiFiBackendHelper(this, this);
    }

    @Override
    protected Location update() {
        backendHelper.onUpdate();
        return null;
    }

    @Override
    protected synchronized void onOpen() {
        Log.d(TAG, "onOpen");
        backendHelper.onOpen();
        reloadSettings();
        running = true;
    }

    private void reloadSettings() {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (!preferences.contains("submit_data")) {
            Spanned message = Html.fromHtml(getString(R.string.first_time_notification_content));
            PendingIntent pi = PendingIntent.getActivity(this, 0,
                    new Intent(this, SettingsActivity.class), PendingIntent.FLAG_ONE_SHOT);
            Notification notification = new NotificationCompat.Builder(this)
                    .setContentText(message)
                    .setContentTitle(getString(R.string.first_time_notification_title))
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setWhen(0)
                    .build();
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(0, notification);
        } else {
            submit = preferences.getBoolean("submit_data", false);
            nickname = preferences.getString("nickname", "µg User");
            if (submit) Log.d(TAG, "Contributing with nickname \"" + nickname + "\"");
        }
    }

    @Override
    protected synchronized void onClose() {
        Log.d(TAG, "onClose");
        running = false;
        backendHelper.onClose();
    }

    @Override
    public void onWiFisChanged(Set<WiFiBackendHelper.WiFi> wiFis) {
        this.wiFis = wiFis;
        if (running) startCalculate();
    }

    private synchronized void startCalculate() {
        if (thread != null) return;
        if (lastRequest + RATE_LIMIT_MS > System.currentTimeMillis()) return;
        final Set<WiFiBackendHelper.WiFi> wiFis = this.wiFis;
        if (wiFis.size() < 2) return;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URLConnection conn = new URL(String.format(SERVICE_URL,
                            submit ? SERVICE_TYPE_SUBMIT : SERVICE_TYPE_LOCATE, API_KEY))
                            .openConnection();
                    conn.setDoOutput(true);
                    conn.setDoInput(true);
                    Location l = null;
                    if (submit) {
                        conn.setRequestProperty("X-Nickname", nickname);
                        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                        for (String provider : lm.getAllProviders()) {
                            Location temp = lm.getLastKnownLocation(provider);
                            if (temp != null && temp.hasAccuracy() &&
                                    temp.getAccuracy() < LOCATION_ACCURACY_THRESHOLD &&
                                    temp.getTime() + RATE_LIMIT_MS > System.currentTimeMillis()) {
                                if (l == null) {
                                    l = temp;
                                } else if (temp.getAccuracy() < l.getAccuracy() ||
                                        temp.getTime() > l.getTime() + SWITCH_ON_FRESHNESS_CLIFF_MS) {
                                    l = temp;
                                }

                            }
                        }
                    }
                    String request = createRequest(null, wiFis, l);
                    Log.d(TAG, "request: " + request);
                    conn.getOutputStream().write(request.getBytes());
                    String r = new String(readStreamToEnd(conn.getInputStream()));
                    Log.d(TAG, "response: " + r);
                    JSONObject response = new JSONObject(r);
                    double lat = response.getJSONObject("location").getDouble("lat");
                    double lon = response.getJSONObject("location").getDouble("lng");
                    double acc = response.getDouble("accuracy");
                    report(LocationHelper.create(PROVIDER, lat, lon, (float) acc));
                } catch (IOException |
                        JSONException e
                        )

                {
                    Log.w(TAG, e);
                }

                lastRequest = System.currentTimeMillis();
                thread = null;
            }
        }

        );
        thread.start();
    }

    @Override
    public void report(Location location) {
        Log.d(TAG, "reporting: " + location);
        super.report(location);
    }

    private static byte[] readStreamToEnd(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (is != null) {
            byte[] buff = new byte[1024];
            while (true) {
                int nb = is.read(buff);
                if (nb < 0) {
                    break;
                }
                bos.write(buff, 0, nb);
            }
            is.close();
        }
        return bos.toByteArray();
    }

    private String createRequest(Object cells, Set<WiFiBackendHelper.WiFi> wiFis,
                                 Location currentLocation) throws JSONException {
        JSONObject jsonObject = new JSONObject();
        if (currentLocation != null) {
            jsonObject.put("latitude", currentLocation.getLatitude());
            jsonObject.put("longitude", currentLocation.getLongitude());
            if (currentLocation.hasAccuracy())
                jsonObject.put("accuracy", currentLocation.getAccuracy() + LOCATION_ACCURACY_THRESHOLD);
            if (currentLocation.hasAltitude())
                jsonObject.put("altitude", currentLocation.getAltitude());
        }
        //jsonObject.put("radioType", radioType);
        JSONArray cellTowers = new JSONArray();
        // TODO cells
        JSONArray wifiAccessPoints = new JSONArray();
        for (WiFiBackendHelper.WiFi wiFi : wiFis) {
            JSONObject wifiAccessPoint = new JSONObject();
            wifiAccessPoint.put("macAddress", wiFi.getBssid());
            wifiAccessPoint.put("signalStrength", wiFi.getRssi());
            //wifiAccessPoint.put("age", age);
            //wifiAccessPoint.put("channel", channel);
            //wifiAccessPoint.put("signalToNoiseRatio", signalToNoiseRatio);
            wifiAccessPoints.put(wifiAccessPoint);
        }
        if (wifiAccessPoints.length() < 2 && cellTowers.length() == 0) return null;
        jsonObject.put("cellTowers", cellTowers);
        jsonObject.put("wifiAccessPoints", wifiAccessPoints);
        return jsonObject.toString();
    }
}
