/*
 * Copyright (C) 2014 Dominik Schürmann <dominik@dominikschuermann.de>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.sufficientlysecure.keychain.helper;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.Id;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.compatibility.DialogFragmentWorkaround;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.service.KeychainIntentService;
import org.sufficientlysecure.keychain.service.KeychainIntentServiceHandler;
import org.sufficientlysecure.keychain.ui.dialog.DeleteKeyDialogFragment;
import org.sufficientlysecure.keychain.ui.dialog.FileDialogFragment;
import org.sufficientlysecure.keychain.util.Log;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.widget.Toast;

import com.actionbarsherlock.app.SherlockFragmentActivity;

public class ExportHelper {
    protected FileDialogFragment mFileDialog;
    protected String mExportFilename;

    SherlockFragmentActivity activity;

    public ExportHelper(SherlockFragmentActivity activity) {
        super();
        this.activity = activity;
    }

    public void deleteKey(Uri dataUri, final int keyType, Handler deleteHandler) {
        long keyRingRowId = Long.valueOf(dataUri.getLastPathSegment());

        // Create a new Messenger for the communication back
        Messenger messenger = new Messenger(deleteHandler);

        DeleteKeyDialogFragment deleteKeyDialog = DeleteKeyDialogFragment.newInstance(messenger,
                new long[] { keyRingRowId }, keyType);

        deleteKeyDialog.show(activity.getSupportFragmentManager(), "deleteKeyDialog");
    }

    /**
     * Show dialog where to export keys
     * 
     * @param keyRingMasterKeyId
     *            if -1 export all keys
     */
    public void showExportKeysDialog(final Uri dataUri, final int keyType,
            final String exportFilename) {
        mExportFilename = exportFilename;

        // Message is received after file is selected
        Handler returnHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                if (message.what == FileDialogFragment.MESSAGE_OKAY) {
                    Bundle data = message.getData();
                    mExportFilename = data.getString(FileDialogFragment.MESSAGE_DATA_FILENAME);

                    long keyRingRowId = Long.valueOf(dataUri.getLastPathSegment());

                    // TODO?
                    long keyRingMasterKeyId = ProviderHelper.getSecretMasterKeyId(activity,
                            keyRingRowId);

                    exportKeys(keyRingMasterKeyId, keyType);
                }
            }
        };

        // Create a new Messenger for the communication back
        final Messenger messenger = new Messenger(returnHandler);

        DialogFragmentWorkaround.INTERFACE.runnableRunDelayed(new Runnable() {
            public void run() {
                String title = null;
                if (dataUri != null) {
                    // single key export
                    title = activity.getString(R.string.title_export_key);
                } else {
                    title = activity.getString(R.string.title_export_keys);
                }

                String message = null;
                if (keyType == Id.type.public_key) {
                    message = activity.getString(R.string.specify_file_to_export_to);
                } else {
                    message = activity.getString(R.string.specify_file_to_export_secret_keys_to);
                }

                mFileDialog = FileDialogFragment.newInstance(messenger, title, message,
                        exportFilename, null, Id.request.filename);

                mFileDialog.show(activity.getSupportFragmentManager(), "fileDialog");
            }
        });
    }

    /**
     * Export keys
     * 
     * @param keyRingMasterKeyId
     *            if -1 export all keys
     */
    public void exportKeys(long keyRingMasterKeyId, int keyType) {
        Log.d(Constants.TAG, "exportKeys started");

        // Send all information needed to service to export key in other thread
        Intent intent = new Intent(activity, KeychainIntentService.class);

        intent.setAction(KeychainIntentService.ACTION_EXPORT_KEYRING);

        // fill values for this action
        Bundle data = new Bundle();

        data.putString(KeychainIntentService.EXPORT_FILENAME, mExportFilename);
        data.putInt(KeychainIntentService.EXPORT_KEY_TYPE, keyType);

        if (keyRingMasterKeyId == -1) {
            data.putBoolean(KeychainIntentService.EXPORT_ALL, true);
        } else {
            data.putLong(KeychainIntentService.EXPORT_KEY_RING_MASTER_KEY_ID, keyRingMasterKeyId);
        }

        intent.putExtra(KeychainIntentService.EXTRA_DATA, data);

        // Message is received after exporting is done in ApgService
        KeychainIntentServiceHandler exportHandler = new KeychainIntentServiceHandler(activity,
                R.string.progress_exporting, ProgressDialog.STYLE_HORIZONTAL) {
            public void handleMessage(Message message) {
                // handle messages by standard ApgHandler first
                super.handleMessage(message);

                if (message.arg1 == KeychainIntentServiceHandler.MESSAGE_OKAY) {
                    // get returned data bundle
                    Bundle returnData = message.getData();

                    int exported = returnData.getInt(KeychainIntentService.RESULT_EXPORT);
                    String toastMessage;
                    if (exported == 1) {
                        toastMessage = activity.getString(R.string.key_exported);
                    } else if (exported > 0) {
                        toastMessage = activity.getString(R.string.keys_exported, exported);
                    } else {
                        toastMessage = activity.getString(R.string.no_keys_exported);
                    }
                    Toast.makeText(activity, toastMessage, Toast.LENGTH_SHORT).show();

                }
            };
        };

        // Create a new Messenger for the communication back
        Messenger messenger = new Messenger(exportHandler);
        intent.putExtra(KeychainIntentService.EXTRA_MESSENGER, messenger);

        // show progress dialog
        exportHandler.showProgressDialog(activity);

        // start service with intent
        activity.startService(intent);
    }

    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == Id.request.filename) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                try {
                    String path = data.getData().getPath();
                    Log.d(Constants.TAG, "path=" + path);

                    // set filename used in export/import dialogs
                    mFileDialog.setFilename(path);
                } catch (NullPointerException e) {
                    Log.e(Constants.TAG, "Nullpointer while retrieving path!", e);
                }
            }
            return true;
        }

        return false;
    }
}