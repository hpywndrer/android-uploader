package com.nightscout.android;

import com.google.android.gms.analytics.HitBuilders;
import com.google.android.gms.analytics.Tracker;

import android.annotation.TargetApi;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import com.nightscout.android.db.DbUtils;
import com.nightscout.android.events.AndroidEventReporter;
import com.nightscout.android.preferences.AndroidPreferences;
import com.nightscout.core.Timestamped;
import com.nightscout.core.dexcom.CRCFailError;
import com.nightscout.core.drivers.AbstractDevice;
import com.nightscout.core.drivers.DeviceTransport;
import com.nightscout.core.drivers.DeviceType;
import com.nightscout.core.drivers.DexcomG4;
import com.nightscout.core.events.EventReporter;
import com.nightscout.core.events.EventSeverity;
import com.nightscout.core.events.EventType;
import com.nightscout.core.model.v2.Download;
import com.nightscout.core.model.v2.DownloadStatus;
import com.nightscout.core.model.v2.SensorGlucoseValue;
import com.nightscout.core.utils.ListUtils;

import net.tribe7.common.base.Optional;
import net.tribe7.common.base.Supplier;

import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.Minutes;
import org.joda.time.Seconds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import javax.inject.Inject;
import javax.inject.Named;

public class CollectorService extends Service {
    private static final Logger log = LoggerFactory.getLogger(CollectorService.class);

    // Max time to wait between grabbing info from the devices. Set to 5 minutes, because that is how often the dex reads from sensor.
    private static final int MAX_POLL_WAIT_SEC = 600;

    public static final String ACTION_SYNC = "com.nightscout.android.dexcom.action.SYNC";
    public static final String ACTION_POLL = "com.nightscout.android.dexcom.action.POLL";

    public static final String NUM_PAGES = "NUM_PAGES";
    public static final String STD_SYNC = "STD_SYNC";
    public static final String GAP_SYNC = "GAP_SYNC";
    public static final String SYNC_TYPE = "SYNC_TYPE";

    private EventReporter reporter;
    private AndroidPreferences preferences;
    private Tracker tracker;
    private PowerManager pm;
    protected AbstractDevice device = null;

    private AlarmManager alarmManager;
    private PendingIntent syncManager;

    private final IBinder mBinder = new LocalBinder();
    private long nextPoll = 0;

    @Inject
    @Named("dexcomDriverSupplier")
    private Supplier<DeviceTransport> dexcomDriverSupplier;

    @Override
    public void onCreate() {
        super.onCreate();
        reporter = AndroidEventReporter.getReporter(getApplicationContext());
        preferences = new AndroidPreferences(getApplicationContext());
        tracker = Nightscout.get(this).getTracker();

        pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);

        alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        Intent syncIntent = new Intent(ACTION_SYNC);
        syncIntent.putExtra(NUM_PAGES, 1);
        syncIntent.putExtra(SYNC_TYPE, STD_SYNC);
        syncManager = PendingIntent.getBroadcast(getApplicationContext(), 1, syncIntent, 0);

        dexcomDriverSupplier = new DexcomDriverSupplier(getApplicationContext(), preferences);

