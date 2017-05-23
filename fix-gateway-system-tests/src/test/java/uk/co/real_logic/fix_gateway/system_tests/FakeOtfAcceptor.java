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
package uk.co.real_logic.fix_gateway.system_tests;

import org.hamcrest.Matcher;
import uk.co.real_logic.fix_gateway.DebugLogger;
import uk.co.real_logic.fix_gateway.ValidationError;
import uk.co.real_logic.fix_gateway.decoder.Constants;
import uk.co.real_logic.fix_gateway.fields.AsciiFieldFlyweight;
import uk.co.real_logic.fix_gateway.otf.MessageControl;
import uk.co.real_logic.fix_gateway.otf.OtfMessageAcceptor;
import uk.co.real_logic.fix_gateway.session.Session;
import uk.co.real_logic.fix_gateway.util.AsciiBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.Assert.assertThat;
import static uk.co.real_logic.fix_gateway.LogTag.FIX_TEST;
import static uk.co.real_logic.fix_gateway.decoder.Constants.MSG_TYPE;
import static uk.co.real_logic.fix_gateway.system_tests.FixMessage.hasMessageSequenceNumber;
import static uk.co.real_logic.fix_gateway.system_tests.FixMessage.hasSequenceIndex;

/**
 * An otf acceptor used to accumulate/log/check acceptor interactions.
 */
public class FakeOtfAcceptor implements OtfMessageAcceptor
{
    private final List<FixMessage> messages = new ArrayList<>();

    private ValidationError error;
    private boolean isCompleted;
    private String senderCompId;
    private FixMessage message;

    public MessageControl onNext()
    {
        senderCompId = null;
        error = null;
        isCompleted = false;
        ensureMessage();
        return MessageControl.CONTINUE;
    }

    public MessageControl onComplete()
    {
        isCompleted = true;
        messages.add(message);
        message = null;
        return MessageControl.CONTINUE;
    }

    public synchronized MessageControl onField(
        final int tag, final AsciiBuffer buffer, final int offset, final int length)
    {
        DebugLogger.log(FIX_TEST, "Field: %s=%s%n", tag, buffer, offset, length);
        if (tag == Constants.SENDER_COMP_ID)
        {
            senderCompId = buffer.getAscii(offset, length);
        }

        message.put(tag, buffer.getAscii(offset, length));
        return MessageControl.CONTINUE;
    }

    public MessageControl onGroupHeader(final int tag, final int numInGroup)
    {
        return MessageControl.CONTINUE;
    }

    public MessageControl onGroupBegin(final int tag, final int numInGroup, final int index)
    {
        return MessageControl.CONTINUE;
    }

    public MessageControl onGroupEnd(final int tag, final int numInGroup, final int index)
    {
        return MessageControl.CONTINUE;
    }

    public boolean onError(
        final ValidationError error,
        final int messageType,
        final int tagNumber,
        final AsciiFieldFlyweight value)
    {
        this.error = error;
        System.err.printf("%s for %d @ %d%n", error, messageType, tagNumber);
        return false;
    }

    public List<FixMessage> messages()
    {
        return messages;
    }

    public String lastSenderCompId()
    {
        return senderCompId;
    }

    public ValidationError lastError()
    {
        return error;
    }

    public boolean isCompleted()
    {
        return isCompleted;
    }

    public void forSession(final Session session)
    {
        ensureMessage();
        message.session(session);
    }

    private void ensureMessage()
    {
        if (message == null)
        {
            message = new FixMessage();
        }
    }

    public FixMessage lastMessage()
    {
        return messages.get(messages.size() - 1);
    }

    public Stream<FixMessage> hasReceivedMessage(final String messageType)
    {
        return messages()
            .stream()
            .filter((fixMessage) -> fixMessage.get(MSG_TYPE).equals(messageType));
    }

    void allMessagesHaveSequenceIndex(final int sequenceIndex)
    {
        messages(hasSequenceIndex(sequenceIndex), (msg) -> true);
    }

    void logonMessagesHaveSequenceNumbers(final int sequenceIndex)
    {
        messages(hasMessageSequenceNumber(sequenceIndex), FixMessage::isLogon);
    }

    private void messages(final Matcher<FixMessage> matcher, final Predicate<? super FixMessage> predicate)
    {
        messages.stream()
            .filter(predicate)
            .forEach((message) -> assertThat(message.toString(), message, matcher));
    }

}
