/*
 * Copyright 2015-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.fix_gateway.engine.framer;

import org.agrona.ErrorHandler;
import org.agrona.LangUtil;
import org.agrona.collections.LongHashSet;
import org.agrona.concurrent.AtomicBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.verification.VerificationMode;
import uk.co.real_logic.fix_gateway.engine.FixEngine;
import uk.co.real_logic.fix_gateway.engine.logger.SequenceNumberIndexReader;
import uk.co.real_logic.fix_gateway.messages.*;
import uk.co.real_logic.fix_gateway.protocol.GatewayPublication;
import uk.co.real_logic.fix_gateway.session.CompositeKey;
import uk.co.real_logic.fix_gateway.session.Session;
import uk.co.real_logic.fix_gateway.session.SessionIdStrategy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.function.ToIntFunction;

import static io.aeron.Publication.BACK_PRESSURED;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.fix_gateway.dictionary.ExampleDictionary.TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER_MESSAGE_BYTES;
import static uk.co.real_logic.fix_gateway.messages.DisconnectReason.REMOTE_DISCONNECT;
import static uk.co.real_logic.fix_gateway.messages.MessageStatus.*;
import static uk.co.real_logic.fix_gateway.messages.SequenceNumberType.DETERMINE_AT_LOGON;
import static uk.co.real_logic.fix_gateway.session.Session.UNKNOWN;
import static uk.co.real_logic.fix_gateway.util.TestMessages.*;

public class ReceiverEndPointTest
{
    private static final int MESSAGE_TYPE = 'D';
    private static final long CONNECTION_ID = 20L;
    private static final long SESSION_ID = 4L;
    private static final int LIBRARY_ID = FixEngine.ENGINE_LIBRARY_ID;
    private static final long POSITION = 1024L;
    private static final int BUFFER_SIZE = 16 * 1024;
    private static final int SEQUENCE_INDEX = 0;

    private TcpChannel mockChannel = mock(TcpChannel.class);
    private GatewayPublication libraryPublication = mock(GatewayPublication.class);
    private GatewayPublication clusterablePublication = mock(GatewayPublication.class);
    private SessionContexts mockSessionContexts = mock(SessionContexts.class);
    private AtomicCounter messagesRead = mock(AtomicCounter.class);
    private ErrorHandler errorHandler = mock(ErrorHandler.class);
    private SequenceNumberIndexReader sentSequenceNumbers = mock(SequenceNumberIndexReader.class);
    private SequenceNumberIndexReader receivedSequenceNumbers = mock(SequenceNumberIndexReader.class);
    private Framer framer = mock(Framer.class);
    private GatewaySession gatewaySession = mock(GatewaySession.class);
    private Session session = mock(Session.class);
    private final LongHashSet replicatedConnectionIds = new LongHashSet();
    private final AuthenticationResult authenticationResult = AuthenticationResult.authenticatedSession(gatewaySession,
        1,
        1);
    private GatewaySessions mockGatewaySessions = mock(GatewaySessions.class);
    private CompositeKey sessionKey = SessionIdStrategy.senderAndTarget()
                                                       .onInitiateLogon("ACCEPTOR",
                                                           "", "",
                                                           "INIATOR", "", "");

    private ReceiverEndPoint endPoint = new ReceiverEndPoint(
        mockChannel, BUFFER_SIZE, libraryPublication, clusterablePublication,
        CONNECTION_ID, UNKNOWN, SEQUENCE_INDEX, mockSessionContexts,
        sentSequenceNumbers, receivedSequenceNumbers, messagesRead, framer, errorHandler, LIBRARY_ID,
        DETERMINE_AT_LOGON, ConnectionType.ACCEPTOR, replicatedConnectionIds, mockGatewaySessions);

    @Before
    public void setUp()
    {
        endPoint.gatewaySession(gatewaySession);
        when(gatewaySession.session()).thenReturn(session);
        when(gatewaySession.sessionKey()).thenReturn(sessionKey);
        when(gatewaySession.sessionId()).thenReturn(SESSION_ID);
        when(session.state()).thenReturn(SessionState.CONNECTED);
        when(mockGatewaySessions.authenticateAndInitiate(any(),
            anyLong(),
            any(),
            any(),
            eq(gatewaySession))).thenReturn(authenticationResult);

        doAnswer(
            (inv) ->
            {
                ((Continuation)inv.getArguments()[0]).attemptToAction();
                return null;
            }).when(framer).schedule(any(Continuation.class));
    }

    @After
    public void tearDown()
    {
        verifyNoMoreInteractions(clusterablePublication);
        assertThat(replicatedConnectionIds, hasSize(0));
    }

    @Test
    public void shouldNotifyDuplicateSession()
    {
        givenADuplicateSession();

        theEndpointReceivesACompleteMessage();

        pollsData(MSG_LEN);

        verifyDuplicateSession(times(1));
    }

    @Test
    public void shouldFrameValidFixMessage()
    {
        theEndpointReceivesACompleteMessage();

        pollsData(2 * MSG_LEN);

        savesAFramedMessage();

        sessionReceivesOneMessage();
    }

    @Test
    public void shouldFrameValidFixMessageWhenBackpressured()
    {
        firstSaveAttemptIsBackPressured();

        theEndpointReceivesACompleteMessage();
        pollsData(MSG_LEN);

        theEndpointReceivesNothing();
        pollsData(MSG_LEN);

        savesFramedMessages(2, OK, MSG_LEN);

        sessionReceivesOneMessage();
    }

    @Test
    public void shouldIgnoreGarbledMessages() throws IOException
    {
        final int length = GARBLED_MESSAGE.length;
        theEndpointReceives(GARBLED_MESSAGE, 0, length);

        endPoint.pollForData();

        savesInvalidMessage(length, times(1));
        verifyNoError();
    }

    @Test
    public void shouldOnlyFrameCompleteFixMessage()
    {
        theEndpointReceivesAnIncompleteMessage();

        endPoint.pollForData();

        nothingMoreSaved();
    }

    @Test
    public void shouldFrameSplitFixMessage()
    {
        theEndpointReceivesAnIncompleteMessage();
        endPoint.pollForData();

        theEndpointReceivesTheRestOfTheMessage();
        endPoint.pollForData();

        savesAFramedMessage();

        sessionReceivesOneMessage();
    }

    @Test
    public void shouldFrameTwoCompleteFixMessagesInOnePacket()
    {
        theEndpointReceivesTwoCompleteMessages();

        endPoint.pollForData();

        savesTwoFramedMessages(1);

        sessionReceivesTwoMessages();
    }

    @Test
    public void shouldFrameOneCompleteMessageWhenTheSecondMessageIsIncomplete()
    {
        theEndpointReceivesACompleteAndAnIncompleteMessage();

        endPoint.pollForData();

        savesAFramedMessage();

        sessionReceivesOneMessage();
    }

    @Test
    public void shouldFrameSecondSplitMessage()
    {
        theEndpointReceivesACompleteAndAnIncompleteMessage();
        endPoint.pollForData();

        theEndpointReceivesTheRestOfTheMessage();
        endPoint.pollForData();

        savesFramedMessages(2, OK, MSG_LEN);

        sessionReceivesTwoMessageAtBufferStart();
    }

    @Test
    public void aClosedSocketSavesItsDisconnect() throws IOException
    {
        theChannelIsClosedByException();

        endPoint.pollForData();

        verify(mockSessionContexts).onDisconnect(anyLong());
        assertSavesDisconnect();
    }

    @Test
    public void anUnreadableSocketDisconnectsItsSession() throws IOException
    {
        theChannelIsClosed();

        endPoint.pollForData();

        assertSavesDisconnect();
    }

    @Test
    public void invalidChecksumMessageRecorded() throws IOException
    {
        theEndpointReceivesAMessageWithInvalidChecksum();

        endPoint.pollForData();

        // Test for bug where invalid message re-saved.
        pollWithNoData();

        savesInvalidChecksumMessage(times(1));
        nothingMoreSaved();
    }

    @Test
    public void invalidChecksumMessageRecordedWhenBackpressured() throws IOException
    {
        firstSaveAttemptIsBackPressured();

        theEndpointReceivesAMessageWithInvalidChecksum();

        endPoint.pollForData();

        endPoint.pollForData();

        savesInvalidChecksumMessage(times(2));
    }

    @Test
    public void fieldOutOfOrderMessageRecordedOnce() throws IOException
    {
        final int length = theEndpointReceivesAnOutOfOrderMessage();

        // Test for bug where invalid message re-saved.
        pollWithNoData();

        savesInvalidMessage(length, times(1));
        nothingMoreSaved();
        verifyNoError();
    }

    @Test
    public void fieldOutOfOrderMessageRecordedWhenBackpressured() throws IOException
    {
        firstSaveAttemptIsBackPressured();

        final int length = theEndpointReceivesAnOutOfOrderMessage();

        pollWithNoData();

        savesInvalidMessage(length, times(2));
        verifyNoError();
    }

    @Test
    public void shouldFrameSplitFixMessageWhenBackpressured()
    {
        firstSaveAttemptIsBackPressured();

        theEndpointReceivesAnIncompleteMessage();
        endPoint.pollForData();

        theEndpointReceivesTheRestOfTheMessage();
        endPoint.pollForData();

        pollWithNoData();

        savesFramedMessages(2, OK, MSG_LEN);

        sessionReceivesOneMessage();
    }

    @Test
    public void shouldFrameTwoCompleteFixMessagesInOnePacketWhenBackpressured()
    {
        firstSaveAttemptIsBackPressured();

        theEndpointReceivesTwoCompleteMessages();
        endPoint.pollForData();

        pollWithNoData();

        savesTwoFramedMessages(2);

        sessionReceivesTwoMessages();
    }

    @Test
    public void shouldFrameOneCompleteMessageWhenTheSecondMessageIsIncompleteWhenBackpressured()
    {
        firstSaveAttemptIsBackPressured();

        theEndpointReceivesACompleteAndAnIncompleteMessage();
        endPoint.pollForData();

        pollWithNoData();

        savesFramedMessages(2, OK, MSG_LEN);
    }

    @Test
    public void shouldFrameSecondSplitMessageWhenBackpressured()
    {
        firstSaveAttemptIsBackPressured();

        theEndpointReceivesACompleteAndAnIncompleteMessage();
        endPoint.pollForData();

        theEndpointReceivesTheRestOfTheMessage();
        endPoint.pollForData();

        savesFramedMessages(2, OK, MSG_LEN);

        sessionReceivesTwoMessages();
    }

    private void firstSaveAttemptIsBackPressured()
    {
        when(libraryPublication
            .saveMessage(anyBuffer(), anyInt(), anyInt(), anyInt(), anyInt(), anyLong(), anyInt(), anyLong(), any()))
            .thenReturn(BACK_PRESSURED, POSITION);
    }

    private AtomicBuffer anyBuffer()
    {
        return any(AtomicBuffer.class);
    }

    private void pollsData(final int bytesReadAndSaved)
    {
        assertEquals(bytesReadAndSaved, endPoint.pollForData());
    }

    private void verifyNoError()
    {
        verify(errorHandler, never()).onError(any());
        assertFalse("Endpoint Disconnected", endPoint.hasDisconnected());
    }

    private void savesInvalidMessage(final int length, final VerificationMode mode)
    {
        verify(libraryPublication, mode)
            .saveMessage(
                anyBuffer(), eq(0), eq(length), eq(LIBRARY_ID),
                anyInt(), anyLong(), anyInt(), eq(CONNECTION_ID),
                eq(INVALID));
    }

    private void assertSavesDisconnect()
    {
        verify(libraryPublication).saveDisconnect(LIBRARY_ID, CONNECTION_ID, REMOTE_DISCONNECT);
    }

    private void theChannelIsClosed() throws IOException
    {
        when(mockChannel.read(any(ByteBuffer.class))).thenReturn(-1);
    }

    private void theChannelIsClosedByException() throws IOException
    {
        doThrow(new ClosedChannelException()).when(mockChannel).read(any(ByteBuffer.class));
    }

    private void savesAFramedMessage()
    {
        savesFramedMessages(1, OK, MSG_LEN);
    }

    private void savesFramedMessages(
        final int numberOfMessages,
        final MessageStatus status,
        final int msgLen)
    {
        verify(libraryPublication, times(numberOfMessages))
            .saveMessage(
                anyBuffer(), eq(0), eq(msgLen), eq(LIBRARY_ID),
                eq(MESSAGE_TYPE), eq(SESSION_ID), eq(SEQUENCE_INDEX), eq(CONNECTION_ID),
                eq(status));
    }

    private void savesTwoFramedMessages(final int firstMessageSaveAttempts)
    {
        final InOrder inOrder = Mockito.inOrder(libraryPublication);
        inOrder.verify(libraryPublication, times(firstMessageSaveAttempts))
               .saveMessage(
                   anyBuffer(),
                   eq(0),
                   eq(MSG_LEN),
                   eq(LIBRARY_ID),
                   eq(MESSAGE_TYPE),
                   eq(SESSION_ID),
                   eq(SEQUENCE_INDEX),
                   eq(CONNECTION_ID),
                   eq(OK));
        inOrder.verify(libraryPublication, times(1))
               .saveMessage(
                   anyBuffer(),
                   eq(MSG_LEN),
                   eq(MSG_LEN),
                   eq(LIBRARY_ID),
                   eq(MESSAGE_TYPE),
                   eq(SESSION_ID),
                   eq(SEQUENCE_INDEX),
                   eq(CONNECTION_ID),
                   eq(OK));
        inOrder.verifyNoMoreInteractions();
    }

    private void nothingMoreSaved()
    {
        verifyNoMoreInteractions(libraryPublication);
    }

    private void theEndpointReceivesACompleteMessage()
    {
        theEndpointReceives(EG_MESSAGE, 0, MSG_LEN);
    }

    private void theEndpointReceivesTwoCompleteMessages()
    {
        theEndpointReceivesTwoMessages(0, MSG_LEN);
    }

    private void theEndpointReceivesAnIncompleteMessage()
    {
        theEndpointReceives(EG_MESSAGE, 0, MSG_LEN - 8);
    }

    private void theEndpointReceivesTheRestOfTheMessage()
    {
        theEndpointReceives(EG_MESSAGE, MSG_LEN - 8, 8);
    }

    private void theEndpointReceivesACompleteAndAnIncompleteMessage()
    {
        theEndpointReceivesTwoMessages(0, MSG_LEN - 8);
    }

    private void theEndpointReceives(final byte[] data, final int offset, final int length)
    {
        endpointBufferUpdatedWith(
            (buffer) ->
            {
                buffer.put(data, offset, length);
                return length;
            });
    }

    private void theEndpointReceivesNothing()
    {
        endpointBufferUpdatedWith(buffer -> 0);
    }

    private void theEndpointReceivesTwoMessages(final int secondOffset, final int secondLength)
    {
        endpointBufferUpdatedWith(
            (buffer) ->
            {
                buffer.put(EG_MESSAGE)
                      .put(EG_MESSAGE, secondOffset, secondLength);
                return MSG_LEN + secondLength;
            });
    }

    private int theEndpointReceivesAnOutOfOrderMessage()
    {
        final int length = TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER_MESSAGE_BYTES.length;

        theEndpointReceives(TAG_SPECIFIED_OUT_OF_REQUIRED_ORDER_MESSAGE_BYTES, 0, length);
        endPoint.pollForData();
        return length;
    }

    private void endpointBufferUpdatedWith(final ToIntFunction<ByteBuffer> bufferUpdater)
    {
        try
        {
            doAnswer(
                (invocation) ->
                {
                    final ByteBuffer buffer = (ByteBuffer)invocation.getArguments()[0];
                    return bufferUpdater.applyAsInt(buffer);
                }).when(mockChannel).read(any(ByteBuffer.class));
        }
        catch (final IOException ex)
        {
            // Should never happen, test in error
            LangUtil.rethrowUnchecked(ex);
        }
    }

    private void theEndpointReceivesAMessageWithInvalidChecksum()
    {
        theEndpointReceives(INVALID_CHECKSUM_MSG, 0, INVALID_CHECKSUM_LEN);
    }

    private void savesInvalidChecksumMessage(final VerificationMode mode)
    {
        verify(libraryPublication, mode)
            .saveMessage(
                anyBuffer(),
                eq(0),
                eq(INVALID_CHECKSUM_LEN),
                eq(LIBRARY_ID),
                eq(MESSAGE_TYPE),
                anyLong(),
                anyInt(),
                eq(CONNECTION_ID),
                eq(INVALID_CHECKSUM));
    }

    private void sessionReceivesOneMessage()
    {
        sessionReceivesMessageAt(0, MSG_LEN, times(1));
        sessionReceivedCountIs(1);
    }

    private void sessionReceivesTwoMessages()
    {
        sessionReceivesMessageAt(0, MSG_LEN, times(1));
        sessionReceivesMessageAt(MSG_LEN, MSG_LEN, times(1));
        sessionReceivedCountIs(2);
    }

    private void sessionReceivesTwoMessageAtBufferStart()
    {
        sessionReceivesMessageAt(0, MSG_LEN, times(2));
        sessionReceivedCountIs(2);
    }

    private void sessionReceivedCountIs(final int numberOfMessages)
    {
        verify(gatewaySession, times(numberOfMessages))
            .onMessage(any(), anyInt(), anyInt(), anyInt(), anyLong());
    }

    private void sessionReceivesMessageAt(final int offset, final int length, final VerificationMode mode)
    {
        verify(gatewaySession, mode)
            .onMessage(any(), eq(offset), eq(length), eq(MESSAGE_TYPE), eq(SESSION_ID));
    }

    private void pollWithNoData()
    {
        theEndpointReceivesNothing();
        endPoint.pollForData();
    }

    private void verifyDuplicateSession(final VerificationMode times)
    {
        verify(libraryPublication, times).saveDisconnect(anyInt(), anyLong(), eq(DisconnectReason.DUPLICATE_SESSION));
    }

    private void givenADuplicateSession()
    {
        when(mockGatewaySessions.authenticateAndInitiate(any(), anyLong(), any(), any(), any())).thenReturn(
            AuthenticationResult.DUPLICATE_SESSION);
    }

}
