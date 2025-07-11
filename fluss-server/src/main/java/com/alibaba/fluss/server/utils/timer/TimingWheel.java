/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.fluss.server.utils.timer;

import com.alibaba.fluss.utils.clock.Clock;

import javax.annotation.concurrent.NotThreadSafe;

import java.util.Timer;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.atomic.AtomicInteger;

/* This file is based on source code of Apache Kafka Project (https://kafka.apache.org/), licensed by the Apache
 * Software Foundation (ASF) under the Apache License, Version 2.0. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership. */

/**
 * Hierarchical Timing Wheels.
 *
 * <p>A simple timing wheel is a circular list of buckets of timer tasks. Let u be the time unit. A
 * timing wheel with size n has n buckets and can hold timer tasks in n * u time interval. Each
 * bucket holds timer tasks that fall into the corresponding time range. At the beginning, the first
 * bucket holds tasks for [0, u), the second bucket holds tasks for [u, 2u), …, the n-th bucket for
 * [u * (n -1), u * n). Every interval of time unit u, the timer ticks and moved to the next bucket
 * then expire all timer tasks in it. So, the timer never insert a task into the bucket for the
 * current time since it is already expired. The timer immediately runs the expired task. The
 * emptied bucket is then available for the next round, so if the current bucket is for the time t,
 * it becomes the bucket for [t + u * n, t + (n + 1) * u) after a tick. A timing wheel has O(1) cost
 * for insert/delete (start-timer/stop-timer) whereas priority queue based timers, such as {@link
 * DelayQueue} and {@link Timer}, have O(log n) insert/delete cost.
 *
 * <p>A major drawback of a simple timing wheel is that it assumes that a timer request is within
 * the time interval of n * u from the current time. If a timer request is out of this interval, it
 * is an overflow. A hierarchical timing wheel deals with such overflows. It is a hierarchically
 * organized timing wheels. The lowest level has the finest time resolution. As moving up the
 * hierarchy, time resolutions become coarser. If the resolution of a wheel at one level is u and
 * the size is n, the resolution of the next level should be n * u. At each level overflows are
 * delegated to the wheel in one level higher. When the wheel in the higher level ticks, it reinsert
 * imer tasks to the lower level. An overflow wheel can be created on-demand. When a bucket in an
 * overflow bucket expires, all tasks in it are reinserted into the timer recursively. The tasks are
 * then moved to the finer grain wheels or be executed. The insert (start-timer) cost is O(m) where
 * m is the number of wheels, which is usually very small compared to the number of requests in the
 * system, and the delete (stop-timer) cost is still O(1).
 *
 * <p>Example Let's say that u is 1 and n is 3. If the start time is c, then the buckets at
 * different levels are:
 *
 * <pre>
 * level   buckets
 * 1       [c,c] [c+1,c+1] [c+2,c+2]
 * 2       [c,c+2] [c+3,c+5] [c+6,c+8]
 * 3       [c,c+8] [c+9,c+17] [c+18,c+26]
 * </pre>
 *
 * <p>The bucket expiration is at the time of bucket beginning. So at time = c+1, buckets [c,c],
 * [c,c+2] and [c,c+8] are expired. Level 1's clock moves to c+1, and [c+3,c+3] is created. Level 2
 * and level3's clock stay at c since their clocks move in unit of 3 and 9, respectively. So, no new
 * buckets are created in level 2 and 3.
 *
 * <p>Note that bucket [c,c+2] in level 2 won't receive any task since that range is already covered
 * in level 1. The same is true for the bucket [c,c+8] in level 3 since its range is covered in
 * level 2. This is a bit wasteful, but simplifies the implementation.
 *
 * <pre>
 * level   buckets
 * 1       [c+1,c+1]  [c+2,c+2]  [c+3,c+3]
 * 2       [c,c+2]    [c+3,c+5]  [c+6,c+8]
 * 3       [c,c+8]    [c+9,c+17] [c+18,c+26]
 * </pre>
 *
 * <p>At time = c+2, [c+1,c+1] is newly expired. Level 1 moves to c+2, and [c+4,c+4] is created,
 *
 * <pre>
 * level   buckets
 * 1       [c+2,c+2] [c+3,c+3] [c+4,c+4]
 * 2       [c,c+2] [c+3,c+5] [c+6,c+8]
 * 3       [c,c+8] [c+9,c+17] [c+18,c+26]
 * </pre>
 *
 * <p>At time = c+3, [c+2,c+2] is newly expired. Level 2 moves to c+3, and [c+5,c+5] and [c+9,c+11]
 * are created. Level 3 stay at c.
 *
 * <pre>
 * level   buckets
 * 1      [c+3,c+3] [c+4,c+4] [c+5,c+5]
 * 2      [c+3,c+5] [c+6,c+8] [c+9,c+11]
 * 3      [c,c+8]   [c+9,c+17] [c+18,c+26]
 * </pre>
 *
 * <p>The hierarchical timing wheels works especially well when operations are completed before they
 * time out. Even when everything times out, it still has advantageous when there are many items in
 * the timer. Its insert cost (including reinsert) and delete cost are O(m) and O(1), respectively
 * while priority queue based timers takes O(log N) for both insert and delete where N is the number
 * of items in the queue.
 *
 * <p>This class is not thread-safe. There should not be any add calls while advanceClock is
 * executing. It is caller's responsibility to enforce it. Simultaneous add calls are thread-safe.
 */