        initializeDevice();
    }

    public class LocalBinder extends Binder {
        public CollectorService getService() {
            // Return this instance of LocalService so clients can call public methods
            return CollectorService.this;
        }
    }

    private void initializeDevice() {
        DeviceType deviceType = preferences.getDeviceType();
        if (deviceType == DeviceType.DEXCOM_G4 || deviceType == DeviceType.DEXCOM_G4_SHARE2) {
            device = new DexcomG4(dexcomDriverSupplier, reporter);
        } else {
            log.warn("Unknown device {} encountered.. Doing nothing.", deviceType.name());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        DeviceTransport driver = dexcomDriverSupplier.get();
        if (driver != null && driver.isConnected()) {
            try {
                driver.close();
            } catch (IOException e) {
                log.error("Error closing driver", e);
            }
        }
        cancelPoll();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        log.debug("Starting service");
        if (intent == null) {
            log.debug("Intent is null!");
            return START_STICKY;
        }
        int numOfPages = intent.getIntExtra(NUM_PAGES, 2);
        int syncType = intent.getStringExtra(SYNC_TYPE).equals(STD_SYNC) ? 0 : 1;
        new AsyncDownload().execute(numOfPages, syncType);
        return super.onStartCommand(intent, flags, startId);
    }

    private class AsyncDownload extends AsyncTask<Integer, Integer, Download> {

        @Override
        protected Download doInBackground(Integer... params) {
            if (device == null) {
                log.error("Device not initialized. Returning.");
                return new Download.Builder().status(DownloadStatus.DEVICE_NOT_FOUND)
                    .timestamp(new DateTime().toString()).build();
            }
            long nextUploadTimeMs = Minutes.minutes(2).toStandardDuration().getMillis();

            Optional<Timestamped> newestElementInDb =
                Optional.<Timestamped>fromNullable(
                    DbUtils.getNewestElementInDb(preferences.getDeviceType()).orNull());

            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NSDownload");
            wl.acquire();

            Download download = null;

            try {
                download = device.downloadAllAfter(newestElementInDb);
                if (download.status != DownloadStatus.SUCCESS) {
                    log.error("Bad download, will try again");
                    setNextPoll(nextUploadTimeMs);
                    return null;
                }
                DbUtils.updateAllRecords(download);
            } catch (ArrayIndexOutOfBoundsException e) {
                reporter.report(EventType.DEVICE, EventSeverity.ERROR,
                        getApplicationContext().getString(R.string.event_fail_log));
                log.error("Unable to read from the dexcom, maybe it will work next time", e);
                tracker.send(new HitBuilders.ExceptionBuilder().setDescription("Array Index out of bounds")
                        .setFatal(false)
                        .build());
            } catch (NegativeArraySizeException e) {
                reporter.report(EventType.DEVICE, EventSeverity.ERROR,
                        getApplicationContext().getString(R.string.event_fail_log));
                log.error("Negative array exception from receiver", e);
                tracker.send(new HitBuilders.ExceptionBuilder().setDescription("Negative Array size")
                        .setFatal(false)
                        .build());
            } catch (IndexOutOfBoundsException e) {
                reporter.report(EventType.DEVICE, EventSeverity.ERROR,
                        getApplicationContext().getString(R.string.event_fail_log));
                log.error("IndexOutOfBounds exception from receiver", e);
                tracker.send(new HitBuilders.ExceptionBuilder().setDescription("IndexOutOfBoundsException")
                        .setFatal(false)
                        .build());
            } catch (CRCFailError e) {
                // FIXME: may consider localizing this catch at a lower level (like ReadData) so that
                // if the CRC check fails on one type of record we can capture the values if it
                // doesn't fail on other types of records. This means we'd need to broadcast back
                // partial results to the UI. Adding it to a lower level could make the ReadData class
                // more difficult to maintain - needs discussion.
                reporter.report(EventType.DEVICE, EventSeverity.ERROR,
                        getApplicationContext().getString(R.string.crc_fail_log));
                log.error("CRC failed", e);
                tracker.send(new HitBuilders.ExceptionBuilder().setDescription("CRC Failed")
                        .setFatal(false)
                        .build());
            } catch (Exception e) {
                reporter.report(EventType.DEVICE, EventSeverity.ERROR,
                        getApplicationContext().getString(R.string.unknown_fail_log));
                log.error("Unhandled exception caught", e);
                tracker.send(new HitBuilders.ExceptionBuilder().setDescription("Catch all exception in handleActionSync")
                        .setFatal(false)
                        .build());
            }
            wl.release();

            if (download != null) {
                Duration durationToNextPoll = Seconds.seconds(MAX_POLL_WAIT_SEC).toStandardDuration();
                Optional<SensorGlucoseValue> lastReadValue = ListUtils.lastOrEmpty(download.g4_data.sensor_glucose_values);
                if (lastReadValue.isPresent()) {
                    long readTimeDifferenceSec =
                        (download.g4_data.receiver_system_time_sec - lastReadValue
                            .get().timestamp.system_time_sec) % MAX_POLL_WAIT_SEC;
                    durationToNextPoll.minus(readTimeDifferenceSec);
                }
                nextUploadTimeMs = durationToNextPoll.getMillis();
            }
            setNextPoll(nextUploadTimeMs);
            return download;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public long getNextPoll() {
        return 1000 * 60 * 5 - (nextPoll - System.currentTimeMillis());
    }


    @TargetApi(Build.VERSION_CODES.KITKAT)
    public void setNextPoll(long millis) {
        log.debug("Setting next poll with Alarm for {}ms from now.", millis);
        nextPoll = System.currentTimeMillis() + millis;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, nextPoll, syncManager);
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, nextPoll, syncManager);
        }
    }

    public void cancelPoll() {
        log.debug("Cancelling next alarm poll.");
        nextPoll = 0;
        alarmManager.cancel(syncManager);
    }

}
