/*
 * SPDX-FileCopyrightText: 2015 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
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
import org.microg.nlp.api.WiFiBackendHelper;

import java.util.Set;

import static org.microg.nlp.api.CellBackendHelper.Cell;
import static org.microg.nlp.api.WiFiBackendHelper.WiFi;

public class BackendService extends HelperLocationBackendService
        implements WiFiBackendHelper.Listener, 
                   CellBackendHelper.Listener,
                   LocationCallback {

    private static final String TAG = "IchnaeaBackendService";
    private static final long MAX_WIFI_AGE = 60000;
    private static final long MAX_CELLS_AGE = 120000;
    private static final long RATE_LIMIT_MS_FLOOR = 60000;
    private static final long RATE_LIMIT_MS_PADDING = 10000;
    private static final String PROVIDER = "ichnaea";

    private long expBackoffFactor = 0;

    private static BackendService instance;

    private CellDatabase cellDatabase;
    private boolean running = false;
    private Set<WiFi> wiFis;
    private long lastWifiTime = 0;
    private boolean wiFisEnabled = false;
    private Set<Cell> cells;
    private long lastCellTime = 0;
    private boolean cellsEnabled = false;
    private long lastRequestTime = 0;
    private long lastResponseTime = 0;

    private Location lastResponse = null;


    @Override
    public synchronized void onCreate() {
        super.onCreate();
        reloadSettings();
        reloadInstanceSettings();
    }

    @Override
    public synchronized boolean canRun() {
        long delay = RATE_LIMIT_MS_FLOOR + (RATE_LIMIT_MS_PADDING * expBackoffFactor);
        return (Math.max(lastRequestTime, lastResponseTime) + delay < System.currentTimeMillis());
    }

    // Methods to extend or reduce the backoff time

    @Override
    public synchronized void extendBackoff() {
        if (expBackoffFactor == 0) {
            expBackoffFactor = 1;
        } else if (expBackoffFactor > 0 && expBackoffFactor < 1024) {
            expBackoffFactor *= 2;
        }
    }

    @Override
    public synchronized void reduceBackoff() {
        if (expBackoffFactor == 1) {
            // Turn the exponential backoff off entirely
            expBackoffFactor = 0;
        } else {
            expBackoffFactor /= 2;
        }
    }

    @Override
    public synchronized void resultCallback(Location locationResult) {
        if (locationResult == null) {
            if (lastResponse == null) {
                // There isn't even a lastResponse to work with
                Log.d(TAG, "No previous location to replay");
                return;
            }
            Log.d(TAG, "Replaying location " + lastResponse);
        } else {
            lastResponseTime = System.currentTimeMillis();
            lastResponse = locationResult;
        }
        report(lastResponse);
    }

    @Override
    protected synchronized void onOpen() {
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
            cellsEnabled = true;
            addHelper(new CellBackendHelper(this, this));
            cellDatabase = new CellDatabase(this);
        } else {
            cells = null;
            lastCellTime = 0;
            cellsEnabled = false;
            cellDatabase = null;
        }
        if (preferences.getBoolean("use_wifis", true)) {
            wiFisEnabled = true;
            addHelper(new WiFiBackendHelper(this, this));
        } else {
            wiFis = null;
            lastWifiTime = 0;
            wiFisEnabled = false;
        }
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
        this.lastWifiTime = System.currentTimeMillis();
        Log.d(TAG, "WiFis: " + wiFis.size());
        if (running) {
            startCalculate();
        }
    }

    @Override
    public void onCellsChanged(Set<Cell> cells) {
        this.cells = cells;
        this.lastCellTime = System.currentTimeMillis();
        Log.d(TAG, "Cells: " + cells.size());
        if (running) {
            startCalculate();
        }
    }

    @Override
    protected synchronized Location update() {
        return super.update();
    }

    private synchronized void startCalculate() {
        Set<WiFi> wiFis = this.wiFis;
        if (lastWifiTime < System.currentTimeMillis() - MAX_WIFI_AGE) wiFis = null;
        if (wiFis != null && wiFis.size() < 2) wiFis = null;
        Set<Cell> cells = this.cells;
        if (lastCellTime < System.currentTimeMillis() - MAX_CELLS_AGE) cells = null;
        if (cells != null && cells.isEmpty()) cells = null;
        if (cells == null && wiFis == null) return;
        if ((lastWifiTime == 0 && wiFisEnabled) || (lastCellTime == 0 && cellsEnabled)) return;

        try {
            Location cachedCellLocation = null;
            if (cells != null && cells.size() == 1) {
                cachedCellLocation = cellDatabase.getLocation(cells.iterator().next());
            }
            if (cachedCellLocation != null && wiFis == null) {
                // Just use the cached cell location instantly
                resultCallback(cachedCellLocation);
                return;
            }
            if (this.canRun()) {
                lastRequestTime = System.currentTimeMillis();
                Cell singleCell = cells != null ? cells.iterator().next() : null;
                String cellRequest = cells != null ? createRequest(cells, null) : null;
                String wifiRequest = wiFis != null ? createRequest(cells, wiFis) : null;
                IchnaeaRequester requester = new IchnaeaRequester(this, cellDatabase, singleCell, cellRequest, wifiRequest);
                Thread t = new Thread(requester);
                t.start();
            } else {
                if (cachedCellLocation != null && cachedCellLocation.getAccuracy() <= lastResponse.getAccuracy()) {
                    resultCallback(cachedCellLocation);
                } else {
                    resultCallback(null);
                }
            }
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
