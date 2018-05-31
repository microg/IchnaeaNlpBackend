package org.microg.nlp.backend.ichnaea;

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

import android.location.Location;

public interface LocationCallback  {
    // returns True if the backoff expiry time doesn't cancel a run
    boolean canRun();

    // Methods to extend or reduce the backoff time
    void extendBackoff();
    void reduceBackoff();

    // How you return data back to the caller
    void resultCallback(Location location_result);
}
