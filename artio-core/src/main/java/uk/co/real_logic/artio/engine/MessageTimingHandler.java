/*
 * Copyright 2020 Monotonic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.engine;

/**
 * Handler can be used to take per-message timings. The callback will be called when a message is passed
 * to the TCP stack. This doesn't mean that the message is actually on the wire - just that it is in the
 * operating system's TCP buffer. If a message is back-pressured and partially sent then the callback
 * will only be called once the message is actually sent.
 *
 * NB: this does not get called for replayed messages.
 */
public interface MessageTimingHandler
{
    /**
     * Called when a message is written to the TCP stack.
     *
     * @param connectionId the connection id of the connection that the message is sent on.
     */
    void onMessage(long connectionId);
}
