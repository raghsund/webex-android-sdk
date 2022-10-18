/*
 * Copyright 2016-2021 Cisco Systems Inc
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.ciscowebex.androidsdk.phone.internal;

import android.app.Notification;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Pair;
import android.util.Size;
import android.view.View;

import com.ciscowebex.androidsdk.CompletionHandler;
import com.ciscowebex.androidsdk.WebexError;
import com.ciscowebex.androidsdk.internal.Device;
import com.ciscowebex.androidsdk.internal.media.WMEngine;
import com.ciscowebex.androidsdk.internal.metric.CallAnalyzerReporter;
import com.ciscowebex.androidsdk.internal.model.FloorModel;
import com.ciscowebex.androidsdk.internal.model.LocusModel;
import com.ciscowebex.androidsdk.internal.model.LocusParticipantModel;
import com.ciscowebex.androidsdk.internal.model.LocusScheduledMeetingModel;
import com.ciscowebex.androidsdk.internal.model.LocusSelfModel;
import com.ciscowebex.androidsdk.internal.model.LocusSequenceModel;
import com.ciscowebex.androidsdk.internal.model.MediaConnectionModel;
import com.ciscowebex.androidsdk.internal.model.MediaShareModel;
import com.ciscowebex.androidsdk.internal.queue.NamedRunnable;
import com.ciscowebex.androidsdk.internal.queue.Queue;
import com.ciscowebex.androidsdk.internal.queue.Scheduler;
import com.ciscowebex.androidsdk.phone.AuxStream;
import com.ciscowebex.androidsdk.phone.Call;
import com.ciscowebex.androidsdk.phone.CallMembership;
import com.ciscowebex.androidsdk.phone.CallObserver;
import com.ciscowebex.androidsdk.phone.CallSchedule;
import com.ciscowebex.androidsdk.phone.MediaOption;
import com.ciscowebex.androidsdk.phone.MultiStreamObserver;
import com.ciscowebex.androidsdk.phone.Phone;
import com.ciscowebex.androidsdk.utils.Lists;
import com.ciscowebex.androidsdk.utils.WebexId;
import com.github.benoitdion.ln.Ln;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimerTask;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import me.helloworld.utils.Checker;
import me.helloworld.utils.Objects;

public class CallImpl implements Call {

    private final PhoneImpl phone;
    private final Device device;
    private final Direction direction;
    private final boolean group;

    private final String correlationId;
    private @Nullable
    MediaSession media;
    private CallObserver observer;
    private MultiStreamObserver streamObserver;
    private CallStatus status = CallStatus.INITIATED;
    private LocusModel model;

    private List<CallMembershipImpl> memberships = new ArrayList<>();
    private List<AuxStreamImpl> streams = new ArrayList<>();
    private CallMembershipImpl activeSpeaker;
    private int availableStreamCount = 0;
    private Set<CallSchedule> schedules = null;

    private boolean sendingVideo = true;
    private boolean sendingAudio = true;
    private boolean receivingVideo = true;
    private boolean receivingAudio = true;

    private Pair<View, View> videoViews;
    private View sharingView;

    private TimerTask keepAliveTask;
    private AtomicInteger predicate = new AtomicInteger(0);
    private AtomicInteger dtmfCorrelation = new AtomicInteger(1);

    private long connectedTime = 0;

    private List<NamedRunnable> peddingTasks = new ArrayList<>(1);

    public CallImpl(String correlationId, LocusModel model, PhoneImpl phone, Device device, MediaSession media, Direction direction, boolean group) {
        this.correlationId = correlationId;
        this.phone = phone;
        this.model = model;
        this.device = device;
        this.direction = direction;
        this.group = (group ? group : !model.isOneOnOne());
        setMedia(media);
        doLocusModel(model);
    }

    public LocusModel getModel() {
        return model;
    }

    public String getUrl() {
        return getModel().getCallUrl();
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public long getConnectedTime() {
        if (model != null) {
            Date startTime = model.getFullState().getLastActive();
            if (startTime != null) {
                return startTime.getTime();
            }
        }
        return connectedTime;
    }

    public @Nullable
    MediaSession getMedia() {
        return media;
    }

    void setMedia(MediaSession media) {
        this.media = media;
        if (media != null) {
            this.videoViews = media.getVideoViews();
            this.sharingView = media.getSharingView();
            CallAnalyzerReporter.shared.reportMediaCapabilities(this);
            CallAnalyzerReporter.shared.reportLocalSdpGenerated(this);
        }
    }

    boolean isGroup() {
        return group;
    }

    void setStatus(CallStatus status) {
        Ln.d("Call status changed from " + this.status + " to " + status);
        this.status = status;
        if (status == CallStatus.CONNECTED) {
            this.connectedTime = System.currentTimeMillis();
        }
    }

    @Override
    public Phone.FacingMode getFacingMode() {
        return media == null ? phone.getDefaultFacingMode() : media.getFacingMode();
    }

    @Override
    public void setFacingMode(Phone.FacingMode facingMode) {
        if (media != null) {
            media.setFacingMode(facingMode);
        }
    }

    @Override
    public Direction getDirection() {
        return direction;
    }

    @Override
    public CallStatus getStatus() {
        return status;
    }

    @Override
    public void setObserver(CallObserver observer) {
        this.observer = observer;
        Queue.serial.run(() -> {
            if (observer != null) {
                if (status == CallStatus.RINGING) {
                    Queue.main.run(() -> observer.onRinging(CallImpl.this));
                } else if (status == CallStatus.WAITING) {
                    Queue.main.run(() -> observer.onWaiting(CallImpl.this, model.getWaitReason()));
                } else if (status == CallStatus.CONNECTED) {
                    CallImpl.this.fireOnConnected();
                }
            }
            Queue.serial.yield();
        });
    }

    @Override
    public CallObserver getObserver() {
        return observer;
    }

    @Override
    public String getSpaceId() {
        WebexId space = (this.model == null) ? null : WebexId.from(this.model.getConversationUrl(), this.device);
        return space == null ? null : space.getBase64Id();
    }

    @Override
    public List<CallMembership> getMemberships() {
        synchronized (this) {
            return Collections.unmodifiableList(memberships);
        }
    }

    @Override
    public CallMembership getFrom() {
        List<CallMembership> memberships = getMemberships();
        for (CallMembership membership : memberships) {
            if (membership.isInitiator()) {
                return membership;
            }
        }
        return null;
    }

    @Override
    public CallMembership getTo() {
        List<CallMembership> memberships = getMemberships();
        for (CallMembership membership : memberships) {
            if (!membership.isInitiator()) {
                return membership;
            }
        }
        return null;
    }

    @Override
    public Set<CallSchedule> getSchedules() {
        synchronized (this) {
            return schedules == null ? null : Collections.unmodifiableSet(schedules);
        }
    }

    @Override
    public void setRemoteVideoRenderMode(VideoRenderMode mode) {
        if (media != null) {
            media.setRemoteVideoRenderMode(mode);
        }
    }

    @Override
    public void setVideoLayout(MediaOption.CompositedVideoLayout layout) {
        this.phone.layout(this, layout, null);
    }

    @Override
    public void setCompositedVideoLayout(MediaOption.CompositedVideoLayout layout) {
        this.phone.layout(this, layout, null);
    }

    @Override
    public void setCompositedVideoLayout(MediaOption.CompositedVideoLayout layout, @Nullable CompletionHandler<Void> callback) {
        this.phone.layout(this, layout, callback);
    }

    @Override
    public Size getLocalVideoViewSize() {
        return media == null ? new Size(0, 0) : media.getLocalVideoViewSize();
    }

    @Override
    public Size getRemoteVideoViewSize() {
        return media == null ? new Size(0, 0) : media.getRemoteVideoViewSize();
    }

    @Override
    public Size getSharingViewSize() {
        return media == null ? new Size(0, 0) : media.getRemoteSharingViewSize();
    }

    @Override
    public boolean isSendingDTMFEnabled() {
        return getModel().isLocalSupportDTMF();
    }

    @Override
    public boolean isRemoteSendingVideo() {
        return media != null && media.isRemoteVideoSending();
    }

    @Override
    public boolean isRemoteSendingAudio() {
        return !model.isRemoteAudioMuted();
    }

    @Override
    public boolean isRemoteSendingSharing() {
        return model.isFloorGranted() && !isSharingFromThisDevice();
    }

    @Override
    public boolean isSendingVideo() {
        return media != null && media.hasVideo() && media.isLocalVideoSending();
    }

    @Override
    public void setSendingVideo(boolean sending) {
        if (media != null) {
            media.setLocalVideoSending(sending);
            sendingVideo = sending;
        }
        if (sending) {
            CallAnalyzerReporter.shared.reportUnmuted(this, WMEngine.Media.Video);
        } else {
            CallAnalyzerReporter.shared.reportMuted(this, WMEngine.Media.Video);
        }
    }

    @Override
    public boolean isSendingAudio() {
        return media != null && media.hasAudio() && media.isLocalAudioSending();
    }

    @Override
    public void setSendingAudio(boolean sending) {
        if (media != null) {
            media.setLocalAudioSending(sending);
            sendingAudio = sending;
        }
        if (sending) {
            CallAnalyzerReporter.shared.reportUnmuted(this, WMEngine.Media.Audio);
        } else {
            CallAnalyzerReporter.shared.reportMuted(this, WMEngine.Media.Audio);
        }
    }

    @Override
    public boolean isReceivingVideo() {
        return media != null && media.hasVideo() && media.isRemoteVideoReceiving();
    }

    @Override
    public void setReceivingVideo(boolean receiving) {
        if (media != null) {
            media.setRemoteVideoReceiving(receiving);
            receivingVideo = receiving;
        }
    }

    @Override
    public boolean isReceivingAudio() {
        return media != null && media.hasAudio() && media.isRemoteAudioReceiving();
    }

    @Override
    public void setReceivingAudio(boolean receiving) {
        if (media != null) {
            media.setRemoteAudioReceiving(receiving);
            receivingAudio = receiving;
        }
    }

    @Override
    public boolean isReceivingSharing() {
        if (media == null) {
            return false;
        }
        if (!media.hasSharing() && media.hasVideo()) {
            return media.isRemoteVideoReceiving();
        } else {
            return media.hasSharing() && media.isRemoteSharingReceiving();
        }
    }

    @Override
    public void setReceivingSharing(boolean receiving) {
        if (media == null) {
            return;
        }
        if (!media.hasSharing() && media.hasVideo() && model.isFloorGranted()) {
            setReceivingVideo(receiving);
        } else if (media.hasSharing()) {
            media.setRemoteSharingReceiving(receiving);
        }
    }

    @Override
    public Pair<View, View> getVideoRenderViews() {
        return videoViews;
    }

    @Override
    public void setVideoRenderViews(@Nullable Pair<View, View> views) {
        Queue.main.run(() -> {
            if (media != null && media.hasVideo()) {
                Ln.d("setVideoRenderViews, old: " + videoViews + ", new: " + views);
                if (videoViews == null && views == null) {
                    Ln.d("Do nothing.");
                    return;
                }
                if (views != null && (views.first == null || views.second == null)) {
                    Ln.e("The local and remote video views must be set in same time");
                    return;
                }
                if (videoViews != null && views != null) {
                    if (videoViews.first == views.first && videoViews.second == views.second) {
                        Ln.d("Same views");
                        return;
                    }
                }
                videoViews = views;
                media.update(new MediaSession.MediaTypeVideo(views));
                phone.update(this, isSendingAudio(), isSendingVideo(), media.getLocalSdp(), result -> {
                    CallAnalyzerReporter.shared.reportLocalSdpGenerated(this);
                    WebexError error = result.getError();
                    if (error != null) {
                        Ln.d("Update media failed " + error);
                    }
                });
            }
        });
    }

    @Override
    public View getSharingRenderView() {
        return sharingView;
    }

    @Override
    public void setSharingRenderView(View view) {
        Queue.main.run(() -> {
            if (media != null && media.hasSharing()) {
                Ln.d("setSharingRenderView, old: " + sharingView + ", new: " + view);
                if (sharingView == view) {
                    Ln.d("Same view");
                    return;
                }
                sharingView = view;
                media.update(new MediaSession.MediaTypeSharing(view));
                phone.update(this, isSendingAudio(), isSendingVideo(), media.getLocalSdp(), result -> {
                    CallAnalyzerReporter.shared.reportLocalSdpGenerated(this);
                    WebexError error = result.getError();
                    if (error != null) {
                        Ln.d("Update media failed " + error);
                    }
                    FloorModel floor = model.getGrantedFloor();
                    if (floor != null) {
                        if (sharingView != null) {
                            media.joinSharing(floor.getGranted(), false);
                        } else {
                            media.leaveSharing(false);
                        }
                    }
                });
            }
        });
    }

    @Override
    public void acknowledge(@NonNull CompletionHandler<Void> callback) {
        phone.acknowledge(this, callback);
    }

    @Override
    public void answer(@NonNull MediaOption option, @NonNull CompletionHandler<Void> callback) {
        phone.answer(this, option, callback);
    }

    @Override
    public void reject(@NonNull CompletionHandler<Void> callback) {
        phone.reject(this, callback);
    }

    @Override
    public void hangup(@NonNull CompletionHandler<Void> callback) {
        phone.hangup(this, callback);
    }

    @Override
    public void sendDTMF(String tones, @NonNull CompletionHandler<Void> callback) {
        phone.dtmf(this, tones, dtmfCorrelation.getAndIncrement(), callback);
    }

    @Override
    public void sendFeedback(int rating, @Nullable String comment) {
        Map<String, String> info = new HashMap<>();
        info.put("user.rating", String.valueOf(rating));
        info.put("user.comments", comment);
        info.put("locusId", getModel().getUniqueCallID());
        info.put("participantId", getModel().getSelfId());
        phone.feedback(info);
    }

    @Override
    public void startSharing(@NonNull CompletionHandler<Void> callback) {
        startSharing(null, 0, callback);
    }

    @Override
    public void startSharing(@Nullable Notification notification, int notificationId, @NonNull @NotNull CompletionHandler<Void> callback) {
        phone.startSharing(this, notification, notificationId, callback);
        CallAnalyzerReporter.shared.reportShareInitiated(this, WMEngine.Media.Sharing);
    }

    @Override
    public void stopSharing(@NonNull CompletionHandler<Void> callback) {
        phone.stopSharing(this, callback);
    }

    void stopSharing() {
        phone.stopSharing(this, null);
    }

    @Override
    public boolean isSendingSharing() {
        return media != null && media.hasSharing() && media.isLocalSharingSending() && isSharingFromThisDevice();
    }

    public void setSendingSharing(boolean sending) {
        if (media != null) {
            media.setLocalSharingSending(sending);
        }
    }

    @Override
    public void openAuxStream(@NonNull View view) {
        Queue.main.run(() -> {
            if (media != null && streams.size() >= media.getCapability().getMaxNumberStreams()) {
                if (streamObserver != null) {
                    streamObserver.onAuxStreamChanged(new MultiStreamObserver.AuxStreamOpenedEvent(this, view, "Exceeded the auxiliary streams limit"));
                }
                return;
            }
            if (!isGroup()) {
                if (streamObserver != null) {
                    streamObserver.onAuxStreamChanged(new MultiStreamObserver.AuxStreamOpenedEvent(this, view, "Only available for group call"));
                }
                return;
            }
            AuxStreamImpl stream = (AuxStreamImpl) getAuxStream(view);
            if (stream != null) {
                if (streamObserver != null) {
                    streamObserver.onAuxStreamChanged(new MultiStreamObserver.AuxStreamOpenedEvent(this, view, "Open aux stream with same view"));
                }
                return;
            }
            if (streams.size() >= getAvailableAuxStreamCount()) {
                if (streamObserver != null) {
                    streamObserver.onAuxStreamChanged(new MultiStreamObserver.AuxStreamOpenedEvent(this, view, "Cannot exceed available stream count"));
                }
                return;
            }
            int vid = media.subscribeAuxVideo(view);
            if (vid == -1) {
                if (streamObserver != null) {
                    streamObserver.onAuxStreamChanged(new MultiStreamObserver.AuxStreamOpenedEvent(this, view, "Open stream fail"));
                }
                return;
            }
            stream = new AuxStreamImpl(this, vid, view);
            streams.add(stream);
            if (streamObserver != null) {
                streamObserver.onAuxStreamChanged(new MultiStreamObserver.AuxStreamOpenedEvent(this, view, null));
            }
        });
    }

    @Override
    public void closeAuxStream(@NonNull View view) {
        if (media == null) {
            return;
        }
        Queue.main.run(() -> {
            AuxStreamImpl stream = (AuxStreamImpl) getAuxStream(view);
            if (stream == null) {
                if (streamObserver != null) {
                    streamObserver.onAuxStreamChanged(new MultiStreamObserver.AuxStreamClosedEvent(this, view, "auxiliary stream not found"));
                }
                return;
            }
            media.unsubscribeAuxVideo(stream.getVid());
            streams.remove(stream);
            if (streamObserver != null) {
                streamObserver.onAuxStreamChanged(new MultiStreamObserver.AuxStreamClosedEvent(this, view, null));
            }
        });
    }

    @Override
    public AuxStream getAuxStream(@NonNull View view) {
        for (AuxStream stream : streams) {
            if (stream.getRenderView() == view) {
                return stream;
            }
        }
        return null;
    }

    public AuxStream getAuxStream(long vid) {
        if (vid >= 0) {
            for (AuxStream stream : streams) {
                if (stream instanceof AuxStreamImpl && ((AuxStreamImpl) stream).getVid() == vid) {
                    return stream;
                }
            }
        }
        return null;
    }

    @Override
    public int getAvailableAuxStreamCount() {
        return availableStreamCount;
    }

    @Override
    public int getOpenedAuxStreamCount() {
        return streams.size();
    }

    @Override
    public CallMembership getActiveSpeaker() {
        return activeSpeaker;
    }

    public void setActiveSpeaker(CallMembershipImpl membership) {
        activeSpeaker = membership;
    }

    @Override
    public void setMultiStreamObserver(MultiStreamObserver observer) {
        streamObserver = observer;
    }

    @Override
    public MultiStreamObserver getMultiStreamObserver() {
        return streamObserver;
    }

    @Override
    public void letIn(@NonNull CallMembership membership) {
        phone.admit(this, Lists.asList(membership), result -> {
        });
    }

    @Override
    public void letIn(@NonNull List<CallMembership> memberships) {
        phone.admit(this, memberships, result -> {
        });
    }

    @Override
    public void switchAudioOutput(AudioOutputMode audioOutputMode) {
        if (media != null && media.getMediaDeviceManager() != null && media.getMediaDeviceManager().getAudioDeviceConnectionManager() != null) {
            media.getMediaDeviceManager()
                    .getAudioDeviceConnectionManager()
                    .toggleAudioOutput(audioOutputMode);
        }
    }

    void startMedia() {
        if (media == null) {
            return;
        }
        String remoteSdp = model.getRemoteSdp(device.getDeviceUrl());
        if (remoteSdp == null) {
            Ln.e("Remote SDP is null");
            return;
        }
        media.setRemoteSdp(remoteSdp);
        for (AuxStreamImpl stream : streams) {
            if (stream.getVid() == -1 && stream.getRenderView() != null) {
                int vid = media.subscribeAuxVideo(stream.getRenderView());
                if (vid != -1) {
                    stream.setVid(vid);
                    media.addAuxVideoView(stream.getRenderView(), vid);
                }
            }
        }
        media.startCloud(this);
        if (media.hasSharing() && model.getGrantedFloor() != null) {
            media.joinSharing(model.getGrantedFloor().getGranted(), isSharingFromThisDevice());
        }

        CallAnalyzerReporter.shared.reportRemoteSdpReceived(this);
        CallAnalyzerReporter.shared.reportMediaEngineReady(this);
        CallAnalyzerReporter.shared.reportIceStart(this);

        Queue.main.run(() -> {
            Iterator<NamedRunnable> it = peddingTasks.iterator();
            while (it.hasNext()) {
                NamedRunnable runnable = it.next();
                if (runnable.getName() == NamedRunnable.Name.FireCallOnConnected) {
                    it.remove();
                    runnable.run();
                    break;
                }
            }
        });
    }

    void stopMedia() {
        if (media == null) {
            return;
        }
        //stopMedia must run in the main thread. Because WME will remove the videoRender view.
        if (media.hasSharing() && model.getGrantedFloor() != null) {
            media.leaveSharing(isSharingFromThisDevice());
        }
        for (AuxStreamImpl stream : streams) {
            if (stream.getVid() != -1) {
                media.unsubscribeAuxVideo(stream.getVid());
            }
        }
        media.stopCloud();
    }

    void updateMedia(boolean sendingAudio, boolean sendingVideo) {
        phone.update(this, sendingAudio, sendingVideo, null, result -> Ln.d("Update media: " + result));
    }

    void startKeepAlive() {
        int sec = getModel().getKeepAliceSecs(device.getDeviceUrl());
        if (sec <= 0) {
            return;
        }
        stopKeepAlive();
        keepAliveTask = Scheduler.schedule(() -> phone.keepAlive(this), sec * 1000, true);
    }

    void stopKeepAlive() {
        if (keepAliveTask != null) {
            keepAliveTask.cancel();
            keepAliveTask = null;
        }
    }

    void updateAuxStreamCount() {
        if (media == null) {
            return;
        }
        Queue.main.run(() -> {
            List<CallMembershipImpl> unduplicateMemberships = new ArrayList<>();
            LOOP:
            for (CallMembershipImpl membership : memberships) {
                if (!membership.getAssociatedUrls().isEmpty()) {
                    for (CallMembershipImpl m : memberships) {
                        if (membership.getAssociatedUrls().contains(m.getModel().getUrl())) {
                            continue LOOP;
                        }
                    }
                }
                unduplicateMemberships.add(membership);
            }
            int count = 0;
            for (CallMembershipImpl membership : unduplicateMemberships) {
                if (membership.getModel().getState() == LocusParticipantModel.State.JOINED && !membership.isSelf()) {
                    count = count + 1;
                }
            }
            int newAvailableAuxStreamCount = Math.min(count - 1, media.getAuxStreamCount() - 1);
            if (newAvailableAuxStreamCount < 0) {
                newAvailableAuxStreamCount = 0;
            } else if (getAvailableAuxStreamCount() >= media.getCapability().getMaxNumberStreams()
                    && newAvailableAuxStreamCount > media.getCapability().getMaxNumberStreams()) {
                availableStreamCount = media.getCapability().getMaxNumberStreams();
                return;
            }
            if (streamObserver != null) {
                int diff = newAvailableAuxStreamCount - availableStreamCount;
                if (diff > media.getCapability().getMaxNumberStreams()) {
                    diff = media.getCapability().getMaxNumberStreams();
                }
                if (diff > 0) {
                    for (int i = 0; i < diff; i++) {
                        View view = streamObserver.onAuxStreamAvailable();
                        if (view != null) {
                            openAuxStream(view);
                        }
                    }
                } else if (diff < 0) {
                    for (int i = 0; i < -diff; i++) {
                        View view = streamObserver.onAuxStreamUnavailable();
                        AuxStream stream = getAuxStream(view);
                        if (stream == null) {
                            view = null;
                        }
                        if (view == null) {
                            if (newAvailableAuxStreamCount < streams.size()) {
                                AuxStream last = Lists.getLast(streams);
                                if (last != null) {
                                    view = last.getRenderView();
                                }
                            }
                        }
                        if (view != null) {
                            closeAuxStream(view);
                        }
                    }
                }
            }
            availableStreamCount = newAvailableAuxStreamCount;
        });
    }

    void joinSharing(LocusParticipantModel participant, String shareId) {
        if (media == null) {
            return;
        }
        if (isSharingFromThisDevice()) {
            if (media.hasSharing()) {
                media.joinSharing(shareId, true);
            }
            if (media.isLocalSharingSending()) {
                if (observer != null) {
                    observer.onMediaChanged(new CallObserver.SendingSharingEvent(this, true));
                }
            }
        } else {
            if (media.hasSharing()) {
                media.joinSharing(shareId, false);
            }
            if (observer != null) {
                observer.onMediaChanged(new CallObserver.RemoteSendingSharingEvent(this, true));
            }
        }
        if (!isSharingFromThisDevice() || media.isLocalSharingSending()) {
            for (CallMembershipImpl membership : memberships) {
                if (Checker.isEqual(membership.getId(), participant.getId())) {
                    if (observer != null) {
                        observer.onCallMembershipChanged(new CallObserver.MembershipSendingSharingEvent(this, membership));
                    }
                }
            }
        }
        CallAnalyzerReporter.shared.reportShareInitiated(this, WMEngine.Media.Sharing);
    }

    void leaveSharing(LocusParticipantModel participant, String granted, LocusModel old) {
        if (media == null) {
            return;
        }
        CallAnalyzerReporter.shared.reportShareStopped(this, WMEngine.Media.Sharing);
        if (isSharingByModel(old)) {
            if (media.hasSharing()) {
                media.leaveSharing(true);
            }
            if (observer != null) {
                observer.onMediaChanged(new CallObserver.SendingSharingEvent(this, false));
            }
        } else {
            if (media.hasSharing()) {
                media.leaveSharing(false);
            }
            if (observer != null) {
                observer.onMediaChanged(new CallObserver.RemoteSendingSharingEvent(this, false));
            }
        }
        for (CallMembershipImpl membership : memberships) {
            if (Checker.isEqual(membership.getId(), participant.getId())) {
                if (observer != null) {
                    observer.onCallMembershipChanged(new CallObserver.MembershipSendingSharingEvent(this, membership));
                }
            }
        }
    }

    void end(CallObserver.CallDisconnectedEvent reason) {
        Ln.d("Call end by reason: " + reason);
        if (isSharingFromThisDevice()) {
            stopSharing(result -> Ln.d("Unshare screen by call end!"));
            if (media != null) {
                media.leaveSharing(true);
            }
        }
        if (reason instanceof CallObserver.RemoteDecline || reason instanceof CallObserver.RemoteLeft) {
            String url = model.getSelf() == null ? null : model.getSelf().getUrl();
            if (url != null) {
                phone.getService().leave(url, device, result -> Ln.d("Leave call: " + result));
            }
        }
        phone.removeCall(this);
        setStatus(CallStatus.DISCONNECTED);
        Queue.main.run(() -> {
            stopMedia();
            if (observer != null) {
                observer.onDisconnected(reason);
            }
        });
        CallAnalyzerReporter.shared.reportCallLeave(this);
        if (reason instanceof CallObserver.LocalDecline) {
            CallAnalyzerReporter.shared.reportCallDeclined(this);
        }
    }

    void update(LocusModel remote) {
        Ln.d("Update locus: " + model.getCallUrl());
        LocusModel local = this.model;
        if (local != null) {
            List<MediaConnectionModel> connections = remote.getMediaConnections();
            if (connections == null) {
                connections = local.getMediaConnections();
            }
            if (connections != null) {
                local.setMediaConnections(connections);
                remote.setMediaConnections(connections);
            }
        }

        boolean isDelta = remote.getBaseSequence() != null;
        boolean processLocus = false;
        if (local != null) {
            LocusSequenceModel.OverwriteWithResult overwriteWithResult;
            if (isDelta) {
                Ln.d("processLocusUpdate() processing with delta sequence information");
                overwriteWithResult = local.getSequence().overwriteWith(remote.getBaseSequence(), remote.getSequence());
            } else {
                Ln.d("processLocusUpdate() processing with full DTO");
                overwriteWithResult = local.getSequence().overwriteWith(remote.getSequence());
            }
            if (overwriteWithResult.equals(LocusSequenceModel.OverwriteWithResult.FALSE) && local.getFullState().isStartingSoon() != remote.getFullState().isStartingSoon()) {
                Ln.d("Overwrite was FALSE, but startingSoon flags do not match so forcing overwrite to TRUE");
                overwriteWithResult = LocusSequenceModel.OverwriteWithResult.TRUE;
            }
            if (overwriteWithResult.equals(LocusSequenceModel.OverwriteWithResult.TRUE)) {
                if (isDelta) {
                    remote = local.applyDelta(remote);
                }
                processLocus = true;
                Ln.d("Updating locus DTO and notifying listeners of data change for: %s", remote.getCallUrl());
            } else if (overwriteWithResult.equals(LocusSequenceModel.OverwriteWithResult.FALSE)) {
                Ln.d("Didn't overwrite locus DTO as new one was older version than one currently in memory.");
            } else if (overwriteWithResult.equals(LocusSequenceModel.OverwriteWithResult.DESYNC)) {
                Ln.d("Didn't overwrite locus DTO as new one was out of sync with one currently in memory.");
                phone.fetch(this, false);
                return;
            }
        } else if (isDelta) {
            // locus doesn't exist in the cache, but this DTO is a delta, so fetch a full DTO and reprocess
            phone.fetch(this, true);
            return;
        } else {
            processLocus = true;
        }
        if (processLocus) {
            doLocusModel(remote);
            LocusModel newModule = remote;
            Queue.main.run(() -> {
                if (local != null) {
                    if (newModule.isRemoteAudioMuted() != local.isRemoteAudioMuted()) {
                        if (observer != null) {
                            observer.onMediaChanged(new CallObserver.RemoteSendingAudioEvent(this, !newModule.isRemoteAudioMuted()));
                        }
                    }
                    doFloorUpdate(local, newModule);
                }
            });
        }
    }

    void doFloorUpdate(LocusModel old, LocusModel current) {
        if (current == null || !current.isValid()) {
            Ln.e("CallImpl.doFloorUpdate: remote is null or valid");
            return;
        }

        if (current.getGrantedFloor() != null) {
            if (old == null || !old.isValid() || old.getGrantedFloor() == null) {
                Ln.d("CallImpl.doFloorUpdate: remote floor granted, join sharing");
                joinSharing(current.getGrantedFloor().getBeneficiary(), current.getGrantedFloor().getGranted());
            } else if (old.getGrantedFloor() != null) {
                String oldMediaShareType = old.getGrantedMediaShare().getName();
                String oldMediaShareDeviceUrl = old.getGrantedFloor().getBeneficiary().getDeviceUrl();
                String currentMediaShareType = current.getGrantedMediaShare().getName();
                String currentMediaShareDeviceUrl = current.getGrantedFloor().getBeneficiary().getDeviceUrl();
                String oldResourceUrl = old.getGrantedMediaShare().getResourceUrl();
                String currentResourceUrl = current.getGrantedMediaShare().getResourceUrl();
                Ln.d("CallImpl.doFloorUpdate: floor state, remote: %s %s %s, local: %s %s %s",
                        currentMediaShareType, currentMediaShareDeviceUrl, currentResourceUrl,
                        oldMediaShareType, oldMediaShareDeviceUrl, oldResourceUrl);

                // Granted is replaced by another type
                boolean isShareTypeChanged = !oldMediaShareType.equals(currentMediaShareType);
                // Granted is replaced by another device
                boolean isMediaShareDeviceUrlChanged = !oldMediaShareDeviceUrl.equals(currentMediaShareDeviceUrl);
                // Granted is replaced by another whiteboard or a new whiteboard is granted
                boolean isResourceUrlChanged = !java.util.Objects.equals(oldResourceUrl, currentResourceUrl);
                if (!isShareTypeChanged && !isMediaShareDeviceUrlChanged && !isResourceUrlChanged) {
                    Ln.d("CallImpl.doFloorUpdate: floor state is not changed, return");
                    return;
                }

                // When I am sharing screen, other device start share screen
                boolean isMySharingReplaced = oldMediaShareType.equals(MediaShareModel.SHARE_CONTENT_TYPE)
                        && isSharingByModel(old)
                        && isMediaShareDeviceUrlChanged;
                // When other device is sharing screen, I start share screen
                boolean isSharingReplacedByMine = oldMediaShareType.equals(MediaShareModel.SHARE_CONTENT_TYPE)
                        && isSharingByModel(current)
                        && isMediaShareDeviceUrlChanged;

                if (isShareTypeChanged || isMySharingReplaced || isSharingReplacedByMine || isResourceUrlChanged) {
                    Ln.d("CallImpl.doFloorUpdate: share type or resource url or sharing device changed, leave and join sharing");
                    leaveSharing(old.getGrantedFloor().getBeneficiary(), old.getGrantedFloor().getGranted(), old);
                    joinSharing(current.getGrantedFloor().getBeneficiary(), current.getGrantedFloor().getGranted());
                    if (isMySharingReplaced) {
                        Ln.d("CallImpl.doFloorUpdate: my sharing replaced by other's, join sharing");
                        joinSharing(current.getGrantedFloor().getBeneficiary(), current.getGrantedFloor().getGranted());
                    }
                } else {
                    Ln.d("CallImpl.doFloorUpdate: only MediaShareDeviceUrlChanged, join sharing");
                    joinSharing(current.getGrantedFloor().getBeneficiary(), current.getGrantedFloor().getGranted());
                }
            }
        } else if (old != null && old.isValid() && old.getGrantedFloor() != null) {
            Ln.d("CallImpl.doFloorUpdate: remote released, leave sharing");
            leaveSharing(old.getGrantedFloor().getBeneficiary(), old.getGrantedFloor().getGranted(), old);
        } else {
            Ln.d("CallImpl.doFloorUpdate: no local or remote sharing, do nothing");
        }
    }

    void doLocusModel(LocusModel model) {
        Ln.d("doLocusModel: " + model.getCallUrl());
        this.model = model;
        List<LocusScheduledMeetingModel> meetings = model.getMeetings();
        Set<CallSchedule> oldSchedules = this.schedules;
        Set<CallSchedule> newSchedules = null;
        if (meetings != null) {
            newSchedules = new TreeSet<>();
            for (LocusScheduledMeetingModel meeting : meetings) {
                newSchedules.add(new InternalCallSchedule(meeting, model.getFullState()));
            }
        }
        if (!Lists.isEquals(oldSchedules, newSchedules)) {
            this.schedules = newSchedules;
            Queue.main.run(() -> {
                if (observer != null) {
                    observer.onScheduleChanged(this);
                }
            });
        }

        List<LocusParticipantModel> participants = Objects.defaultIfNull(model.getRawParticipants(), Collections.emptyList());
        List<CallMembershipImpl> oldMemberships = this.memberships;
        List<CallMembershipImpl> newMemberships = new ArrayList<>();
        List<CallObserver.CallMembershipChangedEvent> events = new ArrayList<>();
        for (LocusParticipantModel participant : participants) {
            if (!participant.isValidCiUser()) {
                continue;
            }
            CallMembershipImpl membership = null;
            for (CallMembershipImpl m : oldMemberships) {
                if (m.getId().equals(participant.getId())) {
                    membership = m;
                    break;
                }
            }
            if (membership == null) {
                membership = new CallMembershipImpl(participant, this);
                events.addAll(generateMembershipEvents(membership));
                events.add(new CallObserver.MembershipSendingAudioEvent(this, membership));
                events.add(new CallObserver.MembershipSendingVideoEvent(this, membership));
                events.add(new CallObserver.MembershipSendingSharingEvent(this, membership));
                events.add(new CallObserver.MembershipAudioMutedControlledEvent(this, membership));
            } else {
                CallMembership.State oldState = membership.getState();
                boolean tempSendingAudio = membership.isSendingAudio();
                boolean tempSendingVideo = membership.isSendingVideo();
                boolean tempSendingSharing = membership.isSendingSharing();
                boolean tempAudioMutedControlled = membership.isAudioMutedControlled();
                membership.setModel(participant);
                if (membership.getState() != oldState) {
                    events.addAll(generateMembershipEvents(membership));
                }
                if (membership.isSendingAudio() != tempSendingAudio) {
                    events.add(new CallObserver.MembershipSendingAudioEvent(this, membership));
                }
                if (membership.isSendingVideo() != tempSendingVideo) {
                    events.add(new CallObserver.MembershipSendingVideoEvent(this, membership));
                }
                if (membership.isSendingSharing() != tempSendingSharing) {
                    events.add(new CallObserver.MembershipSendingSharingEvent(this, membership));
                }
                if (membership.isAudioMutedControlled() != tempAudioMutedControlled) {
                    events.add(new CallObserver.MembershipAudioMutedControlledEvent(this, membership));
                }
            }
            if (!membership.isRemoved()) {
                newMemberships.add(membership);
            }
        }
        this.memberships = newMemberships;
        for (AuxStreamImpl stream : streams) {
            CallMembership old = stream.getPerson();
            for (CallMembership membership : memberships) {
                if (old != null && old.equals(membership)) {
                    stream.setPerson(membership);
                    break;
                }
            }
        }

        for (CallObserver.CallMembershipChangedEvent event : events) {
            Queue.main.run(() -> {
                if (observer != null) {
                    observer.onCallMembershipChanged(event);
                }
            });
        }
        Ln.d("CallMembership: " + this.memberships);
        updateAuxStreamCount();
        updateStatus(model);
        if (getStatus() != CallStatus.WAITING) {
            stopKeepAlive();
        }
        if ((getStatus() == CallStatus.CONNECTED || getStatus() == CallStatus.RINGING) && media != null && !media.isPrepared() && !media.isRunning()) {
            Ln.d("Update SDP before start media");
            phone.stopPreview();
            media.setPrepared(true);
            phone.update(this, isSendingAudio(), isSendingVideo(), media.getLocalSdp(), result -> {
                CallAnalyzerReporter.shared.reportLocalSdpGenerated(this);
                if (result.getError() != null) {
                    Ln.d("Update SDP failed: " + result.getError());
                    return;
                }
                if (!media.isRunning()) {
                    startMedia();
                    setSendingAudio(sendingAudio);
                    setSendingVideo(sendingVideo);
                    setReceivingAudio(receivingAudio);
                    setReceivingVideo(receivingVideo);
                }
            });
        }
    }

    private List<CallObserver.CallMembershipChangedEvent> generateMembershipEvents(CallMembershipImpl membership) {
        List<CallObserver.CallMembershipChangedEvent> events = new ArrayList<>();
        CallMembership.State state = membership.getState();
        if (state == CallMembership.State.JOINED) {
            events.add(new CallObserver.MembershipJoinedEvent(this, membership));
        } else if (state == CallMembership.State.LEFT) {
            events.add(new CallObserver.MembershipLeftEvent(this, membership));
            for (AuxStreamImpl stream : streams) {
                if (stream.getPerson() != null && ((CallMembershipImpl) stream.getPerson()).getId().equals(membership.getId())) {
                    stream.setPerson(null);
                    if (streamObserver != null) {
                        streamObserver.onAuxStreamChanged(new MultiStreamObserver.AuxStreamPersonChangedEvent(this, stream, membership, null));
                    }
                    break;
                }
            }
            if (getActiveSpeaker() != null && ((CallMembershipImpl) getActiveSpeaker()).getId().equals(membership.getId())) {
                activeSpeaker = null;
                if (observer != null) {
                    observer.onMediaChanged(new CallObserver.ActiveSpeakerChangedEvent(this, membership, null));
                }
            }
        } else if (state == CallMembership.State.DECLINED) {
            events.add(new CallObserver.MembershipDeclinedEvent(this, membership));
        } else if (state == CallMembership.State.WAITING) {
            events.add(new CallObserver.MembershipWaitingEvent(this, membership, model.getWaitReason()));
        }
        return events;
    }

    private void updateStatus(LocusModel model) {
        LocusSelfModel self = model.getSelf();
        if (self == null) {
            return;
        }
        CallStatus status = getStatus();
        if (status == CallStatus.INITIATED || status == CallStatus.RINGING || status == CallStatus.WAITING) {
            if (getDirection() == Direction.INCOMING) {
                if (isRemoteJoined()) {
                    if (self.isJoined(device.getDeviceUrl())) {
                        setStatus(CallStatus.CONNECTED);
                        fireOnConnected();
                    } else if (self.isDeclined(device.getDeviceUrl())) {
                        end(new CallObserver.LocalDecline(this));
                    } else if (self.isJoined()) {
                        end(new CallObserver.OtherConnected(this));
                    } else if (self.isDeclined()) {
                        end(new CallObserver.OtherDeclined(this));
                    } else if (model.isInactive()) {
                        end(new CallObserver.RemoteCancel(this));
                    }
                } else if (isRemoteDeclined() || isRemoteLeft()) {
                    end(new CallObserver.RemoteCancel(this));
                }
//                else if (model.isInactive()) {
//                    end(new CallObserver.RemoteCancel(this));
//                }
            } else if (getDirection() == Direction.OUTGOING) {
                if (self.isLefted(device.getDeviceUrl())) {
                    end(new CallObserver.LocalCancel(this));
                } else if (self.isJoined(device.getDeviceUrl())) {
                    if (isGroup()) {
                        setStatus(CallStatus.RINGING);
                        Queue.main.run(() -> {
                            if (observer != null) {
                                observer.onRinging(this);
                            }
                            setStatus(CallStatus.CONNECTED);
                            fireOnConnected();
                        });
                    } else {
                        if (isRemoteNotified()) {
                            setStatus(CallStatus.RINGING);
                            Queue.main.run(() -> {
                                if (observer != null) {
                                    observer.onRinging(this);
                                }
                            });
                        } else if (isRemoteJoined()) {
                            setStatus(CallStatus.CONNECTED);
                            fireOnConnected();
                        } else if (isRemoteDeclined()) {
                            end(new CallObserver.RemoteDecline(this));
                        }
                    }
                } else if (self.isInLobby()) {
                    setStatus(CallStatus.WAITING);
                    Queue.main.run(() -> {
                        if (observer != null) {
                            observer.onWaiting(this, model.getWaitReason());
                        }
                    });
                }
            }
        } else if (status == CallStatus.CONNECTED) {
            if (self.isLefted(device.getDeviceUrl())) {
                end(new CallObserver.LocalLeft(this));
            } else if (!isGroup() && isRemoteLeft()) {
                end(new CallObserver.RemoteLeft(this));
            }
        }
    }

    boolean isSharingFromThisDevice() {
        return isSharingByModel(model);
    }

    boolean isSharingByModel(LocusModel model) {
        if (model != null && model.isValid() && model.getGrantedFloor() != null && media != null && media.hasSharing()) {
            LocusParticipantModel p = model.getGrantedFloor().getBeneficiary();
            return p != null && Checker.isEqual(device.getDeviceUrl(), p.getDeviceUrl());
        }
        return false;
    }

    boolean isRemoteLeft() {
        if (isGroup()) {
            return false;
        }
        for (CallMembershipImpl membership : memberships) {
            if (!membership.isSelf() && membership.getState() != CallMembership.State.LEFT) {
                return false;
            }
        }
        return true;
    }

    boolean isRemoteDeclined() {
        if (isGroup()) {
            return false;
        }
        for (CallMembershipImpl membership : memberships) {
            if (!membership.isSelf() && membership.getState() != CallMembership.State.DECLINED) {
                return false;
            }
        }
        return true;
    }

    boolean isRemoteJoined() {
        if (isGroup()) {
//            for (CallMembershipImpl membership : memberships) {
//                if (!membership.isSelf() && membership.getState() == CallMembership.State.JOINED) {
//                    return true;
//                }
//            }
//            return false;
            return true;
        }
        for (CallMembershipImpl membership : memberships) {
            if (!membership.isSelf() && membership.getState() != CallMembership.State.JOINED) {
                return false;
            }
        }
        return true;
    }

    boolean isRemoteNotified() {
        if (isGroup()) {
            return true;
        }
        for (CallMembershipImpl membership : memberships) {
            if (!membership.isSelf() && (membership.getState() == CallMembership.State.IDLE || membership.getState() == CallMembership.State.NOTIFIED)) {
                return true;
            }
        }
        return false;
    }

    boolean isStatusIllegal() {
        if (!isGroup()) {
            if (getStatus() == CallStatus.INITIATED || getStatus() == CallStatus.RINGING) {
                return getDirection() == Direction.OUTGOING && isRemoteLeft();
            }
        }
        return false;
    }

    private void fireOnConnected() {
        Queue.main.run(() -> {
            NamedRunnable runnable = new NamedRunnable() {
                @Override
                public Name getName() {
                    return Name.FireCallOnConnected;
                }

                @Override
                public void run() {
                    if (observer != null) {
                        observer.onConnected(CallImpl.this);
                    }
                }
            };
            if (media == null || !media.isRunning()) {
                peddingTasks.add(runnable);
            } else {
                runnable.run();
            }
        });
    }
}
