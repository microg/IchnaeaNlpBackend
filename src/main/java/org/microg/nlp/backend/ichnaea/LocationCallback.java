/*
 * SPDX-FileCopyrightText: 2015 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.nlp.backend.ichnaea;

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
