/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {

    private static final String TAG = SunshineWatchFace.class.getName();

    private enum TEXT_TIPE {
        HOURS, DATE, HIGH, LOW
    }

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final String WEAR_PATH = "/weather_wear";
    private static final String WEAR_HIGH_KEY = "HIGH";
    private static final String WEAR_LOW_KEY = "MIN";
    private static final String WEAR_WEATHER_ID = "WEATHER_ID";

    private GoogleApiClient mGoogleApiClient;
    private String mHighTemperature;
    private String mLowTemperature;
    private int mWeatherId;

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;


    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener,
            DataApi.DataListener {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextTimePaint;
        Paint mTextDatePaint;
        Paint mTextHighTempPaint;
        Paint mTextLowTempPaint;

        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffsetTime;
        float mYOffsetTime;
        float mXOffsetDate;
        float mYOffsetDate;
        float mYOffsetTemp;


        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;


        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();


            initializePaintResources(resources);

            mCalendar = Calendar.getInstance();

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
        }

        private void setDimensOffSet(Resources resources) {
            //Measure sample Time
            mXOffsetTime = mAmbient ?
                    mTextTimePaint.measureText(resources.getString(R.string.sample_time)) / 2
                    : mTextTimePaint.measureText(resources.getString(R.string.sample_time_short));

            //Asign default y axis offset
            mYOffsetTime = resources.getDimension(R.dimen.digital_y_offset);
            //Measure sample Date
            mXOffsetDate = mTextDatePaint.measureText(resources.getString(R.string.sample_date)) / 2;
            //Asign default y axis offset
            mYOffsetDate = resources.getDimension(R.dimen.digital_y_offset_date);
            //Asign default y axis offset
            mYOffsetTemp = resources.getDimension(R.dimen.digital_y_offset_temp);


        }

        private void initializePaintResources(Resources resources) {
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.primary_light));

            mTextTimePaint = createTextPaint(resources, TEXT_TIPE.HOURS);

            mTextDatePaint = createTextPaint(resources, TEXT_TIPE.DATE);

            mTextHighTempPaint = createTextPaint(resources, TEXT_TIPE.HIGH);

            mTextLowTempPaint = createTextPaint(resources, TEXT_TIPE.LOW);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(Resources resources, TEXT_TIPE type) {
            Paint paint = new Paint();
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setColor(resources.getColor(R.color.primary_text));
            paint.setAntiAlias(true);
            switch (type) {
                case DATE:
                    paint.setTextSize(resources.getDimension(R.dimen.digital_text_size_date));
                    break;
                case HOURS:
                    paint.setTextSize(resources.getDimension(R.dimen.digital_text_size_hours));
                    break;
                case HIGH:
                    paint.setTextSize(resources.getDimension(R.dimen.digital_text_size_temp_high));
                    break;
                case LOW:
                    paint.setTextSize(resources.getDimension(R.dimen.digital_text_size_temp_low));
                    break;
                default:
                    break;
            }
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextTimePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }


        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            setDimensOffSet(getResources());
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw HH:MM in ambient mode or HH:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String time = generateFriendlyTimeFormat();
            // Draw date
            String dateText = generateFriendlyDateFormat();
            drawTimeAndDate(canvas, bounds, time, dateText);
            drawTemperature(canvas, bounds);
        }

        private void drawTimeAndDate(Canvas canvas, Rect bounds, String time, String dateText) {
            //Draw time properly
            canvas.drawText(time, bounds.centerX() - mXOffsetTime, mYOffsetTime, mTextTimePaint);

            //Draw date properly
            canvas.drawText(dateText, bounds.centerX() - mXOffsetDate, mYOffsetDate + 15, mTextDatePaint);
            //Draw accent divider
            if (!mAmbient) {
                Paint accetPaint = new Paint();
                accetPaint.setColor(getResources().getColor(R.color.accent));
                canvas.drawLine(bounds.centerX() - 40, mYOffsetDate + 30, bounds.centerX() + 40, mYOffsetDate + 30, accetPaint);
            }
        }

        /**
         * Method that generate a string with a Date in a human readable format
         *
         * @return The String value of the date.
         */
        private String generateFriendlyDateFormat() {
            String dayName = mCalendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.getDefault());
            String monthName = mCalendar.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.getDefault());
            int dayOfMonth = mCalendar.get(Calendar.DAY_OF_MONTH);
            int year = mCalendar.get(Calendar.YEAR);
            return String.format("%s, %s %d, %d", dayName.toUpperCase(), monthName.toUpperCase(), dayOfMonth, year);
        }

        /**
         * Method that generate a string with the Time in a human readable format
         *
         * @return The String value of time.
         */
        private String generateFriendlyTimeFormat() {
            return mAmbient
                    ? String.format("%02d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE))
                    : String.format("%02d:%02d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE), mCalendar.get(Calendar.SECOND));
        }

        private void drawTemperature(Canvas canvas, Rect bounds) {
            if (mHighTemperature != null && mLowTemperature != null && mWeatherId > 0) {
                Log.d(TAG, "drawTemperature: High temp. value: " + mHighTemperature);
                Log.d(TAG, "drawTemperature: Low temp. value " + mLowTemperature);
                Log.d(TAG, "drawTemperature: Weather_id value: " + mWeatherId);

                float highTextSize = mTextHighTempPaint.measureText(mHighTemperature);
                if (!mAmbient) {
                    float xOffset = bounds.centerX() - (highTextSize / 2);
                    canvas.drawText(mHighTemperature, xOffset, mYOffsetTemp, mTextHighTempPaint);
                    canvas.drawText(mHighTemperature, bounds.centerX() + (highTextSize / 2) + 20, mYOffsetTemp, mTextLowTempPaint);

                    Drawable b = getResources().getDrawable(IconUtils.getIconResourceForWeatherCondition(mWeatherId));
                    Bitmap icon = ((BitmapDrawable) b).getBitmap();
                    float scaledWidth = (mTextLowTempPaint.getTextSize() / icon.getHeight()) * icon.getWidth();
                    Bitmap weatherIcon = Bitmap.createScaledBitmap(icon, (int) scaledWidth, (int) mTextLowTempPaint.getTextSize(), true);
                    float iconXOffset = bounds.centerX() - ((highTextSize / 2) + weatherIcon.getWidth() + 30);
                    canvas.drawBitmap(weatherIcon, iconXOffset, mYOffsetTemp - weatherIcon.getHeight(), null);
                }
            }
        }


        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            Log.d(TAG, "onConnected: Connected");
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(TAG, "onConnectionSuspended: Connection suspended");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(TAG, "onConnectionFailed: " + connectionResult.getErrorCode() + "-" + connectionResult.getErrorMessage());
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {

            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = dataEvent.getDataItem();
                    if (item.getUri().getPath().compareTo(WEAR_PATH) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        mHighTemperature = dataMap.getString(WEAR_HIGH_KEY);
                        mLowTemperature = dataMap.getString(WEAR_LOW_KEY);
                        mWeatherId = dataMap.getInt(WEAR_WEATHER_ID);
                        Log.d(TAG, "onDataChanged: Obtained data " + mHighTemperature + " " + mLowTemperature + " " + mWeatherId);
                        invalidate();
                    }
                }
            }
        }
    }
}
