/*
 * Copyright 2014 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway.engine;

import io.aeron.logbuffer.ControlledFragmentHandler;
import io.aeron.logbuffer.Header;
import org.agrona.DirectBuffer;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.LongLongConsumer;
import org.agrona.concurrent.Agent;
import uk.co.real_logic.fix_gateway.messages.FixMessageDecoder;
import uk.co.real_logic.fix_gateway.messages.MessageHeaderDecoder;
import uk.co.real_logic.fix_gateway.protocol.GatewayPublication;
import uk.co.real_logic.fix_gateway.replication.ClusterableSubscription;

class ClusterPositionSender implements Agent, ControlledFragmentHandler, LongLongConsumer
{
    private static final int MISSING_LIBRARY = -1;

    private final MessageHeaderDecoder messageHeader = new MessageHeaderDecoder();
    private final FixMessageDecoder fixMessage = new FixMessageDecoder();

    private final Long2LongHashMap libraryIdToClusterPosition = new Long2LongHashMap(MISSING_LIBRARY);
    //private final

    private final ClusterableSubscription fromClusterSubscription;
    private final GatewayPublication toLibraryPublication;

    private int resendCount;

    ClusterPositionSender(
        final ClusterableSubscription fromClusterSubscription, final GatewayPublication toLibraryPublication)
    {
        this.fromClusterSubscription = fromClusterSubscription;
        this.toLibraryPublication = toLibraryPublication;
    }

    public int doWork() throws Exception
    {
        final int work = fromClusterSubscription.controlledPoll(this, 10);

        resendCount = 0;
        libraryIdToClusterPosition.longForEach(this);

        return work + resendCount;
    }

    public Action onFragment(final DirectBuffer buffer, int offset, final int length, final Header header)
    {
        messageHeader.wrap(buffer, offset);

        if (messageHeader.templateId() == FixMessageDecoder.TEMPLATE_ID)
        {
            offset += MessageHeaderDecoder.ENCODED_LENGTH;

            fixMessage.wrap(buffer, offset, messageHeader.blockLength(), messageHeader.version());
            libraryIdToClusterPosition.put(fixMessage.libraryId(), header.position());
        }

        return Action.CONTINUE;
    }

    public String roleName()
    {
        return "ClusterPositionSender";
    }

    public void accept(final long libraryId, final long savedPosition)
    {
        if (toLibraryPublication.saveNewSentPosition((int) libraryId, savedPosition) >= 0)
        {
            libraryIdToClusterPosition.remove(libraryId);
            resendCount++;
        }
    }
}
