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

import android.app.DownloadManager;
import android.location.Location;
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
import java.util.Set;

/*
 * This class implements the runnable portion of a thread which
 * accepts requests to process a location request and returns results
 * via a callback
 */
public class IchnaeaRequester implements Runnable {

    private static final String TAG = "IchnaeaBackendService";
    private static final String SERVICE_URL = "https://location.services.mozilla.com/v1/geolocate?key=%s";
    private static final String API_KEY = "068ab754-c06b-473d-a1e5-60e7b1a2eb77";
    private static final String PROVIDER = "ichnaea";

    private LocationCallback callback = null;
    private String request;

    public IchnaeaRequester(LocationCallback backendService, String request) {
            this.callback = backendService;
            this.request = request;
    }


    public void run() {
        HttpURLConnection conn = null;
        Location response = null;
        try {
            conn = (HttpURLConnection) new URL(String.format(SERVICE_URL, API_KEY)).openConnection();
            conn.setDoOutput(true);
            conn.setDoInput(true);
            Log.d(TAG, "request: " + request);
            conn.getOutputStream().write(request.getBytes());
            int respCode = conn.getResponseCode();
            if ((respCode >= 400) && (respCode <= 599)) {
                // Increase exponential backoff time for
                // any 400 or 500 series status code
                this.callback.extendBackoff();
                this.callback.resultCallback(null);
                return;
            } else {
                // Adjust the backoff time back down
                // towards the floor value
                this.callback.reduceBackoff();
            }
            String r = new String(readStreamToEnd(conn.getInputStream()));
            Log.d(TAG, "response: " + r);
            JSONObject responseJson = new JSONObject(r);
            double lat = responseJson.getJSONObject("location").getDouble("lat");
            double lon = responseJson.getJSONObject("location").getDouble("lng");
            double acc = responseJson.getDouble("accuracy");
            response = LocationHelper.create(PROVIDER, lat, lon, (float) acc);
            this.callback.resultCallback(response);
        } catch (IOException | JSONException e) {
            if (conn != null) {
                InputStream is = conn.getErrorStream();
                if (is != null) {
                    try {
                        String error = new String(readStreamToEnd(is));
                        Log.w(TAG, "Error: " + error);
                    } catch (Exception ignored) {
                    }
                }
            }
            Log.w(TAG, e);
        }
        this.callback.resultCallback(null);
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
}

