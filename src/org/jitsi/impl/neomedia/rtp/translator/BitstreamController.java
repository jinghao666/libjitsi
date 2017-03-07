/*
 * Copyright @ 2015 Atlassian Pty Ltd
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
package org.jitsi.impl.neomedia.rtp.translator;

import net.sf.fmj.media.rtp.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.function.*;

import java.lang.ref.*;
import java.util.*;

/**
 * @author George Politis
 */
public class BitstreamController
{
    /**
     * The available subjective quality indexes that this RTP stream offers.
     */
    private final int[] availableIdx;

    /**
     * The sequence number offset that this bitstream started.
     */
    private final int seqNumOff;

    /**
     * The timestamp offset that this bitstream started.
     */
    private final long tsOff;

    /**
     * The SSRC of the TL0 of the RTP stream that is currently being
     * forwarded.
     */
    private final long tl0SSRC;

    /**
     * The target subjective quality index for this instance. This instance
     * switches between the available RTP streams and sub-encodings until it
     * reaches this target. -1 effectively means that the stream is suspended.
     */
    private int targetIdx;

    /**
     * The optimal subjective quality index for this instance.
     */
    private int optimalIdx;

    /**
     * The current subjective quality index for this instance. If this is
     * different than the target, then a switch is pending.
     */
    private int currentIdx;

    /**
     * The number of transmitted bytes.
     */
    private long transmittedBytes;

    /**
     * The number of transmitted packets.
     */
    private long transmittedPackets;

    /**
     * The independent frame that started this bitstream.
     */
    private FrameController kfFrame;

    /**
     * The max (biggest timestamp) frame that we've sent out.
     */
    private FrameController maxSentFrame;

    /**
     * At 60fps, this holds 5 seconds worth of frames.
     * At 30fps, this holds 10 seconds worth of frames.
     */
    private Map<Long, FrameController> seenFrames
        = Collections.synchronizedMap(new LRUCache<Long, FrameController>(300));

    /**
     * Ctor.
     */
    BitstreamController(
        MediaStreamTrackDesc source, int tl0Idx, int targetIdx, int optimalIdx)
    {
        this(source, -1, -1, 0, 0, tl0Idx, targetIdx, optimalIdx);
    }

    /**
     * Ctor.
     */
    BitstreamController(
        MediaStreamTrackDesc source,
        int seqNumOff, long tsOff,
        long transmittedBytes, long transmittedPackets,
        int initialIdx, int targetIdx, int optimalIdx)
    {
        this.seqNumOff = seqNumOff;
        this.tsOff = tsOff;
        this.transmittedBytes = transmittedBytes;
        this.transmittedPackets = transmittedPackets;
        this.optimalIdx = optimalIdx;
        this.targetIdx = targetIdx;
        this.currentIdx = -1;

        RTPEncodingDesc[] rtpEncodings = source.getRTPEncodings();
        if (ArrayUtils.isNullOrEmpty(rtpEncodings))
        {
            this.availableIdx = null;
            this.tl0SSRC = -1;
        }
        else
        {
            int tl0Idx = initialIdx;
            if (initialIdx > -1)
            {
                tl0Idx = rtpEncodings[tl0Idx].getBaseLayer().getIndex();
            }

            // find the available qualities in this bitstream.

            // TODO optimize if we have a single quality
            List<Integer> availableQualities = new ArrayList<>();
            for (int i = 0; i < rtpEncodings.length; i++)
            {
                if (rtpEncodings[i].requires(tl0Idx))
                {
                    availableQualities.add(i);
                }
            }

            availableIdx = new int[availableQualities.size()];
            Iterator<Integer> iterator = availableQualities.iterator();
            for (int i = 0; i < availableIdx.length; i++)
            {
                availableIdx[i] = iterator.next();
            }

            if (tl0Idx > -1)
            {
                tl0SSRC = rtpEncodings[tl0Idx].getPrimarySSRC();
            }
            else
            {
                tl0SSRC = -1;
            }
        }
    }

