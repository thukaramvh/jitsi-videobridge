/*
 * Copyright @ 2015-2018 Atlassian Pty Ltd
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
package org.jitsi.videobridge;

import org.jetbrains.annotations.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.transform.node.Node;
import org.jitsi.nlj.util.*;
import org.jitsi.rtp.UnparsedPacket;
import org.jitsi.rtp.rtcp.rtcpfb.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.event.*;
import org.jitsi.videobridge.datachannel.*;
import org.jitsi.videobridge.datachannel.protocol.*;
import org.jitsi.videobridge.sctp.SctpManager;
import org.jitsi_modified.sctp4j.*;

import java.io.*;
import java.lang.ref.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * Represents an endpoint in a conference (i.e. the entity associated with
 * a participant in the conference, which connects the participant's audio
 * and video channel). This might be an endpoint connected to this instance of
 * jitsi-videobridge, or a "remote" endpoint connected to another bridge in the
 * same conference (if Octo is being used).
 *
 * @author Boris Grozev
 */
public abstract class AbstractEndpoint extends PropertyChangeNotifier
    implements EncodingsManager.EncodingsUpdateListener
{
    /**
     * The (unique) identifier/ID of the endpoint of a participant in a
     * <tt>Conference</tt>.
     */
    private final String id;

    /**
     * The string used to identify this endpoint for the purposes of logging.
     */
    private final String loggingId;

    /**
     * A reference to the <tt>Conference</tt> this <tt>Endpoint</tt> belongs to.
     */
    private final Conference conference;

    /**
     * The list of <tt>Channel</tt>s associated with this <tt>Endpoint</tt>.
     */
    private final List<WeakReference<RtpChannel>> channels = new LinkedList<>();

    private final List<WeakReference<ColibriShim.Channel>> channelShims = new LinkedList<>();

    private IceUdpTransportManager transportManager;

    /**
     * The (human readable) display name of this <tt>Endpoint</tt>.
     */
    private String displayName;

    /**
     * The statistic Id of this <tt>Endpoint</tt>.
     */
    private String statsId;

    /**
     * The indicator which determines whether {@link #expire()} has been called
     * on this <tt>Endpoint</tt>.
     */
    private boolean expired = false;

    //Public for now since the channel needs to reach in and grab it
    public Transceiver transceiver;
    private SctpManager sctpManager;
    private ExecutorService receiverExecutor;
    private ExecutorService senderExecutor;
    // We'll still continue to share a single background executor, as I think it's sufficient.
    // TODO: Though should investigate how many threads may be needed, and also verify we don't have any concurrency
    // issues with the code using this pool
    private static ScheduledExecutorService backgroundExecutor =
            Executors.newScheduledThreadPool(1, new NameableThreadFactory("Background transceiver thread"));

    /**
     * Initializes a new {@link AbstractEndpoint} instance.
     * @param conference the {@link Conference} which this endpoint is to be a
     * part of.
     * @param id the ID of the endpoint.
     */
    protected AbstractEndpoint(Conference conference, String id)
    {
        this.conference = Objects.requireNonNull(conference, "conference");
        this.id = Objects.requireNonNull(id, "id");
        loggingId = conference.getLoggingId() + ",endp_id=" + id;
        receiverExecutor = Executors.newSingleThreadExecutor(new NameableThreadFactory("Receiver " + id + " executor"));
        senderExecutor = Executors.newSingleThreadExecutor(new NameableThreadFactory("Sender " + id + " executor"));
        transceiver = new Transceiver(getID(), receiverExecutor, senderExecutor, backgroundExecutor);
        transceiver.setIncomingRtpHandler(new Node("RTP receiver chain handler")
        {
            @Override
            protected void doProcessPackets(@NotNull List<PacketInfo> pkts)
            {
                handleIncomingRtp(pkts);
            }
        });
        transceiver.setIncomingRtcpHandler(new Node("RTCP receiver chain handler") {
            @Override
            public void doProcessPackets(@NotNull List<PacketInfo> pkts)
            {
                handleIncomingRtcp(pkts);
            }
        });
        conference.encodingsManager.subscribe(this);
    }

    @Override
    public void onNewSsrcAssociation(String epId, long primarySsrc, long secondarySsrc, String semantics) {
        transceiver.addSsrcAssociation(primarySsrc, secondarySsrc, semantics);
    }

    private DataChannelStack dataChannelStack;

    public void createSctpConnection() {
        System.out.println("Creating SCTP manager");
        this.sctpManager = new SctpManager(
                (data, offset, length) -> {
                    System.out.println("Sending outgoing SCTP data");
                    ((IceDtlsTransportManager)transportManager).sendDtlsData(new PacketInfo(new UnparsedPacket(ByteBuffer.wrap(data, offset, length))));
                    return 0;
                }
        );
        // NOTE(brian): as far as I know we always act as the 'server' for sctp connections, but if not we can make
        // which type we use dynamic
        SctpServerSocket socket = sctpManager.createServerSocket();
        socket.eventHandler = new SctpSocket.SctpSocketEventHandler()
        {
            @Override
            public void onReady()
            {
                System.out.println("SCTP connection is ready! Opening data channel");
                //TODO: open data channel -- if we do it this way though, maybe we'll miss a message?
                DataChannel dataChannel = dataChannelStack.createDataChannel(
                        DataChannelProtocolConstants.RELIABLE,
                        0,
                        0,
                        0,
                        "default");
                dataChannel.onDataChannelEvents(new DataChannelStack.DataChannelEventListener()
                {
                    @Override
                    public void onDataChannelOpened()
                    {
                        System.out.println("Locally created data channel opened!");
                    }
                });
                dataChannel.open();
            }

            @Override
            public void onDisconnected()
            {
                System.out.println("SCTP is disconnected!");
            }
        };
        dataChannelStack = new DataChannelStack(socket);
        dataChannelStack.onDataChannelStackEvents(new DataChannelStack.DataChannelStackEventListener()
        {
            @Override
            public void onDataChannelOpenedRemotely(DataChannel dataChannel)
            {
                System.out.println("Data channel was opened remotely!");
            }
        });
        //TODO: move this to an executor/pool
        socket.listen();
        new Thread(() -> {
            while (!socket.accept())
            {
                System.out.println("SCTP socket " + socket.hashCode() + " trying to accept connection");
                try
                {
                    Thread.sleep(100);
                } catch (InterruptedException e)
                {
                    System.out.println("Interrupted while trying to accept incoming SCTP connection: " + e.toString());
                    break;
                }
            }
            System.out.println("SCTP socket " + socket.hashCode() + " accepted connection");
        }).start();
    }

    //TODO(brian): not sure if this is the final way we'll associate the transport manager and endpoint/transceiver,
    // but it's a step.
    public void setTransportManager(IceUdpTransportManager transportManager)
    {
        this.transportManager = transportManager;

        //TODO: technically we want to start this once dtls is complete, is this good enough though?
        transportManager.onTransportConnected(() -> {
            System.out.println("Endpoint sees transport is connected, now reading incoming sctp packets");
            if (sctpManager != null) {
                new Thread(() -> {
                    LinkedBlockingQueue<PacketInfo> sctpPackets = ((IceDtlsTransportManager)getConference().getTransportManager(AbstractEndpoint.this.id)).sctpAppPackets;
                    while (true) {
                        try {
                            PacketInfo sctpPacket = sctpPackets.take();
                            System.out.println("SCTP reader " + getID() + " received an incoming sctp packet " +
                                    " (size " + sctpPacket.getPacket().getBuffer().limit() + ")");
                            sctpManager.handleIncomingSctp(sctpPacket);
                        } catch (InterruptedException e) {
                            System.out.println("Interrupted while trying to receive sctp packet");
                        }
                    }
                }, "Incoming SCTP reader").start();
            }
        });

        ((IceDtlsTransportManager)transportManager).setTransceiver(this.transceiver);
    }

    protected void handleIncomingRtp(List<PacketInfo> packetInfos)
    {
        // For now, just write every packet to every channel other than ourselves
        packetInfos.forEach(pktInfo -> {
//            pktInfo.getMetaData().forEach((name, value) -> {
//                if (name instanceof String && ((String) name).contains("TimeTag"))
//                {
//                    Long timestamp = (Long)value;
//                    RtpPacket packet = (RtpPacket)pktInfo.getPacket();
//                    logger.info("Packet " + packet.getHeader().getSsrc() + " " +
//                            packet.getHeader().getSequenceNumber() + " took " +
//                            (System.currentTimeMillis() - timestamp) + " ms to get through the" +
//                            " receive pipeline");
//                }
//
//            });
            getConference().getEndpointsFast().forEach(endpoint -> {
                if (endpoint == this)
                {
                    return;
                }
                PacketInfo pktInfoCopy = pktInfo.clone();
                //TODO: add 'wants' check and we'll need to go through the videochannel(?)
                endpoint.transceiver.sendRtp(Collections.singletonList(pktInfoCopy));

            });
        });
    }

    public void sendRtp(List<PacketInfo> packets)
    {
        // By default just add it to the sender's queue
        transceiver.sendRtp(packets);
    }

    protected void handleIncomingRtcp(List<PacketInfo> packetInfos)
    {
        // We don't need to copy RTCP packets for each dest like we do with RTP because each one
        // will only have a single destination
        getConference().getEndpoints().forEach(endpoint -> {
            packetInfos.forEach(packetInfo -> {
                RtcpFbPacket rtcpPacket = (RtcpFbPacket) packetInfo.getPacket();
                if (endpoint.receivesSsrc(rtcpPacket.getMediaSourceSsrc())) {
                    endpoint.transceiver.sendRtcp(Collections.singletonList(rtcpPacket));
                }
            });
        });
    }

    public boolean receivesSsrc(long ssrc) {
        return transceiver.receivesSsrc(ssrc);
    }

    public void addSsrcAssociation(long primarySsrc, long secondarySsrc, String semantics)
    {
        transceiver.addSsrcAssociation(primarySsrc, secondarySsrc, semantics);
    }

    /**
     * @return the {@link AbstractEndpointMessageTransport} associated with
     * this endpoint.
     */
    public AbstractEndpointMessageTransport getMessageTransport()
    {
        return null;
    }

    public void addChannel(ColibriShim.Channel channel)
    {
        synchronized (channelShims)
        {
            channelShims.add(new WeakReference<>(channel));
        }
        System.out.println("Endpoint added channel shim, now have " + channelShims.size() +
                " channel shims");
    }

    public void removeChannel(ColibriShim.Channel channel)
    {
        synchronized (channelShims)
        {
            for (Iterator<WeakReference<ColibriShim.Channel>> i = channelShims.iterator(); i.hasNext();)
            {
                ColibriShim.Channel existingChannel = i.next().get();
                if (existingChannel != null && existingChannel.equals(channel)) {
                    i.remove();
                }
            }
            System.out.println("Endpoint removed channel shim, now have " + channelShims.size() +
                    " channel shims");
            if (channelShims.isEmpty())
            {
                expire();
            }
        }
    }

    /**
     * Adds a specific {@link RtpChannel} to the list of channels
     * associated with this {@link AbstractEndpoint}.
     *
     * @param channel the {@link RtpChannel }to add.
     * @return {@code true} if the list of {@link RtpChannel}s associated with
     * this endpoint changed as a result of the method invocation; otherwise,
     * {@code false}.
     */
    public boolean addChannel(RtpChannel channel)
    {
        Objects.requireNonNull(channel, "channel");

        // The expire state of Channel is final. Adding an expired Channel to
        // an Endpoint is a no-op.
        if (channel.isExpired())
        {
            return false;
        }

        boolean added = false;
        boolean removed = false;

        synchronized (channels)
        {
            boolean add = true;

            for (Iterator<WeakReference<RtpChannel>> i = channels.iterator();
                 i.hasNext();)
            {
                RtpChannel c = i.next().get();

                if (c == null)
                {
                    i.remove();
                    removed = true;
                }
                else if (c.equals(channel))
                {
                    add = false;
                }
                else if (c.isExpired())
                {
                    i.remove();
                    removed = true;
                }
            }
            if (add)
            {
                channels.add(new WeakReference<>(channel));
                added = true;
            }
        }

        if (removed)
        {
            maybeExpire();
        }

        return added;
    }

    /**
     * Gets the number of {@link RtpChannel}s of this endpoint which,
     * optionally, are of a specific {@link MediaType}.
     *
     * @param mediaType the {@link MediaType} of the {@link RtpChannel}s to
     * count or {@code null} to count all {@link RtpChannel}s.
     * @return the number of {@link RtpChannel}s of this endpoint which,
     * , optionally, are of the specified {@link MediaType}.
     */
    int getChannelCount(MediaType mediaType)
    {
        return getChannels(mediaType).size();
    }

    /**
     * @return a list with all {@link RtpChannel}s of this {@link Endpoint}.
     */
    public List<RtpChannel> getChannels()
    {
        return getChannels(null);
    }

    /**
     * Gets a list with the {@link RtpChannel}s of this {@link Endpoint} with a
     * particular {@link MediaType} (or all of them, if {@code mediaType} is
     * {@code null}).
     *
     * @param mediaType the {@link MediaType} to match. If {@code null}, all
     * channels of this endpoint will be returned.
     * @return a <tt>List</tt> with the channels of this <tt>Endpoint</tt> with
     * a particular <tt>MediaType</tt>.
     */
    public List<RtpChannel> getChannels(MediaType mediaType)
    {
        boolean removed = false;
        List<RtpChannel> channels = new LinkedList<>();

        synchronized (this.channels)
        {
            for (Iterator<WeakReference<RtpChannel>> i
                        = this.channels.iterator();
                    i.hasNext();)
            {
                RtpChannel c = i.next().get();

                if ((c == null) || c.isExpired())
                {
                    i.remove();
                    removed = true;
                }
                else if ((mediaType == null)
                        || mediaType.equals(c.getContent().getMediaType()))
                {
                    channels.add(c);
                }
            }
        }

        if (removed)
        {
            maybeExpire();
        }

        return channels;
    }

    /**
     * Returns the display name of this <tt>Endpoint</tt>.
     *
     * @return the display name of this <tt>Endpoint</tt>.
     */
    public String getDisplayName()
    {
        return displayName;
    }

    /**
     * Returns the stats Id of this <tt>Endpoint</tt>.
     *
     * @return the stats Id of this <tt>Endpoint</tt>.
     */
    public String getStatsId()
    {
        return statsId;
    }

    /**
     * Gets the (unique) identifier/ID of this instance.
     *
     * @return the (unique) identifier/ID of this instance
     */
    public final String getID()
    {
        return id;
    }

    /**
     * Gets the <tt>Conference</tt> to which this <tt>Endpoint</tt> belongs.
     *
     * @return the <tt>Conference</tt> to which this <tt>Endpoint</tt> belongs.
     */
    public Conference getConference()
    {
        return conference;
    }

    /**
     * Checks whether or not this <tt>Endpoint</tt> is considered "expired"
     * ({@link #expire()} method has been called).
     *
     * @return <tt>true</tt> if this instance is "expired" or <tt>false</tt>
     * otherwise.
     */
    public boolean isExpired()
    {
        return expired;
    }

    /**
     * Removes a specific <tt>Channel</tt> from the list of <tt>Channel</tt>s
     * associated with this <tt>Endpoint</tt>.
     *
     * @param channel the <tt>Channel</tt> to remove from the list of
     * <tt>Channel</tt>s associated with this <tt>Endpoint</tt>
     * @return <tt>true</tt> if the list of <tt>Channel</tt>s associated with
     * this <tt>Endpoint</tt> changed as a result of the method invocation;
     * otherwise, <tt>false</tt>
     */
    public boolean removeChannel(RtpChannel channel)
    {
        if (channel == null)
        {
            return false;
        }

        boolean removed;

        synchronized (channels)
        {
            removed = channels.removeIf(w -> {
                Channel c = w.get();
                return c == null || c.equals(channel) || c.isExpired();
            });
        }

        if (removed)
        {
            maybeExpire();
        }

        return removed;
    }

    /**
     * Sets the display name of this <tt>Endpoint</tt>.
     *
     * @param displayName the display name to set on this <tt>Endpoint</tt>.
     */
    public void setDisplayName(String displayName)
    {
        this.displayName = displayName;
    }

    /**
     * Sets the stats Id of this <tt>Endpoint</tt>.
     *
     * @param value the stats Id value to set on this <tt>Endpoint</tt>.
     */
    public void setStatsId(String value)
    {
        this.statsId = value;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        return getClass().getName() + " " + getID();
    }

    /**
     * Expires this {@link AbstractEndpoint}.
     */
    public void expire()
    {
        System.out.println("Endpoint expiring");
        this.expired = true;
        this.transceiver.stop();
        receiverExecutor.shutdown();
        senderExecutor.shutdown();
        getConference().getTransportManager(this.id).close();
        getConference().endpointExpired(this);
    }

    /**
     * @return a string which identifies this {@link Endpoint} for the
     * purposes of logging. The string is a comma-separated list of "key=value"
     * pairs.
     */
    public String getLoggingId()
    {
        return loggingId;
    }

    /**
     * Expires this {@link Endpoint} if it has no channels and no SCTP
     * connection.
     */
    protected void maybeExpire()
    {}

    /**
     * @return the {@link Set} of selected endpoints, represented as a set of
     * endpoint IDs.
     */
    public Set<String> getSelectedEndpoints()
    {
        return Collections.EMPTY_SET;
    }

    /**
     * @return the {@link Set} of pinned endpoints, represented as a set of
     * endpoint IDs.
     */
    public Set<String> getPinnedEndpoints()
    {
        return Collections.EMPTY_SET;
    }

    /**
     * Gets an array that contains all the {@link MediaStreamTrackDesc} of the
     * specified media type associated with this {@link Endpoint}.
     *
     * @param mediaType the media type of the {@link MediaStreamTrackDesc} to
     * get.
     * @return an array that contains all the {@link MediaStreamTrackDesc} of
     * the specified media type associated with this {@link Endpoint}, or null.
     */
    public MediaStreamTrackDesc[] getMediaStreamTracks(MediaType mediaType)
    {
        return
            getAllMediaStreamTracks(mediaType)
                .toArray(new MediaStreamTrackDesc[0]);
    }

    /**
     * @return all {@link MediaStreamTrackDesc} of all channels of type
     * {@code mediaType} associated with this endpoint. Note that this may
     * include {@link MediaStreamTrackDesc}s which do not belong to this
     * endpoint.
     * @param mediaType the media type of the {@link MediaStreamTrackDesc} to
     * get.
     */
    protected List<MediaStreamTrackDesc> getAllMediaStreamTracks(
        MediaType mediaType)
    {
        List<RtpChannel> channels = getChannels(mediaType);

        if (channels == null || channels.isEmpty())
        {
            return Collections.EMPTY_LIST;
        }

        List<MediaStreamTrackDesc> allTracks = new LinkedList<>();
        channels.stream()
            .map(channel -> channel.getStream().getMediaStreamTrackReceiver())
            .filter(Objects::nonNull)
            .forEach(
                trackReceiver -> allTracks.addAll(
                    Arrays.asList(trackReceiver.getMediaStreamTracks())));
        return allTracks;
    }


    /**
     * Sends a specific {@link String} {@code msg} to the remote end of this
     * endpoint.
     *
     * @param msg message text to send.
     */
    public abstract void sendMessage(String msg)
        throws IOException;

    /**
     * Notify this endpoint that another endpoint has set it
     * as a 'selected' endpoint, meaning its HD stream has another
     * consumer.
     */
    public void incrementSelectedCount()
    {
        // No-op
    }

    /**
     * Notify this endpoint that another endpoint has stopped consuming
     * its HD stream.
     */
    public void decrementSelectedCount()
    {
        // No-op
    }
}
