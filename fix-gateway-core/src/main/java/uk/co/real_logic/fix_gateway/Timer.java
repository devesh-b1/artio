/*
 * Copyright 2015 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway;

import org.HdrHistogram.Histogram;
import uk.co.real_logic.agrona.concurrent.NanoClock;

import static uk.co.real_logic.fix_gateway.CommonConfiguration.MESSAGES_EXCHANGED;
import static uk.co.real_logic.fix_gateway.CommonConfiguration.WARMUP_MESSAGES;

public final class Timer
{
    public static final int TOTAL_MESSAGES = WARMUP_MESSAGES + MESSAGES_EXCHANGED;
    private final Histogram histogram;
    private final NanoClock clock;
    private final String prefix;
    private final String name;

    private int count;

    public Timer(final String name, final NanoClock clock)
    {
        this("", name, clock);
    }

    public Timer(final String prefix, final String name, final NanoClock clock)
    {
        this.prefix = prefix;
        this.name = name;
        histogram = new Histogram(3);
        this.clock = clock;
    }

    public long recordSince(final long timestamp)
    {
        final long time = clock.nanoTime();
        final long duration = time - timestamp;
        histogram.recordValue(duration);
        count++;
        if (count == WARMUP_MESSAGES)
        {
            histogram.reset();
        }
        else if (count == TOTAL_MESSAGES)
        {
            prettyPrint(prefix, name, histogram, 1000);
        }
        return time;
    }

    public static void prettyPrint(
        final String prefix, final String name, final Histogram histogram, final double scalingFactor)
    {
        System.out.printf("%s Histogram\n", name);
        System.out.printf("%s----------\n", prefix);
        System.out.printf("%sMean: %G\n", prefix, histogram.getMean() / scalingFactor);
        System.out.printf("%s1:    %G\n", prefix, histogram.getValueAtPercentile(1) / scalingFactor);
        System.out.printf("%s50:   %G\n", prefix, histogram.getValueAtPercentile(50) / scalingFactor);
        System.out.printf("%s90:   %G\n", prefix, histogram.getValueAtPercentile(90) / scalingFactor);
        System.out.printf("%s99:   %G\n", prefix, histogram.getValueAtPercentile(99) / scalingFactor);
        System.out.printf("%s99.9: %G\n", prefix, histogram.getValueAtPercentile(99.9) / scalingFactor);
        System.out.printf("%s100:  %G\n", prefix, histogram.getValueAtPercentile(100) / scalingFactor);
        System.out.printf("%s----------", prefix);
        System.out.println();
    }

}