    /**
     *
     * @param sourceFrameDesc
     * @param buf
     * @param off
     * @param len
     * @return
     */
    public boolean accept(
        FrameDesc sourceFrameDesc, byte[] buf, int off, int len)
    {
        FrameController destFrame
            = seenFrames.get(sourceFrameDesc.getTimestamp());

        if (destFrame == null)
        {
            // An unseen frame has arrived. We forward it iff all of the
            // following conditions hold:
            //
            // 1. it's a dependency of the currentIdx (obviously).
            // 2. it's newer than max frame (we can't modify the distances of
            // past frames).
            // 2. we know the boundaries of the max frame OR if the max frame is
            // *not* a TL0 (this allows for non-TL0 to be corrupt).
            //
            // Given the above conditions, we might decide to drop a TL0 frame.
            // This can happen when the max frame is a TL0 and we don't know its
            // boundaries. Then the stream will be broken an we should ask for a
            // key frame.

            int currentIdx = this.currentIdx, targetIdx = this.targetIdx;

            if (currentIdx < 0
                && targetIdx > -1 && sourceFrameDesc.isIndependent())
            {
                // Resume a suspended stream (requires an independent frame).
                currentIdx = availableIdx[0];
                for (int i = 1; i < availableIdx.length; i++)
                {
                    if (availableIdx[i] <= targetIdx)
                    {
                        currentIdx = availableIdx[i];
                    }
                    else
                    {
                        break;
                    }
                }

                this.currentIdx = currentIdx;
            }

            if (currentIdx >= 0 && (maxSentFrame == null
                || TimeUtils.rtpDiff(sourceFrameDesc.getTimestamp(),
                    maxSentFrame.getSource().getTimestamp()) > 0))
            {
                // the stream is not suspended and we're not dealing with a late
                // frame.

                RTPEncodingDesc sourceEncodings[] = sourceFrameDesc
                    .getRTPEncoding().getMediaStreamTrack().getRTPEncodings();

                int sourceIdx = sourceFrameDesc.getRTPEncoding().getIndex();
                if (sourceEncodings[currentIdx].requires(sourceIdx))
                {
                    // the quality of the frame is a dependency of the
                    // forwarded quality.

                    // TODO ask for independent frame if we're corrupting a TL0.

                    int maxSeqNum = getMaxSeqNum();
                    SeqnumTranslation seqnumTranslation;
                    if (maxSeqNum > -1)
                    {
                        int seqNumDelta = (maxSeqNum
                            + 1 - sourceFrameDesc.getStart()) & 0xFFFF;

                        seqnumTranslation = new SeqnumTranslation(seqNumDelta);
                    }
                    else
                    {
                        seqnumTranslation = null;
                    }

                    long maxTs = getMaxTs();
                    TimestampTranslation tsTranslation;
                    if (maxTs > -1)
                    {
                        long tsDelta = (maxTs
                            + 3000 - sourceFrameDesc.getTimestamp()) & 0xFFFFFFFFL;

                        tsTranslation = new TimestampTranslation(tsDelta);
                    }
                    else
                    {
                        tsTranslation = null;
                    }

                    destFrame = new FrameController(
                        sourceFrameDesc, seqnumTranslation, tsTranslation);
                    seenFrames.put(sourceFrameDesc.getTimestamp(), destFrame);
                    maxSentFrame = destFrame;

                    if (sourceFrameDesc.isIndependent())
                    {
                        kfFrame = destFrame;
                    }
                }
                else
                {
                    destFrame = new FrameController(sourceFrameDesc, null, null);
                    seenFrames.put(sourceFrameDesc.getTimestamp(), destFrame);
                }
            }
            else
            {
                destFrame = new FrameController(sourceFrameDesc, null, null);
                seenFrames.put(sourceFrameDesc.getTimestamp(), destFrame);
            }
        }

        boolean accept
            = destFrame.accept(maxSentFrame == destFrame, buf, off, len);

        if (accept)
        {
            transmittedPackets++;
            transmittedBytes += len;
        }

        return accept;
    }

