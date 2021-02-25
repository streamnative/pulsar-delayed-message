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

import io.netty.buffer.ByteBuf;
import io.streamnative.pulsar.plugins.delayed.DelayedMessageStorage;
import org.apache.bookkeeper.mledger.Entry;
import org.apache.bookkeeper.mledger.Position;
import org.apache.bookkeeper.mledger.impl.EntryImpl;
import org.apache.pulsar.common.util.FutureUtil;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class InMemoryDelayedMessageStorage implements DelayedMessageStorage {

    private static long LEDGER_ID = 0;

    private final long ledgerId;
    private long entryId = 0;

    private final List<Entry> entries;

    public InMemoryDelayedMessageStorage() {
        this.ledgerId = ++ LEDGER_ID;
        this.entries = new ArrayList<>();
    }

    @Override
    public void replay(ReplayCallback callback) {
        Iterator<Entry> iterator = entries.iterator();
        while (true) {
            Entry entry = null;
            if (iterator.hasNext()) {
                entry = iterator.next();
            }
            boolean hasNext = iterator.hasNext();
            callback.handleEntry(entry, hasNext);
            if (!hasNext) {
                break;
            }
        }
    }

    @Override
    public CompletableFuture<Position> writeAsync(ByteBuf data) {
        Entry entry = EntryImpl.create(ledgerId, entryId++, data);
        entries.add(entry);
        return CompletableFuture.completedFuture(entry.getPosition());
    }

    @Override
    public CompletableFuture<Entry> readAsync(Position position) {
        if (position.getLedgerId() != ledgerId || position.getEntryId() >= entries.size()) {
            return FutureUtil.failedFuture(null);
        }
        return CompletableFuture.completedFuture(entries.get((int) position.getEntryId()));
    }

    @Override
    public CompletableFuture<Void> truncateAsync() {
        entries.clear();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        entries.clear();
        return CompletableFuture.completedFuture(null);
    }
}
