package org.yamcs.simulation.simulator;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.cfdp.DataFile;
import org.yamcs.cfdp.DataFileSegment;
import org.yamcs.cfdp.FileDirective;
import org.yamcs.cfdp.pdu.AckPacket;
import org.yamcs.cfdp.pdu.CfdpHeader;
import org.yamcs.cfdp.pdu.CfdpPacket;
import org.yamcs.cfdp.pdu.ConditionCode;
import org.yamcs.cfdp.pdu.EofPacket;
import org.yamcs.cfdp.pdu.FileDataPacket;
import org.yamcs.cfdp.pdu.FileDirectiveCode;
import org.yamcs.cfdp.pdu.FileStoreResponse;
import org.yamcs.cfdp.pdu.FinishedPacket;
import org.yamcs.cfdp.pdu.MetadataPacket;
import org.yamcs.cfdp.pdu.NakPacket;
import org.yamcs.cfdp.pdu.SegmentRequest;
import org.yamcs.cfdp.pdu.AckPacket.FileDirectiveSubtypeCode;
import org.yamcs.cfdp.pdu.AckPacket.TransactionStatus;
import org.yamcs.cfdp.pdu.FinishedPacket.FileStatus;

/**
 * Receives CFDP files.
 * <p>
 * It doesn't store them but just print a message at the end of the reception.
 * 
 * @author nm
 *
 */
public class CfdpReceiver {
    private static final Logger log = LoggerFactory.getLogger(CfdpReceiver.class);
    final Simulator simulator;

    private DataFile cfdpDataFile = null;
    List<SegmentRequest> missingSegments;

    public CfdpReceiver(Simulator simulator) {
        this.simulator = simulator;
    }

    protected void processCfdp(ByteBuffer buffer) {
        CfdpPacket packet = CfdpPacket.getCFDPPacket(buffer);
        if (packet.getHeader().isFileDirective()) {
            switch (((FileDirective) packet).getFileDirectiveCode()) {
            case EOF:
                // 1 in 2 chance that we did not receive the EOF packet
                if (Math.random() > 0.5) {
                    break;
                }

                log.info("EOF CFDP packet received, sending back ACK (EOF) packet");
                EofPacket p = (EofPacket) packet;

                CfdpHeader header = new CfdpHeader(
                        true,
                        true,
                        false,
                        false,
                        packet.getHeader().getEntityIdLength(),
                        packet.getHeader().getSequenceNumberLength(),
                        packet.getHeader().getSourceId(),
                        packet.getHeader().getDestinationId(),
                        packet.getHeader().getSequenceNumber());
                AckPacket EofAck = new AckPacket(
                        FileDirectiveCode.EOF,
                        FileDirectiveSubtypeCode.FinishedByWaypointOrOther,
                        ConditionCode.NoError,
                        TransactionStatus.Active,
                        header);
                transmitCfdp(EofAck);

                log.info("ACK (EOF) sent, delaying a bit and sending Finished packet");
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                // checking the file completeness;
                missingSegments = cfdpDataFile.getMissingChunks();
                if (missingSegments.isEmpty()) {
                    log.info("File complete, sending back FinishedPacket");
                    header = new CfdpHeader(
                            true, // file directive
                            true, // towards sender
                            false, // not acknowledged
                            false, // no CRC
                            packet.getHeader().getEntityIdLength(),
                            packet.getHeader().getSequenceNumberLength(),
                            packet.getHeader().getSourceId(),
                            packet.getHeader().getDestinationId(),
                            packet.getHeader().getSequenceNumber());

                    FinishedPacket finished = new FinishedPacket(
                            ConditionCode.NoError,
                            true, // generated by end system
                            false, // data complete
                            FileStatus.SuccessfulRetention,
                            new ArrayList<FileStoreResponse>(),
                            null,
                            header);

                    transmitCfdp(finished);
                } else {
                    header = new CfdpHeader(
                            true, // file directive
                            true, // towards sender
                            false, // not acknowledged
                            false, // no CRC
                            packet.getHeader().getEntityIdLength(),
                            packet.getHeader().getSequenceNumberLength(),
                            packet.getHeader().getSourceId(),
                            packet.getHeader().getDestinationId(),
                            packet.getHeader().getSequenceNumber());

                    NakPacket nak = new NakPacket(
                            missingSegments.get(0).getSegmentStart(),
                            missingSegments.get(missingSegments.size() - 1).getSegmentEnd(),
                            missingSegments,
                            header);
                    transmitCfdp(nak);
                    log.info("File not complete (" + missingSegments.size() + "segments missing), NAK sent back");

                }
                break;
            case Finished:
                log.info("Finished CFDP packet received");
                break;
            case ACK:
                log.info("ACK CFDP packet received");
                break;
            case Metadata:
                log.info("Metadata CFDP packet received");
                MetadataPacket metadata = (MetadataPacket) packet;
                long packetLength = metadata.getPacketLength();
                cfdpDataFile = new DataFile(packetLength);
                break;
            case NAK:
                log.info("NAK CFDP packet received");
                break;
            case Prompt:
                log.info("Prompt CFDP packet received");
                break;
            case KeepAlive:
                log.info("KeepAlive CFDP packet received");
                break;
            default:
                log.error("CFDP packet of unknown type received");
                break;
            }
        } else {
            FileDataPacket fdp = (FileDataPacket) packet;
            if (missingSegments == null || missingSegments.isEmpty()) {
                // we're not in "resending mode"
                // 1 in 5 chance to 'loose' the packet
                if (Math.random() > 0.8) {
                    log.info("'loosing' a FileDataPacket");
                } else {
                    cfdpDataFile.addSegment(new DataFileSegment(fdp.getOffset(), fdp.getData()));
                    log.info("file data received: " + new String(fdp.getData()).toString());
                }
            } else {
                // we're resending
                cfdpDataFile.addSegment(new DataFileSegment(fdp.getOffset(), fdp.getData()));
                missingSegments.remove(new SegmentRequest(fdp.getOffset(), fdp.getOffset() + fdp.getData().length));
                log.info("RESENT file data received: " + new String(fdp.getData()).toString());
                if (missingSegments.isEmpty()) {
                    CfdpHeader header = new CfdpHeader(
                            true, // file directive
                            true, // towards sender
                            false, // not acknowledged
                            false, // no CRC
                            packet.getHeader().getEntityIdLength(),
                            packet.getHeader().getSequenceNumberLength(),
                            packet.getHeader().getSourceId(),
                            packet.getHeader().getDestinationId(),
                            packet.getHeader().getSequenceNumber());

                    FinishedPacket finished = new FinishedPacket(
                            ConditionCode.NoError,
                            true, // generated by end system
                            false, // data complete
                            FileStatus.SuccessfulRetention,
                            new ArrayList<FileStoreResponse>(),
                            null,
                            header);

                    transmitCfdp(finished);
                }
            }
        }
    }

    protected void transmitCfdp(CfdpPacket packet) {
        CfdpHeader header = packet.getHeader();

        int length = 16 + header.getLength() + header.getDataLength();
        ByteBuffer buffer = ByteBuffer.allocate(length);
        buffer.putShort((short) 0x17FD);
        buffer.putShort(4, (short) (length - 7));
        buffer.position(16);
        packet.writeToBuffer(buffer.slice());
        
        simulator.transmitRealtimeTM(new CCSDSPacket(buffer));
    }
}
