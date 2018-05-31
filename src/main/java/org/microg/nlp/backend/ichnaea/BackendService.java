/*
 * Copyright (C) 2013-2017 microG Project Team
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

import android.content.SharedPreferences;
import android.location.Location;
import android.os.Build;
import android.os.Process;
import android.preference.PreferenceManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.microg.nlp.api.CellBackendHelper;
import org.microg.nlp.api.HelperLocationBackendService;
import org.microg.nlp.api.LocationHelper;
import org.microg.nlp.api.WiFiBackendHelper;

import java.util.Set;

import static org.microg.nlp.api.CellBackendHelper.Cell;
import static org.microg.nlp.api.WiFiBackendHelper.WiFi;

public class BackendService extends HelperLocationBackendService
        implements WiFiBackendHelper.Listener, 
                   CellBackendHelper.Listener,
                   LocationCallback
{

    private static final String TAG = "IchnaeaBackendService";
    private static final String SERVICE_URL = "https://location.services.mozilla.com/v1/geolocate?key=%s";
    private static final String API_KEY = "068ab754-c06b-473d-a1e5-60e7b1a2eb77";

    private long RATE_LIMIT_MS_FLOOR = 10000;
    private long EXP_BACKOFF_RATE = 1;

    private static BackendService instance;

    private boolean running = false;
    private Set<WiFi> wiFis;
    private Set<Cell> cells;
    private long lastRequestTime = 0;

    private boolean useWiFis = true;
    private boolean useCells = true;

    private static final String PROVIDER = "ichnaea";
    private String lastRequest = null;
    private Location lastResponse = null;


    @Override
    synchronized public void onCreate() {
        super.onCreate();
        reloadSettings();
        reloadInstanceSettings();

    }

    @Override
    synchronized public boolean canRun() {
        long delay = RATE_LIMIT_MS_FLOOR * EXP_BACKOFF_RATE;
        return (lastRequestTime + delay > System.currentTimeMillis());
    }

    // Methods to extend or reduce the backoff time

    @Override
    synchronized public void extendBackoff() {
        if (EXP_BACKOFF_RATE < 1024) {
            EXP_BACKOFF_RATE *= 2;
        }
    }

    @Override
    synchronized public void reduceBackoff() {
        if (EXP_BACKOFF_RATE > 1) {
            EXP_BACKOFF_RATE /= 2;
        }
    }

    @Override
    synchronized public void resultCallback(Location location_result) {
        if (location_result == null) {
            if (lastResponse == null) {
                // There isn't even a lastResponse to work with
                Log.d(TAG, "No previous location to replay");
                return;
            }
            location_result = LocationHelper.create(PROVIDER, lastResponse.getLatitude(), lastResponse.getLongitude(), lastResponse.getAccuracy());
            Log.d(TAG, "Replaying location " + location_result);
        }
        lastRequestTime = System.currentTimeMillis();
        lastResponse  = location_result;
        report(location_result);
    }

    @Override
    synchronized protected void onOpen() {
        super.onOpen();
        reloadSettings();
        instance = this;
        running = true;
        Log.d(TAG, "Activating instance at process " + Process.myPid());
    }

    public static void reloadInstanceSettings() {
        if (instance != null) {
            instance.reloadSettings();
        } else {
            Log.d(TAG, "No instance found active.");
        }
    }

    private void reloadSettings() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        removeHelpers();
        if (preferences.getBoolean("use_cells", true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            addHelper(new CellBackendHelper(this, this));
        } else {
            cells = null;
        }
        if (preferences.getBoolean("use_wifis", true)) {
            addHelper(new WiFiBackendHelper(this, this));
        } else {
            wiFis = null;
        }
    }

    @Override
    synchronized protected void onClose() {
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
        if (running) {
            startCalculate();
        }
    }

    @Override
    public void onCellsChanged(Set<Cell> cells) {
        this.cells = cells;
        Log.d(TAG, "Cells: " + cells.size());
        if (running) {
            startCalculate();
        }
    }

    @Override
    synchronized protected Location update() {
        return super.update();
    }

    synchronized private void startCalculate() {
        final Set<WiFi> wiFis = this.wiFis;
        final Set<Cell> cells = this.cells;
        if ((cells == null || cells.isEmpty()) && (wiFis == null || wiFis.size() < 2)) return;

        try {
            final String request = createRequest(cells, wiFis);
            IchnaeaRequester requester = new IchnaeaRequester(this, request);
            Thread t = new Thread(requester);
            t.start();
        } catch (Exception e) {
            Log.w(TAG, e);
        }
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

    private static String createRequest(Set<Cell> cells, Set<WiFi> wiFis) throws JSONException {
        JSONObject jsonObject = new JSONObject();
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
                //wifiAccessPoint.put("age", age);
                if (wiFi.getChannel() != -1) wifiAccessPoint.put("channel", wiFi.getChannel());
                if (wiFi.getFrequency() != -1)
                    wifiAccessPoint.put("frequency", wiFi.getFrequency());
                wifiAccessPoint.put("signalStrength", wiFi.getRssi());
                //wifiAccessPoint.put("signalToNoiseRatio", signalToNoiseRatio);
                wifiAccessPoints.put(wifiAccessPoint);
            }
        }
        jsonObject.put("cellTowers", cellTowers);
        jsonObject.put("wifiAccessPoints", wifiAccessPoints);
        jsonObject.put("fallbacks", new JSONObject().put("lacf", true).put("ipf", false));
        return jsonObject.toString();
    }
}
