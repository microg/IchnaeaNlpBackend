/*
 * SPDX-FileCopyrightText: 2015 microG Project Team
 * SPDX-License-Identifier: Apache-2.0
 */

package org.microg.nlp.backend.ichnaea;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;

import org.microg.nlp.api.CellBackendHelper;
import org.microg.nlp.api.LocationHelper;

public class CellDatabase extends SQLiteOpenHelper {
    private final static String SELECTION = "mcc = ? AND mnc = ? AND type = ? AND lac = ? AND cid = ? AND psc = ?";
    private long MAX_AGE = 1000L*60*60*24*30; // 1 month

    public CellDatabase(Context context) {
        super(context, "cellcache.db", null, 1);
    }

    private String[] getSelectionArgs(CellBackendHelper.Cell cell) {
        return new String[]{String.valueOf(cell.getMcc()), String.valueOf(cell.getMnc()), String.valueOf(cell.getType().ordinal()), String.valueOf(cell.getLac()), String.valueOf(cell.getCid()), String.valueOf(cell.getPsc())};
    }

    public Location getLocation(CellBackendHelper.Cell cell) {
        if (cell == null) return null;
        try (Cursor cursor = getReadableDatabase().query("cells", new String[]{"lat", "lon", "acc", "time"}, SELECTION, getSelectionArgs(cell), null, null, null)) {
            if (cursor.moveToNext()) {
                if (cursor.getLong(3) > System.currentTimeMillis() - MAX_AGE) {
                    return LocationHelper.create("ichnaea", cursor.getDouble(0), cursor.getDouble(1), (float) cursor.getDouble(2));
                }
            }
        }
        return null;
    }

    public void putLocation(CellBackendHelper.Cell cell, Location location) {
        if (cell == null) return;
        ContentValues cv = new ContentValues();
        cv.put("mcc", cell.getMcc());
        cv.put("mnc", cell.getMnc());
        cv.put("lac", cell.getLac());
        cv.put("type", cell.getType().ordinal());
        cv.put("cid", cell.getCid());
        cv.put("psc", cell.getPsc());
        if (location != null) {
            cv.put("lat", location.getLatitude());
            cv.put("lon", location.getLongitude());
            cv.put("acc", location.getAccuracy());
            cv.put("time", location.getTime());
        } else  {
            cv.put("lat", 0.0);
            cv.put("lon", 0.0);
            cv.put("acc", 0.0);
            cv.put("time", System.currentTimeMillis());
        }
        getWritableDatabase().insertWithOnConflict("cells", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS cells;");
        db.execSQL("CREATE TABLE cells(mcc INTEGER NOT NULL, mnc INTEGER NOT NULL, type INTEGER NOT NULL, lac INTEGER NOT NULL, cid INTEGER NOT NULL, psc INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, acc REAL NOT NULL, time INTEGER NOT NULL);");
        db.execSQL("CREATE UNIQUE INDEX cells_index ON cells(mcc, mnc, type, lac, cid, psc);");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        onCreate(db);
    }
}
