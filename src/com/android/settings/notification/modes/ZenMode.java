/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settings.notification.modes;

import static android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
import static android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY;
import static android.service.notification.SystemZenRules.getTriggerDescriptionForScheduleEvent;
import static android.service.notification.SystemZenRules.getTriggerDescriptionForScheduleTime;
import static android.service.notification.ZenModeConfig.tryParseEventConditionId;
import static android.service.notification.ZenModeConfig.tryParseScheduleConditionId;

import static com.google.common.base.Preconditions.checkState;

import static java.util.Objects.requireNonNull;

import android.annotation.SuppressLint;
import android.app.AutomaticZenRule;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.service.notification.SystemZenRules;
import android.service.notification.ZenDeviceEffects;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenPolicy;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settings.R;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Objects;

/**
 * Represents either an {@link AutomaticZenRule} or the manual DND rule in a unified way.
 *
 * <p>It also adapts other rule features that we don't want to expose in the UI, such as
 * interruption filters other than {@code PRIORITY}, rules without specific icons, etc.
 */
class ZenMode {

    private static final String TAG = "ZenMode";

    static final String MANUAL_DND_MODE_ID = "manual_dnd";

    // Must match com.android.server.notification.ZenModeHelper#applyCustomPolicy.
    private static final ZenPolicy POLICY_INTERRUPTION_FILTER_ALARMS =
            new ZenPolicy.Builder()
                    .disallowAllSounds()
                    .allowAlarms(true)
                    .allowMedia(true)
                    .allowPriorityChannels(false)
                    .build();

    // Must match com.android.server.notification.ZenModeHelper#applyCustomPolicy.
    private static final ZenPolicy POLICY_INTERRUPTION_FILTER_NONE =
            new ZenPolicy.Builder()
                    .disallowAllSounds()
                    .hideAllVisualEffects()
                    .allowPriorityChannels(false)
                    .build();

    private final String mId;
    private AutomaticZenRule mRule;
    private final boolean mIsActive;
    private final boolean mIsManualDnd;

    ZenMode(String id, AutomaticZenRule rule, boolean isActive) {
        this(id, rule, isActive, false);
    }

    private ZenMode(String id, AutomaticZenRule rule, boolean isActive, boolean isManualDnd) {
        mId = id;
        mRule = rule;
        mIsActive = isActive;
        mIsManualDnd = isManualDnd;
    }

    static ZenMode manualDndMode(AutomaticZenRule manualRule, boolean isActive) {
        return new ZenMode(MANUAL_DND_MODE_ID, manualRule, isActive, true);
    }

    @NonNull
    public String getId() {
        return mId;
    }

    @NonNull
    public AutomaticZenRule getRule() {
        return mRule;
    }

    @NonNull
    public ListenableFuture<Drawable> getIcon(@NonNull Context context,
            @NonNull IconLoader iconLoader) {
        if (mIsManualDnd) {
            return Futures.immediateFuture(requireNonNull(
                    context.getDrawable(R.drawable.ic_do_not_disturb_on_24dp)));
        }

        return iconLoader.getIcon(context, mRule);
    }

    @NonNull
    public ZenPolicy getPolicy() {
        switch (mRule.getInterruptionFilter()) {
            case INTERRUPTION_FILTER_PRIORITY:
            case NotificationManager.INTERRUPTION_FILTER_ALL:
                return requireNonNull(mRule.getZenPolicy());

            case NotificationManager.INTERRUPTION_FILTER_ALARMS:
                return POLICY_INTERRUPTION_FILTER_ALARMS;

            case NotificationManager.INTERRUPTION_FILTER_NONE:
                return POLICY_INTERRUPTION_FILTER_NONE;

            case NotificationManager.INTERRUPTION_FILTER_UNKNOWN:
            default:
                Log.wtf(TAG, "Rule " + mId + " with unexpected interruptionFilter "
                        + mRule.getInterruptionFilter());
                return requireNonNull(mRule.getZenPolicy());
        }
    }