    /**
     * {@inheritDoc}
     */
    long getTransmittedBytes()
    {
        return transmittedBytes;
    }

    /**
     * {@inheritDoc}
     */
    long getTransmittedPackets()
    {
        return transmittedPackets;
    }

    /**
     * {@inheritDoc}
     */
    int getOptimalIndex()
    {
        return optimalIdx;
    }

    /**
     * {@inheritDoc}
     */
    int getCurrentIndex()
    {
        return currentIdx;
    }

    /**
     * {@inheritDoc}
     */
    int getTargetIndex()
    {
        return targetIdx;
    }

    /**
     * {@inheritDoc}
     */
    void setTargetIndex(int newTargetIdx)
    {
        this.targetIdx = newTargetIdx;
        if (newTargetIdx < 0)
        {
            currentIdx = newTargetIdx;
        }

        if (currentIdx > -1)
        {
            // Resume a suspended stream (requires an independent frame).
            currentIdx = availableIdx[0];
            for (int i = 1; i < availableIdx.length; i++)
            {
                if (availableIdx[i] <= targetIdx)
                {
                    currentIdx = availableIdx[i];
                }
                else
                {
                    break;
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    void setOptimalIndex(int newOptimalIdx)
    {
        this.optimalIdx = newOptimalIdx;
    }

    /**
     *
     * @param pktIn
     * @return
     */
    RawPacket[] rtpTransform(RawPacket pktIn)
    {
        FrameDesc source = kfFrame == null ? null : kfFrame.getSource();
        if (source == null)
        {
            return null;
        }

        long ts = pktIn.getTimestamp(),
            kfTs = source.getTimestamp();

        if (TimeUtils.rtpDiff(ts, kfTs) < 0)
        {
            // Don't forward anything older than the most recent independent
            // frame.
            return null;
        }

        FrameController destFrame = seenFrames.get(ts);
        return destFrame.rtpTransform(pktIn);
    }

    /**
     *
     * @param pktIn
     * @return
     */
    RawPacket rtcpTransform(RawPacket pktIn)
    {
        // Drop SRs from other streams.
        RTCPIterator it = new RTCPIterator(pktIn);
        while (it.hasNext())
        {
            ByteArrayBuffer baf = it.next();
            switch (RTCPHeaderUtils.getPacketType(baf))
            {
            case RTCPPacket.SR:
                if (RawPacket.getRTCPSSRC(baf) != tl0SSRC)
                {
                    continue;
                }
                // Rewrite timestamp.
                if (maxSentFrame != null
                    && maxSentFrame.getTimestampTranslation() != null)
                {
                    long srcTs = RTCPSenderInfoUtils.getTimestamp(pktIn);
                    // FIXME what if maxSentFrame == null?
                    long dstTs
                        = maxSentFrame.getTimestampTranslation().apply(srcTs);

                    if (srcTs != dstTs)
                    {
                        RTCPSenderInfoUtils.setTimestamp(pktIn, (int) dstTs);
                    }
                }

                // Rewrite packet/octet count.
                RTCPSenderInfoUtils.setOctetCount(pktIn, (int) transmittedBytes);
                RTCPSenderInfoUtils.setPacketCount(pktIn, (int) transmittedPackets);
            }
        }

        return pktIn;
    }

    /**
     * {@inheritDoc}
     */
    int getMaxSeqNum()
    {
        return maxSentFrame == null ? seqNumOff : maxSentFrame.getMaxSeqNum();
    }

    /**
     * {@inheritDoc}
     */
    long getMaxTs()
    {
        return maxSentFrame == null ? tsOff : maxSentFrame.getTs();
    }

    long getTL0SSRC()
    {
        return tl0SSRC;
    }

    class FrameController
    {
        /**
         *
         */
        private final WeakReference<FrameDesc> weakSource;

        /**
         * The sequence number translation to apply to accepted RTP packets.
         */
        private final SeqnumTranslation seqNumTranslation;

        /**
         * The RTP timestamp translation to apply to accepted RTP/RTCP packets.
         */
        private final TimestampTranslation tsTranslation;

        /**
         * A boolean that indicates whether or not the transform thread should
         * try to piggyback missed packets from the initial key frame.
         */
        private boolean maybeFixInitialIndependentFrame = true;

        /**
         * The maximum sequence number to accept. -1 means drop.
         */
        private int srcSeqNumLimit = -1;

        /**
         *
         * @param sourceFrameDesc
         * @param seqnumTranslation
         * @param tsTranslation
         */
        FrameController(FrameDesc sourceFrameDesc,
                        SeqnumTranslation seqnumTranslation,
                        TimestampTranslation tsTranslation)
        {
            this.weakSource = new WeakReference<>(sourceFrameDesc);
            this.seqNumTranslation = seqnumTranslation;
            this.tsTranslation = tsTranslation;
        }

        boolean accept(boolean expand, byte[] buf, int off, int len)
        {
            if (expand)
            {
                FrameDesc source = weakSource.get();
                assert source != null;
                srcSeqNumLimit = source.getMaxSeen();
            }
            if (srcSeqNumLimit == -1)
            {
                return false;
            }

            int seqNum = RawPacket.getSequenceNumber(buf, off, len);

            return RTPUtils.sequenceNumberDiff(seqNum, srcSeqNumLimit) <= 0;
        }

        /**
         * {@inheritDoc}
         */
        RawPacket[] rtpTransform(RawPacket pktIn)
        {
            RawPacket[] pktsOut;
            long srcSSRC = pktIn.getSSRCAsLong();

            if (maybeFixInitialIndependentFrame)
            {
                FrameDesc source = weakSource.get();
                assert source != null;

                maybeFixInitialIndependentFrame = false;

                if (source.getStart() != pktIn.getSequenceNumber())
                {
                    // Piggy back till max seen.
                    RawPacketCache inCache = source
                        .getRTPEncoding()
                        .getMediaStreamTrack()
                        .getMediaStreamTrackReceiver()
                        .getStream()
                        .getCachingTransformer()
                        .getIncomingRawPacketCache();

                    int start = source.getStart();
                    int len = RTPUtils.sequenceNumberDiff(
                        source.getMaxSeen(), start) + 1;
                    pktsOut = new RawPacket[len];
                    for (int i = 0; i < pktsOut.length; i++)
                    {
                        // Note that the ingress cache might not have the desired
                        // packet.
                        pktsOut[i] = inCache.get(srcSSRC, (start + i) & 0xFFFF);
                    }
                }
                else
                {
                    pktsOut = new RawPacket[] { pktIn };
                }
            }
            else
            {
                pktsOut = new RawPacket[]{ pktIn };
            }

            for (RawPacket pktOut : pktsOut)
            {
                // Note that the ingress cache might not have the desired packet.
                if (pktOut == null)
                {
                    continue;
                }

                if (seqNumTranslation != null)
                {
                    int srcSeqNum = pktOut.getSequenceNumber();
                    int dstSeqNum = seqNumTranslation.apply(srcSeqNum);

                    if (srcSeqNum != dstSeqNum)
                    {
                        pktOut.setSequenceNumber(dstSeqNum);
                    }
                }

                if (tsTranslation != null)
                {
                    long srcTs = pktOut.getTimestamp();
                    long dstTs = tsTranslation.apply(srcTs);

                    if (dstTs != srcTs)
                    {
                        pktOut.setTimestamp(dstTs);
                    }
                }
            }
            return pktsOut;
        }

        TimestampTranslation getTimestampTranslation()
        {
            return tsTranslation;
        }

        FrameDesc getSource()
        {
            return weakSource.get();
        }

        int getMaxSeqNum()
        {
            return seqNumTranslation == null
                ? srcSeqNumLimit : seqNumTranslation.apply(srcSeqNumLimit);
        }

        long getTs()
        {
            long ts = weakSource.get().getTimestamp();
            return tsTranslation == null ? ts : tsTranslation.apply(ts);
        }
    }
}
