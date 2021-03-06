/***************************************************************************************
 * Copyright (c) 2012 Norbert Nagold <norbert.nagold@gmail.com>                         *
 *                                                                                      *
 * This program is free software; you can redistribute it and/or modify it under        *
 * the terms of the GNU General Public License as published by the Free Software        *
 * Foundation; either version 3 of the License, or (at your option) any later           *
 * version.                                                                             *
 *                                                                                      *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY      *
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A      *
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.             *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License along with         *
 * this program.  If not, see <http://www.gnu.org/licenses/>.                           *
 ****************************************************************************************/

package com.ichi2.libanki.sync;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

import org.apache.http.HttpResponse;

import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.util.Log;

import com.ichi2.anki.AnkiDatabaseManager;
import com.ichi2.anki.AnkiDb;
import com.ichi2.anki.AnkiDroidApp;
import com.ichi2.anki2.R;
import com.ichi2.async.Connection;
import com.ichi2.libanki.Collection;


public class FullSyncer extends BasicHttpSyncer {

	Collection mCol;
	Connection mCon;

	public FullSyncer(Collection col, String hkey, Connection con) {
		super(hkey, con);
		mCol = col;
		mCon = con;
	} 

	@Override
	public Object[] download() {
		InputStream cont;
		try {
			HttpResponse ret = super.req("download");
			if (ret == null) {
				return null;
			}			
			cont = ret.getEntity().getContent();
			// TODO: check for upgradeRequired
//			if (cont.equals("upgradeRequired")) {
//				runHook("sync", "upgradeRequired");
//				return null;
//			}
		} catch (IllegalStateException e1) {
			throw new RuntimeException(e1);
		} catch (IOException e1) {
			return null;
		}
		String path = mCol.getPath();
		mCol.close(false);
		mCol = null;
		String tpath = path + ".tmp";
		if (!super.writeToFile(cont, tpath)) {
			return new Object[]{"sdAccessError"};
		}
		// check the received file is ok
		mCon.publishProgress(R.string.sync_check_download_file);
		try {
			AnkiDb d = AnkiDatabaseManager.getDatabase(tpath);
			if (!d.queryString("PRAGMA integrity_check").equalsIgnoreCase("ok")) {
				Log.e(AnkiDroidApp.TAG, "Full sync - downloaded file corrupt");
				return new Object[]{"remoteDbError"};
			}
		} catch (SQLiteDatabaseCorruptException e) {
			Log.e(AnkiDroidApp.TAG, "Full sync - downloaded file corrupt");
			return new Object[]{"remoteDbError"};
		} finally {
			AnkiDatabaseManager.closeDatabase(tpath);
		}
		// overwrite existing collection
		File newFile = new File(tpath);
		if (newFile.renameTo(new File(path))) {
			return new Object[]{"success"};
		} else {
			return new Object[]{"overwriteError"};
		}
	}

	@Override
	public Object[] upload() {
		// make sure it's ok before we try to upload
		mCon.publishProgress(R.string.sync_check_upload_file);
		if (!mCol.getDb().queryString("PRAGMA integrity_check").equalsIgnoreCase("ok")) {
			return new Object[]{"dbError"};
		}
		// apply some adjustments, then upload
		mCol.beforeUpload();
		String filePath = mCol.getPath();
		mCol.close();
		HttpResponse ret;
		mCon.publishProgress(R.string.sync_uploading_message);
		try {
			ret = super.req("upload", new FileInputStream(filePath));
			if (ret == null) {
				return null;
			}
			int status = ret.getStatusLine().getStatusCode();
			if (status != 200) {
				// error occurred
				return new Object[]{"error", status, ret.getStatusLine().getReasonPhrase()};
			} else {
				return new Object[]{super.stream2String(ret.getEntity().getContent())};
			}
		} catch (FileNotFoundException e) {
			throw new RuntimeException(e);
		} catch (IllegalStateException e) {
			throw new RuntimeException(e);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}