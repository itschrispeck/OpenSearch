/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.benchmark.action.bulk;

import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.core.common.io.stream.Writeable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark demonstrating the unstable_if deoptimization on
 * BulkItemRequest.writeThin() when primaryResponse is occasionally null.
 *
 * C2 speculates on the dominant branch of the null check. When the minority
 * branch is hit, it triggers an uncommon trap (unstable_if). With action=none,
 * each minority-branch call deoptimizes to the interpreter without recompilation.
 */
@Fork(value = 1)
@Warmup(iterations = 5, time = 3)
@Measurement(iterations = 5, time = 5)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
public class WriteOptionalWriteableBenchmark {

    // Percentage of calls where primaryResponse is null.
    // In production this might be 1-5%, enough to trigger the trap but
    // not enough to shift the profile during warmup.
    @Param({"0", "1", "5", "10", "50"})
    int nullPercentage;

    private static final int BATCH_SIZE = 1000;

    private boolean[] isNull;
    private DevNullStreamOutput out;
    private int counter;

    static class DevNullStreamOutput extends StreamOutput {
        @Override
        public void writeByte(byte b) {}

        @Override
        public void writeBytes(byte[] b, int offset, int length) {}

        @Override
        public void flush() {}

        @Override
        public void close() {}

        @Override
        public void reset() {}
    }

    /**
     * Simulates a primaryResponse object with a writeThin method.
     */
    static class FakePrimaryResponse {
        void writeThin(StreamOutput out) throws IOException {
            out.writeVInt(200);
            out.writeVInt(1);
        }
    }

    private final FakePrimaryResponse primaryResponse = new FakePrimaryResponse();

    @Setup(Level.Trial)
    public void setup() {
        out = new DevNullStreamOutput();
        isNull = new boolean[BATCH_SIZE];
        for (int i = 0; i < BATCH_SIZE; i++) {
            isNull[i] = (i % 100) < nullPercentage;
        }
        counter = 0;
    }

    /**
     * Original code pattern: uses writeOptionalWriteable with a lambda.
     * Triggers unstable_if when primaryResponse is occasionally null.
     */
    @Benchmark
    public void originalWriteThin() throws IOException {
        FakePrimaryResponse resp = isNull[(counter++ & 0x7FFFFFFF) % BATCH_SIZE] ? null : primaryResponse;

        out.writeVInt(1);
        // This mirrors the original: ternary + writeOptionalWriteable + lambda
        out.writeOptionalWriteable(resp == null ? null : resp::writeThin);
    }

    /**
     * Fixed code pattern: explicit if/else with direct call.
     * No unstable_if possible because both branches are explicit.
     * No lambda allocation.
     */
    @Benchmark
    public void fixedWriteThin() throws IOException {
        FakePrimaryResponse resp = isNull[(counter++ & 0x7FFFFFFF) % BATCH_SIZE] ? null : primaryResponse;

        out.writeVInt(1);
        if (resp != null) {
            out.writeBoolean(true);
            resp.writeThin(out);
        } else {
            out.writeBoolean(false);
        }
    }
}
