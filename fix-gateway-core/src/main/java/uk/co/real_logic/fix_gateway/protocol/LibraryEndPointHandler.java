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
package uk.co.real_logic.fix_gateway.protocol;

import io.aeron.logbuffer.ControlledFragmentHandler.Action;
import org.agrona.DirectBuffer;
import uk.co.real_logic.fix_gateway.messages.*;
import uk.co.real_logic.fix_gateway.messages.ControlNotificationDecoder.SessionsDecoder;

public interface LibraryEndPointHandler
{
    Action onManageConnection(
        int libraryId,
        long connectionId,
        long sessionId,
        ConnectionType type,
        int lastSequenceNumber,
        int lastReceivedSequenceNumber,
        DirectBuffer buffer,
        int addressOffset,
        int addressLength,
        SessionState state,
        int heartbeatIntervalInS,
        long replyToId,
        int sequenceIndex);

    Action onSessionExists(
        int libraryId,
        long connectionId,
        long sessionId,
        int lastSentSequenceNumber,
        int lastReceivedSequenceNumber,
        LogonStatus logonstatus,
        boolean isSlow,
        String localCompId,
        String localSubId,
        String localLocationId,
        String remoteCompId,
        String remoteSubId,
        String remoteLocationId,
        String username,
        String password);

    Action onError(int libraryId, GatewayError errorType, long replyToId, String message);

    Action onApplicationHeartbeat(int libraryId);

    Action onReleaseSessionReply(int libraryId, long replyToId, SessionReplyStatus status);

    Action onRequestSessionReply(int toId, long replyToId, SessionReplyStatus status);

    Action onNewSentPosition(int libraryId, long position);

    Action onNotLeader(int libraryId, long replyToId, String libraryChannel);

    Action onControlNotification(int libraryId, SessionsDecoder sessions);

    Action onSlowStatusNotification(int libraryId, long connectionId, boolean hasBecomeSlow);

    Action onResetLibrarySequenceNumber(int libraryId, long sessionId);
}