@NotThreadSafe
final class TimingWheel {
    /** The time span represented by an individual bucket. */
    private final long tickMs;

    /** The total size of the timing wheel, namely the number of buckets in current timing wheel. */
    private final int wheelSize;

    /** The total time span represented by this timing wheel, equals to tickMs * wheelSize. */
    private final long interval;

    /**
     * The buckets list, A bucket is composed of a {@link TimerTaskList}, which is a linked list
     * representing the tasks to be executed.
     */
    private final TimerTaskList[] buckets;

    /** The counter for the number of tasks in this timing wheel. */
    private final AtomicInteger taskCounter;

    private final DelayQueue<TimerTaskList> queue;
    private final Clock clock;
    /** The upper level timing wheel. */
    private volatile TimingWheel overflowWheel;

    private long currentTime;

    TimingWheel(
            long tickMs,
            int wheelSize,
            long startMs,
            AtomicInteger taskCounter,
            DelayQueue<TimerTaskList> queue,
            Clock clock) {
        this.tickMs = tickMs;
        this.wheelSize = wheelSize;
        this.interval = tickMs * wheelSize;
        this.taskCounter = taskCounter;
        this.queue = queue;
        this.buckets = new TimerTaskList[wheelSize];
        this.currentTime = startMs - (startMs % tickMs);
        this.overflowWheel = null;

        // Initialize buckets
        for (int i = 0; i < wheelSize; i++) {
            buckets[i] = new TimerTaskList(taskCounter, clock);
        }
        this.clock = clock;
    }

    private synchronized void addOverflowWheel() {
        if (overflowWheel == null) {
            overflowWheel =
                    new TimingWheel(interval, wheelSize, currentTime, taskCounter, queue, clock);
        }
    }

    boolean add(TimerTaskEntry timerTaskEntry) {
        long expiration = timerTaskEntry.getExpirationMs();
        if (timerTaskEntry.isCancelled()) {
            return false;
        } else if (expiration < currentTime + tickMs) {
            // Already expired.
            return false;
        } else if (expiration < currentTime + interval) {
            // Put it its own bucket.
            long virtualBucketId = expiration / tickMs;
            TimerTaskList bucket = buckets[(int) (virtualBucketId % (long) wheelSize)];
            bucket.add(timerTaskEntry);

            // Set the bucket expiration time.
            if (bucket.setExpiration(virtualBucketId * tickMs)) {
                // Only enqueue the bucket if its expiration time has changed.
                queue.offer(bucket);
            }
            return true;
        } else {
            // Out of the interval. Put it into the overflow wheel.
            if (overflowWheel == null) {
                addOverflowWheel();
            }
            return overflowWheel.add(timerTaskEntry);
        }
    }

    void advanceClock(long timeMs) {
        if (timeMs >= currentTime + tickMs) {
            currentTime = timeMs - (timeMs % tickMs);
            // Try to advance the clock of the overflow wheel if present.
            if (overflowWheel != null) {
                overflowWheel.advanceClock(currentTime);
            }
        }
    }
}
