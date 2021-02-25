/**
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
package io.streamnative.pulsar.plugins.delayed.impl;

import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.streamnative.pulsar.plugins.delayed.DelayedMessageIndexer;
import io.streamnative.pulsar.plugins.delayed.DelayedMessageStorage;
import org.apache.bookkeeper.mledger.Position;
import org.apache.bookkeeper.mledger.impl.PositionImpl;
import org.apache.pulsar.common.protocol.Commands;
import org.apache.pulsar.common.util.collections.TripleLongPriorityQueue;

import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class InMemoryDelayedMessageIndexer implements DelayedMessageIndexer {

    private final TripleLongPriorityQueue queue = new TripleLongPriorityQueue();
    private CompletableFuture<Position> currentScheduledFuture;
    private final Clock clock = Clock.systemUTC();
    private final Timer timer;
    private final DelayedMessageStorage storage;
    private Timeout timeout;

    public InMemoryDelayedMessageIndexer(DelayedMessageStorage storage, Timer timer) {
        this.timer = timer;
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Void> initializeAsync() {
        return rebuildIndex();
    }

    @Override
    public CompletableFuture<Void> addAsync(Position position, long deliverTimestamp) {
        queue.add(deliverTimestamp, position.getLedgerId(), position.getEntryId());
        long headScheduled = queue.peekN1();
        if (deliverTimestamp < headScheduled) {
            timeout.cancel();
        }
        if (currentScheduledFuture != null) {
            pollScheduled();
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Position> pollScheduled() {
        if (currentScheduledFuture == null || currentScheduledFuture.isDone()) {
            currentScheduledFuture = new CompletableFuture<>();
        }
        long cutTime = clock.millis();
        long toDeliver = queue.peekN1();

        if (toDeliver < cutTime) {
            if (timeout == null) {
                timeout = timer.newTimeout(timeout -> pollScheduled(),
                        cutTime - toDeliver, TimeUnit.MILLISECONDS);
            }
        } else {
            long ledgerId = queue.peekN2();
            long entryId = queue.peekN3();
            currentScheduledFuture.complete(PositionImpl.get(ledgerId, entryId));
        }
        return currentScheduledFuture;
    }

    @Override
    public CompletableFuture<Position> truncateAsync() {
        queue.clear();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        queue.clear();
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> rebuildIndex() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        this.storage.replay((entry, isReachEnd) -> {
            if (entry != null) {
                // Peek the message metadata from the entry and add the timestamp, position to the in-memory index.
                long deliverTime = Commands.parseMessageMetadata(entry.getDataBuffer()).getDeliverAtTime();
                addAsync(entry.getPosition(), deliverTime);
            }
            if (isReachEnd) {
                future.complete(null);
            }
        });
        return future;
    }
}
