package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class DigitalWatchFaceService extends CanvasWatchFaceService  {
    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final String MAX_TEMPERATURE_KEY = "max_temperature";
    private static final String MIN_TEMPERATURE_KEY = "min_temperature";
    private static final String WEATHER_KEY = "weather_key";
    private static final String WEATHER_BITMAP_KEY = "weather_bitmap";

    private static final long NORMAL_UPDATE_RATE_MS = 500;

    private static final long MUTE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);
    public static final String MAX_TEMPERATURE_KEY_PREF = "max_temperature_pref";
    public static final String MIN_TEMPERATURE_KEY_PREF = "min_temperature_pref";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        static final String COLON_STRING = ":";

        static final int MUTE_ALPHA = 100;

        /** Alpha value for drawing time when not in mute mode. */
        static final int NORMAL_ALPHA = 255;

        static final int MSG_UPDATE_TIME = 0;

        long mInteractiveUpdateRateMs = NORMAL_UPDATE_RATE_MS;

        /** Handler to update the time periodically in interactive mode. */
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs =
                                    mInteractiveUpdateRateMs - (timeMs % mInteractiveUpdateRateMs);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };


        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(DigitalWatchFaceService.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };

        boolean mRegisteredReceiver = false;

        boolean mLowBitAmbient;

        Paint mBackgroundPaint;
        Paint mDatePaint;
        Paint mHourPaint;
        Paint mMinutePaint;
        Paint mAmPmPaint;
        Paint mColonPaint;
        Paint mCenterLinePaint;
        Paint mMaxTemperaturePaint;
        Paint mMinTemperaturePaint;
        Paint mWeatherBitmapPaint;
        float mColonWidth;
        boolean mMute;

        Calendar mCalendar;
        Date mDate;
        SimpleDateFormat mDateFormat;

        boolean mShouldDrawColons;
        float mXOffset;
        float mYOffset;
        float mLineHeight;
        float mCenterLineWidth;
        Bitmap mWeatherBitmap = null;
        Bitmap mWeatherBitmapTemp = null;

        String mMaxTemperatureString = "";
        String mMinTemperatureString = "";

        @Override // DataApi.DataListener
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.d("NARANJA", "DATA WAS CHANGED VEDA?");
            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataMap dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem()).getDataMap();
                    String path = dataEvent.getDataItem().getUri().getPath();
                    if (path.equals("/weather")) {
                        long timestamp = dataMap.getLong(WEATHER_KEY);
                        String maxTemperature = dataMap.getString(MAX_TEMPERATURE_KEY);
                        String minTemperature = dataMap.getString(MIN_TEMPERATURE_KEY);
                        Asset asset = dataMap.getAsset(WEATHER_BITMAP_KEY);
                        Bitmap bitmap = loadBitmapFromAsset(asset);
                        Log.d("NARANJA", "YES SON, DATA WAS CHANGED: " + timestamp + ", " + maxTemperature + ", " + minTemperature);
                        updateUi(maxTemperature, minTemperature, bitmap);
                    }
                }
            }
        }

        private void updateUi(String maxTemperature, String minTemperature, Bitmap bitmap) {
            mMaxTemperatureString = maxTemperature;
            mMinTemperatureString = minTemperature;
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(MAX_TEMPERATURE_KEY_PREF, maxTemperature);
            editor.putString(MIN_TEMPERATURE_KEY_PREF, minTemperature);
            editor.apply();
            mWeatherBitmap = bitmap;
            invalidate();
        }

        public Bitmap loadBitmapFromAsset(Asset asset) {
            if (asset == null) {
                throw new IllegalArgumentException("Asset must be non-null");
            }

            LoadBitmapAsyncTask loadBitmapAsyncTask = (LoadBitmapAsyncTask) new LoadBitmapAsyncTask().execute(asset);

            InputStream assetInputStream;
            try {
                assetInputStream = loadBitmapAsyncTask.get();
                return BitmapFactory.decodeStream(assetInputStream);

            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                return null;
            }
        }

        private class LoadBitmapAsyncTask extends AsyncTask <Asset, Void, InputStream> {

            @Override
            protected InputStream doInBackground(Asset... params) {
                InputStream assetInputStream = Wearable.DataApi.getFdForAsset(mGoogleApiClient, params[0])
                        .await()
                        .getInputStream();

                return assetInputStream;
            }
        }

        @Override
        public void onConnected(Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {

        }


        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(DigitalWatchFaceService.this)
                                      .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                                      .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                                      .setShowSystemUiTime(false)
                                      .build());

            Resources resources = DigitalWatchFaceService.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mLineHeight = resources.getDimension(R.dimen.digital_line_height);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.primary));
            mDatePaint = createTextPaint(resources.getColor(R.color.clouds));
            mHourPaint = createTextPaint(resources.getColor(android.R.color.white));
            mMinutePaint = createTextPaint(resources.getColor(android.R.color.white));
            mColonPaint = createTextPaint(resources.getColor(android.R.color.white));
            mCenterLinePaint = new Paint();
            mCenterLinePaint.setColor(resources.getColor(R.color.clouds));
            mMaxTemperaturePaint = createTextPaint(resources.getColor(android.R.color.white));
            mMinTemperaturePaint = createTextPaint(resources.getColor(R.color.clouds));
            mWeatherBitmapPaint = new Paint();
            mWeatherBitmapPaint.setAntiAlias(true);
            mWeatherBitmapPaint.setFilterBitmap(true);
            mWeatherBitmapPaint.setDither(true);

            mCalendar = Calendar.getInstance();
            mDate = new Date();
            initFormats();
        }

        private void initFormats() {
            mDateFormat = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault());
            mDateFormat.setCalendar(mCalendar);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();

            invalidate();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            adjustPaintColorToCurrentMode(mBackgroundPaint, getResources().getColor(android.R.color.black),
                                          getResources().getColor(R.color.primary));

            adjustBitmap();

            if (mLowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                mDatePaint.setAntiAlias(antiAlias);
                mHourPaint.setAntiAlias(antiAlias);
                mMinutePaint.setAntiAlias(antiAlias);
                mAmPmPaint.setAntiAlias(antiAlias);
                mColonPaint.setAntiAlias(antiAlias);
                mMaxTemperaturePaint.setAntiAlias(antiAlias);
                mMinTemperaturePaint.setAntiAlias(antiAlias);
                mWeatherBitmapPaint.setAntiAlias(antiAlias);
            }
            invalidate();

            // Whether the timer should be running depends on whether we're in ambient mode (as well
            // as whether we're visible), so we may need to start or stop the timer.
            updateTimer();
        }

        private void adjustBitmap() {
            if (isInAmbientMode()) {
                mWeatherBitmap = mWeatherBitmapTemp;
            } else {
                mWeatherBitmapTemp = mWeatherBitmap;
                mWeatherBitmap = null;
            }
        }

        private void adjustPaintColorToCurrentMode(Paint paint, int interactiveColor,
                                                   int ambientColor) {
            paint.setColor(isInAmbientMode() ? ambientColor : interactiveColor);
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);

            mShouldDrawColons = (System.currentTimeMillis() % 1000) < 500;

            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);

            float centerX = (canvas.getWidth() / 2);

            float x;
            String hourString;
            hourString = formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY));

            x = mHourPaint.measureText(hourString);
            canvas.drawText(hourString, centerX - x, mYOffset, mHourPaint);

            if (isInAmbientMode() || mMute || mShouldDrawColons) {
                canvas.drawText(COLON_STRING, centerX, mYOffset, mColonPaint);
            }

            x = mColonPaint.measureText(COLON_STRING);

            String minuteString = formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE));
            canvas.drawText(minuteString, centerX + x, mYOffset, mMinutePaint);

            if (getPeekCardPosition().isEmpty()) {
                String dateString = mDateFormat.format(mDate);
                x = mDatePaint.measureText(dateString) / 2;

                canvas.drawText(mDateFormat.format(mDate),
                                centerX - x, mYOffset + mLineHeight, mDatePaint);
            }

            canvas.drawLine(centerX - mCenterLineWidth,
                            mYOffset + (mLineHeight * 2),
                            centerX + mCenterLineWidth,
                            mYOffset + (mLineHeight * 2),
                            mCenterLinePaint);

            x = mMaxTemperaturePaint.measureText(mMaxTemperatureString);

            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            if (mMaxTemperatureString.isEmpty()) {
                mMaxTemperatureString = sharedPreferences.getString(MAX_TEMPERATURE_KEY_PREF, "");
            }
            if (mMinTemperatureString.isEmpty()) {
                mMinTemperatureString = sharedPreferences.getString(MIN_TEMPERATURE_KEY_PREF, "");
            }

            float maxTemperatureXPosition = centerX - (x/2);
            float minTemperatureXPosition = centerX + (x/2);
            if (mWeatherBitmap == null) {
                maxTemperatureXPosition = centerX - (3 * x/2);
            }

            canvas.drawText(mMaxTemperatureString,
                            maxTemperatureXPosition,
                            mYOffset + (mLineHeight * 4),
                            mMaxTemperaturePaint);

            if (mWeatherBitmap != null) {
                canvas.drawBitmap(mWeatherBitmap,
                                  null,
                                  new RectF(centerX - (2 * x),
                                            mYOffset + (mLineHeight * 3) - 5,
                                            centerX - x/2,
                                            mYOffset + (mLineHeight * 4) + 10),
                                  mWeatherBitmapPaint);
            }

            canvas.drawText(mMinTemperatureString,
                            minTemperatureXPosition,
                            mYOffset + (mLineHeight * 4),
                            mMinTemperaturePaint);
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }

        private Paint createTextPaint(int defaultInteractiveColor) {
            return createTextPaint(defaultInteractiveColor, NORMAL_TYPEFACE);
        }

        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            super.onInterruptionFilterChanged(interruptionFilter);

            boolean inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE;
            // We only need to update once a minute in mute mode.
            setInteractiveUpdateRateMs(inMuteMode ? MUTE_UPDATE_RATE_MS : NORMAL_UPDATE_RATE_MS);

            if (mMute != inMuteMode) {
                mMute = inMuteMode;
                int alpha = inMuteMode ? MUTE_ALPHA : NORMAL_ALPHA;
                mDatePaint.setAlpha(alpha);
                mHourPaint.setAlpha(alpha);
                mMinutePaint.setAlpha(alpha);
                mColonPaint.setAlpha(alpha);
                mAmPmPaint.setAlpha(alpha);
                invalidate();
            }
        }

        public void setInteractiveUpdateRateMs(long updateRateMs) {
            if (updateRateMs == mInteractiveUpdateRateMs) {
                return;
            }
            mInteractiveUpdateRateMs = updateRateMs;

            // Stop and restart the timer so the new update rate takes effect immediately.
            if (shouldTimerBeRunning()) {
                updateTimer();
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            mGoogleApiClient.connect();

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                mCalendar.setTimeZone(TimeZone.getDefault());
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            updateTimer();
        }

        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        private Paint createTextPaint(int defaultInteractiveColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(defaultInteractiveColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        private void registerReceiver() {
            if (mRegisteredReceiver) {
                return;
            }
            mRegisteredReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            DigitalWatchFaceService.this.registerReceiver(mReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredReceiver) {
                return;
            }
            mRegisteredReceiver = false;
            DigitalWatchFaceService.this.unregisterReceiver(mReceiver);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            mHourPaint.setTypeface(burnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);

            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = DigitalWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();

            mCenterLineWidth = resources.getDimension(R.dimen.center_line_width);

            mXOffset = resources.getDimension(isRound ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound ? R.dimen.digital_text_size_round :
                                                            R.dimen.digital_text_size);

            mDatePaint.setTextSize(resources.getDimension(R.dimen.digital_date_text_size));
            mHourPaint.setTextSize(textSize);
            mMinutePaint.setTextSize(textSize);
            mColonPaint.setTextSize(textSize);
            mMaxTemperaturePaint.setTextSize(resources.getDimension(R.dimen.digital_temperature_text_size));
            mMinTemperaturePaint.setTextSize(resources.getDimension(R.dimen.digital_temperature_text_size));

            mColonWidth = mColonPaint.measureText(COLON_STRING);
        }

    }
}
