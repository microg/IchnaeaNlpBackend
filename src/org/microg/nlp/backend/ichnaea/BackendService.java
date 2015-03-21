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
import android.os.Build;
import android.os.Process;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.text.Html;
import android.text.Spanned;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.microg.nlp.api.CellBackendHelper;
import org.microg.nlp.api.HelperLocationBackendService;
import org.microg.nlp.api.LocationHelper;
import org.microg.nlp.api.WiFiBackendHelper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Set;

import static org.microg.nlp.api.CellBackendHelper.Cell;
import static org.microg.nlp.api.WiFiBackendHelper.WiFi;

public class BackendService extends HelperLocationBackendService
        implements WiFiBackendHelper.Listener, CellBackendHelper.Listener {

    private static final String TAG = "IchnaeaBackendService";
    private static final String SERVICE_URL = "https://location.services.mozilla.com/v1/%s?key=%s";
    private static final String SERVICE_TYPE_SUBMIT = "geosubmit";
    private static final String SERVICE_TYPE_LOCATE = "geolocate";
    private static final String API_KEY = "068ab754-c06b-473d-a1e5-60e7b1a2eb77";
    private static final String PROVIDER = "ichnaea";
    private static final int RATE_LIMIT_MS = 5000;
    private static final long SWITCH_ON_FRESHNESS_CLIFF_MS = 30000;
    private static final int LOCATION_ACCURACY_THRESHOLD = 50;

    private static BackendService instance;

    private boolean running = false;
    private Set<WiFi> wiFis;
    private Set<Cell> cells;
    private Thread thread;
    private long lastRequest = 0;

    private String nickname = "";
    private boolean submit = false;
    private boolean useWiFis = true;
    private boolean useCells = true;

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    protected synchronized void onOpen() {
        super.onOpen();
        reloadSettings();
        instance = this;
        running = true;
        Log.d(TAG, "Activating instance at process " + Process.myPid());
    }

    public static void reloadInstanceSttings() {
        if (instance != null) {
            instance.reloadSettings();
        } else {
            Log.d(TAG, "No instance found active.");
        }
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
            nickname = preferences.getString("nickname", null);
            if (nickname == null) nickname = "";
            if (submit) 
                 Log.d(TAG, "Contributing with nickname \"" + nickname + "\"");
            useCells = preferences.getBoolean("use_cells", true)
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1;
            useWiFis = preferences.getBoolean("use_wifis", true);
        }
        removeHelpers();
        if (useCells) addHelper(new CellBackendHelper(this, this));
        if (!useCells) cells = null;
        if (useWiFis) addHelper(new WiFiBackendHelper(this, this));
        if (!useWiFis) wiFis = null;
    }

    @Override
    protected synchronized void onClose() {
        super.onClose();
        running = false;
        if (instance == this) {
            instance = null;
            Log.d(TAG, "Deactivating instance at process " + Process.myPid());
        }
    }

    @Override
    public void onWiFisChanged(Set<WiFi> wiFis) {
        this.wiFis = wiFis;
        if (running) startCalculate();
    }

    @Override
    public void onCellsChanged(Set<Cell> cells) {
        this.cells = cells;
        Log.d(TAG, "Cells: " + cells.size());
        if (running) startCalculate();
    }

    private synchronized void startCalculate() {
        if (thread != null) return;
        if (lastRequest + RATE_LIMIT_MS > System.currentTimeMillis()) return;
        final Set<WiFi> wiFis = this.wiFis;
        final Set<Cell> cells = this.cells;
        if ((cells == null || cells.isEmpty()) && (wiFis == null || wiFis.size() < 2)) return;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(String.format(SERVICE_URL,
                            submit ? SERVICE_TYPE_SUBMIT : SERVICE_TYPE_LOCATE, API_KEY))
                            .openConnection();
                    conn.setDoOutput(true);
                    conn.setDoInput(true);
                    Location l = null;
                    if (submit) {
                        if (!nickname.isEmpty()) conn.setRequestProperty("X-Nickname", nickname);
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
                    String request = createRequest(cells, wiFis, l);
                    Log.d(TAG, "request: " + request);
                    conn.getOutputStream().write(request.getBytes());
                    String r = new String(readStreamToEnd(conn.getInputStream()));
                    Log.d(TAG, "response: " + r);
                    JSONObject response = new JSONObject(r);
                    double lat = response.getJSONObject("location").getDouble("lat");
                    double lon = response.getJSONObject("location").getDouble("lng");
                    double acc = response.getDouble("accuracy");
                    report(LocationHelper.create(PROVIDER, lat, lon, (float) acc));
                } catch (IOException | JSONException e) {
                    if (conn != null) {
                        InputStream is = conn.getErrorStream();
                        if (is != null) {
                            try {
                                String error = new String(readStreamToEnd(is));
                                Log.w(TAG, "Error: "+error);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    Log.w(TAG, e);
                }

                lastRequest = System.currentTimeMillis();
                thread = null;
            }
        });
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

    /**
     * see https://mozilla-ichnaea.readthedocs.org/en/latest/cell.html
     */
    @SuppressWarnings("MagicNumber")
    private static int calculateAsu(Cell cell) {
        switch (cell.getType()) {
            case GSM:
                return Math.max(0, Math.min(31, (cell.getSignal() + 113) / 2));
            case UMTS:
                return Math.max(-5, Math.max(91, cell.getSignal() + 116));
            case LTE:
                return Math.max(0, Math.min(95, cell.getSignal() + 140));
            case CDMA:
                int signal = cell.getSignal();
                if (signal >= -75) {
                    return 16;
                }
                if (signal >= -82) {
                    return 8;
                }
                if (signal >= -90) {
                    return 4;
                }
                if (signal >= -95) {
                    return 2;
                }
                if (signal >= -100) {
                    return 1;
                }
                return 0;
        }
        return 0;
    }
    
    private static String getRadioType(Cell cell) {
        switch (cell.getType()) {
            case CDMA:
                return "cdma";
            case LTE:
                return "lte";
            case UMTS:
                return "wcdma";
            case GSM:
            default:
                return "gsm";
        }
    }

    private static String createRequest(Set<Cell> cells, Set<WiFi> wiFis,
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
        JSONArray cellTowers = new JSONArray();
        
        if (cells != null) {
            Cell.CellType lastType = null;
            for (Cell cell : cells) {
                if (cell.getType() == Cell.CellType.CDMA) {
                    jsonObject.put("radioType", "cdma");
                } else if (lastType != null && lastType != cell.getType()) {
                    // We can't contribute if different cell types are mixed.
                    jsonObject.put("radioType", null);
                } else {
                    jsonObject.put("radioType", getRadioType(cell));
                }
                lastType = cell.getType();
                JSONObject cellTower = new JSONObject();
                cellTower.put("radioType", getRadioType(cell));
                cellTower.put("mobileCountryCode", cell.getMcc());
                cellTower.put("mobileNetworkCode", cell.getMnc());
                cellTower.put("locationAreaCode", cell.getLac());
                cellTower.put("cellId", cell.getCid());
                cellTower.put("signalStrength", cell.getSignal());
                if (cell.getPsc() != -1)
                    cellTower.put("psc", cell.getPsc());
                cellTower.put("asu", calculateAsu(cell));
                cellTowers.put(cellTower);
            }
        }
        JSONArray wifiAccessPoints = new JSONArray();
        if (wiFis != null) {
            for (WiFi wiFi : wiFis) {
                JSONObject wifiAccessPoint = new JSONObject();
                wifiAccessPoint.put("macAddress", wiFi.getBssid());
                wifiAccessPoint.put("signalStrength", wiFi.getRssi());
                //wifiAccessPoint.put("age", age);
                //wifiAccessPoint.put("channel", channel);
                //wifiAccessPoint.put("signalToNoiseRatio", signalToNoiseRatio);
                wifiAccessPoints.put(wifiAccessPoint);
            }
        }
        jsonObject.put("cellTowers", cellTowers);
        jsonObject.put("wifiAccessPoints", wifiAccessPoints);
        return jsonObject.toString();
    }
}
