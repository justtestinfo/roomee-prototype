/*
 * Copyright 2013 Chris Banes
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

package uk.co.senab.actionbarpulltorefresh.library;

import java.util.WeakHashMap;

import uk.co.senab.actionbarpulltorefresh.library.listeners.HeaderViewListener;
import uk.co.senab.actionbarpulltorefresh.library.listeners.OnRefreshListener;
import uk.co.senab.actionbarpulltorefresh.library.viewdelegates.ViewDelegate;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

public class PullToRefreshAttacher {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "PullToRefreshAttacher";

	/* Member Variables */

    private EnvironmentDelegate mEnvironmentDelegate;
    private HeaderTransformer mHeaderTransformer;

    private OnRefreshListener mOnRefreshListener;

    private Activity mActivity;
    private View mHeaderView;
    private FrameLayout mHeaderViewWrapper;
    private HeaderViewListener mHeaderViewListener;

    private final int mTouchSlop;
    private final float mRefreshScrollDistance;

    private float mInitialMotionY, mLastMotionY, mPullBeginY;
    private float mInitialMotionX;
    private boolean mIsBeingDragged, mIsRefreshing, mHandlingTouchEventFromDown;
    private View mViewBeingDragged;

    private final WeakHashMap<View, ViewDelegate> mRefreshableViews;

    private final boolean mRefreshOnUp;
    private final int mRefreshMinimizeDelay;
    private final boolean mRefreshMinimize;
    private boolean mIsDestroyed = false;

    private final Handler mHandler = new Handler();

    protected PullToRefreshAttacher(Activity activity, Options options) {
        if (options == null) {
            Log.i(LOG_TAG, "Given null options so using default options.");
            options = new Options();
        }

        mActivity = activity;
        mRefreshableViews = new WeakHashMap<View, ViewDelegate>();

        // Copy necessary values from options
        mRefreshScrollDistance = options.refreshScrollDistance;
        mRefreshOnUp = options.refreshOnUp;
        mRefreshMinimizeDelay = options.refreshMinimizeDelay;
        mRefreshMinimize = options.refreshMinimize;

        // EnvironmentDelegate
        mEnvironmentDelegate = options.environmentDelegate != null ? options.environmentDelegate
                : createDefaultEnvironmentDelegate();

        // Header Transformer
        mHeaderTransformer = options.headerTransformer != null ? options.headerTransformer
                : createDefaultHeaderTransformer();

        // Get touch slop for use later
        mTouchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();

        // Get Window Decor View
        final ViewGroup decorView = (ViewGroup) activity.getWindow()
                .getDecorView();

        // Create Header view and then add to Decor View
        mHeaderView = LayoutInflater.from(
                mEnvironmentDelegate.getContextForInflater(activity)).inflate(
                options.headerLayout, decorView, false);
        if (mHeaderView == null) {
            throw new IllegalArgumentException("Must supply valid layout id for header.");
        }
        // Make Header View invisible so it still gets a layout pass
        mHeaderView.setVisibility(View.INVISIBLE);

        mHeaderViewWrapper = new FrameLayout(mActivity);
        mHeaderViewWrapper.addView(mHeaderView);

        // Notify transformer
        mHeaderTransformer.onViewCreated(activity, mHeaderView);
        // TODO Remove the follow deprecated method call before v1.0
        mHeaderTransformer.onViewCreated(mHeaderView);

        // Now HeaderView to Activity
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (decorView.getWindowToken() != null) {
                    // The Decor View has a Window Token, so we can add the HeaderView!
                    addHeaderViewToActivity(mHeaderViewWrapper, mActivity);
                } else {
                    // The Decor View doesn't have a Window Token yet, post ourselves again...
                    mHandler.post(this);
                }
            }
        });
    }

    /**
     * Add a view which will be used to initiate refresh requests.
     *
     * @param view View which will be used to initiate refresh requests.
     */
    void addRefreshableView(View view, ViewDelegate viewDelegate) {
        if (isDestroyed()) return;

        // Check to see if view is null
        if (view == null) {
            Log.i(LOG_TAG, "Refreshable View is null.");
            return;
        }

        // ViewDelegate
        if (viewDelegate == null) {
            viewDelegate = InstanceCreationUtils.getBuiltInViewDelegate(view);
        }

        // View to detect refreshes for
        mRefreshableViews.put(view, viewDelegate);
    }

    void useViewDelegate(Class<?> viewClass, ViewDelegate delegate) {
        for (View view : mRefreshableViews.keySet()) {
            if (viewClass.isInstance(view)) {
                mRefreshableViews.put(view, delegate);
            }
        }
    }

    /**
     * Clear all views which were previously used to initiate refresh requests.
     */
    void clearRefreshableViews() {
        mRefreshableViews.clear();
    }

    /**
     * This method should be called by your Activity's or Fragment's
     * onConfigurationChanged method.
     *
     * @param newConfig The new configuration
     */
    public void onConfigurationChanged(Configuration newConfig) {
        mHeaderTransformer.onConfigurationChanged(mActivity, newConfig);
    }

    /**
     * Manually set this Attacher's refreshing state. The header will be
     * displayed or hidden as requested.
     *
     * @param refreshing
     *            - Whether the attacher should be in a refreshing state,
     */
    final void setRefreshing(boolean refreshing) {
        setRefreshingInt(null, refreshing, false);
    }

    /**
     * @return true if this Attacher is currently in a refreshing state.
     */
    final boolean isRefreshing() {
        return mIsRefreshing;
    }

    /**
     * Call this when your refresh is complete and this view should reset itself
     * (header view will be hidden).
     *
     * This is the equivalent of calling <code>setRefreshing(false)</code>.
     */
    final void setRefreshComplete() {
        setRefreshingInt(null, false, false);
    }

    /**
     * Set the Listener to be called when a refresh is initiated.
     */
    void setOnRefreshListener(OnRefreshListener listener) {
        mOnRefreshListener = listener;
    }

    /**
     * This should be called when you now longer need the Pull-to-Refresh functionality. Typically
     * from your {@link android.app.Activity#onDestroy()}.
     *
     * Please note, this is automatically when running on a device with Android v4.0 (Ice Cream Sandwich)
     * by the hosting Activity's {@link android.app.Activity#onDestroy() onDestroy()}.
     */
    void destroy() {
        if (mIsDestroyed) return; // We've already been destroyed

        // Remove the Header View from the Activity
        removeHeaderViewFromActivity(mHeaderViewWrapper, mActivity);

        // Lets clear out all of our internal state
        clearRefreshableViews();

        mActivity = null;
        mHeaderView = mHeaderViewWrapper = null;
        mHeaderViewListener = null;
        mEnvironmentDelegate = null;
        mHeaderTransformer = null;

        mIsDestroyed = true;
    }

    /**
     * Set a {@link HeaderViewListener} which is called when the visibility
     * state of the Header View has changed.
     *
     * @param listener
     */
    final void setHeaderViewListener(HeaderViewListener listener) {
        mHeaderViewListener = listener;
    }

    /**
     * @return The Header View which is displayed when the user is pulling, or
     *         we are refreshing.
     */
    final View getHeaderView() {
        return mHeaderView;
    }

    /**
     * @return The HeaderTransformer currently used by this Attacher.
     */
    HeaderTransformer getHeaderTransformer() {
        return mHeaderTransformer;
    }

    final boolean onInterceptTouchEvent(MotionEvent event) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onInterceptTouchEvent: " + event.toString());
        }

        // If we're not enabled or currently refreshing don't handle any touch
        // events
        if (isRefreshing()) {
            return false;
        }

        final float x = event.getX(), y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_MOVE: {
                // We're not currently being dragged so check to see if the user has
                // scrolled enough
                if (!mIsBeingDragged && mInitialMotionY > 0f) {
                    final float yDiff = y - mInitialMotionY;
                    final float xDiff = x - mInitialMotionX;

                    if (yDiff > xDiff && yDiff > mTouchSlop) {
                        mIsBeingDragged = true;
                        onPullStarted(y);
                    } else if (yDiff < -mTouchSlop) {
                        resetTouch();
                    }
                }
                break;
            }

            case MotionEvent.ACTION_DOWN: {
                // If we're already refreshing, ignore
                if (canRefresh(true)) {
                    for (View view : mRefreshableViews.keySet()) {
                        if (isViewBeingDragged(view, x, y)) {
                            mInitialMotionX = x;
                            mInitialMotionY = y;
                            mViewBeingDragged = view;
                        }
                    }
                }
                break;
            }

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                resetTouch();
                break;
            }
        }

        if (DEBUG) Log.d(LOG_TAG, "onInterceptTouchEvent. Returning " + mIsBeingDragged);

        return mIsBeingDragged;
    }

    boolean isViewBeingDragged(View view, float x, float y) {
        ViewDelegate delegate = mRefreshableViews.get(view);
        if (delegate != null) {
            return delegate.isReadyForPull(view, x, y);
        }
        return false;
    }

    final boolean onTouchEvent(MotionEvent event) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onTouchEvent: " + event.toString());
        }

        // Record whether our handling is started from ACTION_DOWN
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            mHandlingTouchEventFromDown = true;
        }

        // If we're being called from ACTION_DOWN then we must call through to
        // onInterceptTouchEvent until it sets mIsBeingDragged
        if (mHandlingTouchEventFromDown && !mIsBeingDragged) {
            onInterceptTouchEvent(event);
            return true;
        }

        if (mViewBeingDragged == null) {
            return false;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_MOVE: {
                // If we're already refreshing ignore it
                if (isRefreshing()) {
                    return false;
                }

                final float y = event.getY();

                if (mIsBeingDragged && y != mLastMotionY) {
                    final float yDx = y - mLastMotionY;

                    /**
                     * Check to see if the user is scrolling the right direction
                     * (down). We allow a small scroll up which is the check against
                     * negative touch slop.
                     */
                    if (yDx >= -mTouchSlop) {
                        onPull(mViewBeingDragged, y);
                        // Only record the y motion if the user has scrolled down.
                        if (yDx > 0f) {
                            mLastMotionY = y;
                        }
                    } else {
                        onPullEnded();
                        resetTouch();
                    }
                }
                break;
            }

            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                checkScrollForRefresh(mViewBeingDragged);
                if (mIsBeingDragged) {
                    onPullEnded();
                }
                resetTouch();
                break;
            }
        }

        return true;
    }

    void minimizeHeader() {
        if (isDestroyed()) return;

        mHeaderTransformer.onRefreshMinimized();

        if (mHeaderViewListener != null) {
            mHeaderViewListener.onStateChanged(mHeaderView, HeaderViewListener.STATE_MINIMIZED);
        }
    }

    void resetTouch() {
        mIsBeingDragged = false;
        mHandlingTouchEventFromDown = false;
        mInitialMotionY = mLastMotionY = mPullBeginY = -1f;
    }

    void onPullStarted(float y) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPullStarted");
        }
        showHeaderView();
        mPullBeginY = y;
    }

    void onPull(View view, float y) {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPull");
        }

        final float pxScrollForRefresh = getScrollNeededForRefresh(view);
        final float scrollLength = y - mPullBeginY;

        if (scrollLength < pxScrollForRefresh) {
            mHeaderTransformer.onPulled(scrollLength / pxScrollForRefresh);
        } else {
            if (mRefreshOnUp) {
                mHeaderTransformer.onReleaseToRefresh();
            } else {
                setRefreshingInt(view, true, true);
            }
        }
    }

    void onPullEnded() {
        if (DEBUG) {
            Log.d(LOG_TAG, "onPullEnded");
        }
        if (!mIsRefreshing) {
            reset(true);
        }
    }

    void showHeaderView() {
        if (mHeaderTransformer.showHeaderView()) {
            if (mHeaderViewListener != null) {
                mHeaderViewListener.onStateChanged(mHeaderView,
                        HeaderViewListener.STATE_VISIBLE);
            }
        }
    }

    void hideHeaderView() {
        if (mHeaderTransformer.hideHeaderView()) {
            if (mHeaderViewListener != null) {
                mHeaderViewListener.onStateChanged(mHeaderView,
                        HeaderViewListener.STATE_HIDDEN);
            }
        }
    }

    final Activity getAttachedActivity() {
        return mActivity;
    }

    protected EnvironmentDelegate createDefaultEnvironmentDelegate() {
        return new EnvironmentDelegate() {
            @Override
            public Context getContextForInflater(Activity activity) {
                Context context = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                    ActionBar ab = activity.getActionBar();
                    if (ab != null) {
                        context = ab.getThemedContext();
                    }
                }
                if (context == null) {
                    context = activity;
                }
                return context;
            }
        };
    }

    protected HeaderTransformer createDefaultHeaderTransformer() {
        return new DefaultHeaderTransformer();
    }

    private boolean checkScrollForRefresh(View view) {
        if (mIsBeingDragged && mRefreshOnUp && view != null) {
            if (mLastMotionY - mPullBeginY >= getScrollNeededForRefresh(view)) {
                setRefreshingInt(view, true, true);
                return true;
            }
        }
        return false;
    }

    private void setRefreshingInt(View view, boolean refreshing, boolean fromTouch) {
        if (isDestroyed()) return;

        if (DEBUG) Log.d(LOG_TAG, "setRefreshingInt: " + refreshing);
        // Check to see if we need to do anything
        if (mIsRefreshing == refreshing) {
            return;
        }

        resetTouch();

        if (refreshing && canRefresh(fromTouch)) {
            startRefresh(view, fromTouch);
        } else {
            reset(fromTouch);
        }
    }

    /**
     * @param fromTouch Whether this is being invoked from a touch event
     * @return true if we're currently in a state where a refresh can be
     *         started.
     */
    private boolean canRefresh(boolean fromTouch) {
        return !mIsRefreshing && (!fromTouch || mOnRefreshListener != null);
    }

    private float getScrollNeededForRefresh(View view) {
        return view.getHeight() * mRefreshScrollDistance;
    }

    private void reset(boolean fromTouch) {
        // Update isRefreshing state
        mIsRefreshing = false;

        // Remove any minimize callbacks
        if (mRefreshMinimize) {
            mHandler.removeCallbacks(mRefreshMinimizeRunnable);
        }

        // Hide Header View
        hideHeaderView();
    }

    private void startRefresh(View view, boolean fromTouch) {
        // Update isRefreshing state
        mIsRefreshing = true;

        // Call OnRefreshListener if this call has originated from a touch event
        if (fromTouch) {
            if (mOnRefreshListener != null) {
                mOnRefreshListener.onRefreshStarted(view);
            }
        }

        // Call Transformer
        mHeaderTransformer.onRefreshStarted();

        // Show Header View
        showHeaderView();

        // Post a runnable to minimize the refresh header
        if (mRefreshMinimize) {
            if (mRefreshMinimizeDelay > 0) {
                mHandler.postDelayed(mRefreshMinimizeRunnable, mRefreshMinimizeDelay);
            } else {
                mHandler.post(mRefreshMinimizeRunnable);
            }
        }
    }

    private boolean isDestroyed() {
        if (mIsDestroyed) {
            Log.i(LOG_TAG, "PullToRefreshAttacher is destroyed.");
        }
        return mIsDestroyed;
    }

    protected void addHeaderViewToActivity(View headerViewLayout, Activity activity) {
        // Get the Display Rect of the Decor View
        final View decorView = activity.getWindow().getDecorView();
        final Rect visibleRect = new Rect();
        decorView.getWindowVisibleDisplayFrame(visibleRect);

        // Create LayoutParams for adding the View as a panel
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.x = 0;
        params.y = visibleRect.top;
        params.gravity = Gravity.TOP;

        activity.getWindowManager().addView(headerViewLayout, params);
    }

    protected void removeHeaderViewFromActivity(View headerViewLayout, Activity activity) {
        activity.getWindowManager().removeViewImmediate(headerViewLayout);
    }

    private final Runnable mRefreshMinimizeRunnable = new Runnable() {
        @Override
        public void run() {
            minimizeHeader();
        }
    };
}