    /**
     * Updates the {@link ZenPolicy} of the associated {@link AutomaticZenRule} based on the
     * supplied policy. In some cases this involves conversions, so that the following call
     * to {@link #getPolicy} might return a different policy from the one supplied here.
     */
    @SuppressLint("WrongConstant")
    public void setPolicy(@NonNull ZenPolicy policy) {
        ZenPolicy currentPolicy = getPolicy();
        if (currentPolicy.equals(policy)) {
            return;
        }

        if (mRule.getInterruptionFilter() == INTERRUPTION_FILTER_ALL) {
            Log.wtf(TAG, "Able to change policy without filtering being enabled");
        }

        // If policy is customized from any of the "special" ones, make the rule PRIORITY.
        if (mRule.getInterruptionFilter() != INTERRUPTION_FILTER_PRIORITY) {
            mRule.setInterruptionFilter(INTERRUPTION_FILTER_PRIORITY);
        }
        mRule.setZenPolicy(policy);
    }

    @NonNull
    public ZenDeviceEffects getDeviceEffects() {
        return mRule.getDeviceEffects() != null
                ? mRule.getDeviceEffects()
                : new ZenDeviceEffects.Builder().build();
    }

    public void setCustomModeConditionId(Context context, Uri conditionId) {
        checkState(SystemZenRules.PACKAGE_ANDROID.equals(mRule.getPackageName()),
                "Trying to change condition of non-system-owned rule %s (to %s)",
                mRule, conditionId);

        Uri oldCondition = mRule.getConditionId();
        mRule.setConditionId(conditionId);

        ZenModeConfig.ScheduleInfo scheduleInfo = tryParseScheduleConditionId(conditionId);
        if (scheduleInfo != null) {
            mRule.setType(AutomaticZenRule.TYPE_SCHEDULE_TIME);
            mRule.setOwner(ZenModeConfig.getScheduleConditionProvider());
            mRule.setTriggerDescription(
                    getTriggerDescriptionForScheduleTime(context, scheduleInfo));
            return;
        }

        ZenModeConfig.EventInfo eventInfo = tryParseEventConditionId(conditionId);
        if (eventInfo != null) {
            mRule.setType(AutomaticZenRule.TYPE_SCHEDULE_CALENDAR);
            mRule.setOwner(ZenModeConfig.getEventConditionProvider());
            mRule.setTriggerDescription(getTriggerDescriptionForScheduleEvent(context, eventInfo));
            return;
        }

        if (ZenModeConfig.isValidCustomManualConditionId(conditionId)) {
            mRule.setType(AutomaticZenRule.TYPE_OTHER);
            mRule.setOwner(ZenModeConfig.getCustomManualConditionProvider());
            mRule.setTriggerDescription("");
            return;
        }

        Log.wtf(TAG, String.format(
                "Changed condition of rule %s (%s -> %s) but cannot recognize which kind of "
                        + "condition it was!",
                mRule, oldCondition, conditionId));
    }

    public boolean canEditName() {
        return !isManualDnd();
    }

    public boolean canEditIcon() {
        return !isManualDnd();
    }

    public boolean canBeDeleted() {
        return !mIsManualDnd;
    }

    public boolean isManualDnd() {
        return mIsManualDnd;
    }

    public boolean isActive() {
        return mIsActive;
    }

    public boolean isSystemOwned() {
        return SystemZenRules.PACKAGE_ANDROID.equals(mRule.getPackageName());
    }

    @AutomaticZenRule.Type
    public int getType() {
        return mRule.getType();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        return obj instanceof ZenMode other
                && mId.equals(other.mId)
                && mRule.equals(other.mRule)
                && mIsActive == other.mIsActive;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mRule, mIsActive);
    }

    @Override
    public String toString() {
        return mId + "(" + (mIsActive ? "active" : "inactive") + ") -> " + mRule;
    }
}
