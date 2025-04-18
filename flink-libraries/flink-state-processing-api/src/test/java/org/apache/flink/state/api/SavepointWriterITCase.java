/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.state.api;

import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.StateBackendOptions;
import org.apache.flink.runtime.checkpoint.metadata.CheckpointMetadata;
import org.apache.flink.runtime.jobgraph.SavepointRestoreSettings;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.runtime.state.StateBackend;
import org.apache.flink.runtime.state.hashmap.HashMapStateBackend;
import org.apache.flink.state.api.functions.BroadcastStateBootstrapFunction;
import org.apache.flink.state.api.functions.KeyedStateBootstrapFunction;
import org.apache.flink.state.api.functions.StateBootstrapFunction;
import org.apache.flink.state.api.runtime.SavepointLoader;
import org.apache.flink.state.rocksdb.EmbeddedRocksDBStateBackend;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.co.BroadcastProcessFunction;
import org.apache.flink.streaming.api.functions.sink.v2.DiscardingSink;
import org.apache.flink.streaming.api.graph.StreamGraph;
import org.apache.flink.test.util.AbstractTestBaseJUnit4;
import org.apache.flink.util.AbstractID;
import org.apache.flink.util.CloseableIterator;
import org.apache.flink.util.Collector;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** IT test for writing savepoints. */
public class SavepointWriterITCase extends AbstractTestBaseJUnit4 {

    private static final long CHECKPOINT_ID = 42;

    private static final String ACCOUNT_UID = "accounts";

    private static final String CURRENCY_UID = "currency";

    private static final String MODIFY_UID = "numbers";

    private static final MapStateDescriptor<String, Double> descriptor =
            new MapStateDescriptor<>("currency-rate", Types.STRING, Types.DOUBLE);

    private static final Collection<Account> accounts =
            Arrays.asList(new Account(1, 100.0), new Account(2, 100.0), new Account(3, 100.0));

    private static final Collection<CurrencyRate> currencyRates =
            Arrays.asList(new CurrencyRate("USD", 1.0), new CurrencyRate("EUR", 1.3));

    @Test
    public void testDefaultStateBackend() throws Exception {
        testStateBootstrapAndModification(new Configuration(), null);
    }

    @Test
    public void testHashMapStateBackend() throws Exception {
        testStateBootstrapAndModification(
                new Configuration().set(StateBackendOptions.STATE_BACKEND, "hashmap"),
                new HashMapStateBackend());
    }

    @Test
    public void testEmbeddedRocksDBStateBackend() throws Exception {
        testStateBootstrapAndModification(
                new Configuration().set(StateBackendOptions.STATE_BACKEND, "rocksdb"),
                new EmbeddedRocksDBStateBackend());
    }

    public void testStateBootstrapAndModification(Configuration config, StateBackend backend)
            throws Exception {
        final String savepointPath = getTempDirPath(new AbstractID().toHexString());

        bootstrapState(backend, savepointPath);

        validateBootstrap(config, savepointPath);

        final String modifyPath = getTempDirPath(new AbstractID().toHexString());

        modifySavepoint(backend, savepointPath, modifyPath);

        validateModification(config, modifyPath);
    }

    private void bootstrapState(StateBackend backend, String savepointPath) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.AUTOMATIC);

        StateBootstrapTransformation<Account> transformation =
                OperatorTransformation.bootstrapWith(env.fromData(accounts), CHECKPOINT_ID)
                        .keyBy(acc -> acc.id)
                        .transform(new AccountBootstrapper());

        StateBootstrapTransformation<CurrencyRate> broadcastTransformation =
                OperatorTransformation.bootstrapWith(env.fromData(currencyRates), CHECKPOINT_ID)
                        .transform(new CurrencyBootstrapFunction());

        SavepointWriter writer =
                backend == null
                        ? SavepointWriter.newSavepoint(env, CHECKPOINT_ID, 128)
                        : SavepointWriter.newSavepoint(env, backend, CHECKPOINT_ID, 128);

        writer.withOperator(OperatorIdentifier.forUid(ACCOUNT_UID), transformation)
                .withOperator(getUidHashFromUid(CURRENCY_UID), broadcastTransformation)
                .write(savepointPath);

        env.execute("Bootstrap");
    }

    private void validateBootstrap(Configuration configuration, String savepointPath)
            throws Exception {
        CheckpointMetadata metadata = SavepointLoader.loadSavepointMetadata(savepointPath);
        assertThat(metadata.getCheckpointId()).isEqualTo(CHECKPOINT_ID);

        StreamExecutionEnvironment env =
                StreamExecutionEnvironment.getExecutionEnvironment(configuration);

        DataStream<Account> stream =
                env.fromData(accounts)
                        .keyBy(acc -> acc.id)
                        .flatMap(new UpdateAndGetAccount())
                        .uid(ACCOUNT_UID);

        final CloseableIterator<Account> results = stream.collectAsync();

        env.fromData(currencyRates)
                .connect(env.fromData(currencyRates).broadcast(descriptor))
                .process(new CurrencyValidationFunction())
                .uid(CURRENCY_UID)
                .sinkTo(new DiscardingSink<>());

        final StreamGraph streamGraph = env.getStreamGraph();
        streamGraph.setSavepointRestoreSettings(
                SavepointRestoreSettings.forPath(savepointPath, false));

        env.execute(streamGraph);

        assertThat(results).toIterable().hasSize(3);
        results.close();
    }

    private void modifySavepoint(StateBackend backend, String savepointPath, String modifyPath)
            throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setRuntimeMode(RuntimeExecutionMode.AUTOMATIC);

        StateBootstrapTransformation<Integer> transformation =
                OperatorTransformation.bootstrapWith(env.fromData(1, 2, 3))
                        .transform(new ModifyProcessFunction());

        SavepointWriter writer =
                backend == null
                        ? SavepointWriter.fromExistingSavepoint(env, savepointPath)
                        : SavepointWriter.fromExistingSavepoint(env, savepointPath, backend);

        writer.removeOperator(OperatorIdentifier.forUid(CURRENCY_UID))
                .withOperator(getUidHashFromUid(MODIFY_UID), transformation)
                .write(modifyPath);

        env.execute("Modifying");
    }

    private void validateModification(Configuration configuration, String savepointPath)
            throws Exception {
        CheckpointMetadata metadata = SavepointLoader.loadSavepointMetadata(savepointPath);
        assertThat(metadata.getCheckpointId()).isEqualTo(CHECKPOINT_ID);

        StreamExecutionEnvironment sEnv =
                StreamExecutionEnvironment.getExecutionEnvironment(configuration);

        DataStream<Account> stream =
                sEnv.fromData(accounts)
                        .keyBy(acc -> acc.id)
                        .flatMap(new UpdateAndGetAccount())
                        .uid(ACCOUNT_UID);

        final CloseableIterator<Account> results = stream.collectAsync();

        stream.map(acc -> acc.id)
                .map(new StatefulOperator())
                .uid(MODIFY_UID)
                .sinkTo(new DiscardingSink<>());

        final StreamGraph streamGraph = sEnv.getStreamGraph();
        streamGraph.setSavepointRestoreSettings(
                SavepointRestoreSettings.forPath(savepointPath, false));

        sEnv.execute(streamGraph);

        assertThat(results).toIterable().hasSize(3);
        results.close();
    }

    private static OperatorIdentifier getUidHashFromUid(String uid) {
        return OperatorIdentifier.forUidHash(
                OperatorIdentifier.forUid(uid).getOperatorId().toHexString());
    }

    /** A simple pojo. */
    @SuppressWarnings("WeakerAccess")
    public static class Account {
        Account(int id, double amount) {
            this.id = id;
            this.amount = amount;
            this.timestamp = 1000L;
        }

        public int id;

        public double amount;

        public long timestamp;

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Account
                    && ((Account) obj).id == id
                    && ((Account) obj).amount == amount;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, amount);
        }
    }

    /** A simple pojo. */
    @SuppressWarnings("WeakerAccess")
    public static class CurrencyRate {
        public String currency;

        public Double rate;

        CurrencyRate(String currency, double rate) {
            this.currency = currency;
            this.rate = rate;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof CurrencyRate
                    && ((CurrencyRate) obj).currency.equals(currency)
                    && ((CurrencyRate) obj).rate.equals(rate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(currency, rate);
        }
    }

    /** A savepoint writer function. */
    public static class AccountBootstrapper extends KeyedStateBootstrapFunction<Integer, Account> {
        ValueState<Double> state;

        @Override
        public void open(OpenContext openContext) {
            ValueStateDescriptor<Double> descriptor =
                    new ValueStateDescriptor<>("total", Types.DOUBLE);
            state = getRuntimeContext().getState(descriptor);
        }

        @Override
        public void processElement(Account value, Context ctx) throws Exception {
            state.update(value.amount);
        }
    }

    /** A streaming function bootstrapped off the state. */
    public static class UpdateAndGetAccount extends RichFlatMapFunction<Account, Account> {
        ValueState<Double> state;

        @Override
        public void open(OpenContext openContext) throws Exception {
            super.open(openContext);

            ValueStateDescriptor<Double> descriptor =
                    new ValueStateDescriptor<>("total", Types.DOUBLE);
            state = getRuntimeContext().getState(descriptor);
        }

        @Override
        public void flatMap(Account value, Collector<Account> out) throws Exception {
            Double current = state.value();
            if (current != null) {
                value.amount += current;
            }

            state.update(value.amount);
            out.collect(value);
        }
    }

    /** A bootstrap function. */
    public static class ModifyProcessFunction extends StateBootstrapFunction<Integer> {
        List<Integer> numbers;

        ListState<Integer> state;

        @Override
        public void open(OpenContext openContext) {
            numbers = new ArrayList<>();
        }

        @Override
        public void processElement(Integer value, Context ctx) {
            numbers.add(value);
        }

        @Override
        public void snapshotState(FunctionSnapshotContext context) throws Exception {
            state.update(numbers);
        }

        @Override
        public void initializeState(FunctionInitializationContext context) throws Exception {
            state =
                    context.getOperatorStateStore()
                            .getUnionListState(new ListStateDescriptor<>("numbers", Types.INT));
        }
    }

    /** A streaming function bootstrapped off the state. */
    public static class StatefulOperator extends RichMapFunction<Integer, Integer>
            implements CheckpointedFunction {
        List<Integer> numbers;

        ListState<Integer> state;

        @Override
        public void open(OpenContext openContext) {
            numbers = new ArrayList<>();
        }

        @Override
        public void snapshotState(FunctionSnapshotContext context) throws Exception {
            state.update(numbers);
        }

        @Override
        public void initializeState(FunctionInitializationContext context) throws Exception {
            state =
                    context.getOperatorStateStore()
                            .getUnionListState(new ListStateDescriptor<>("numbers", Types.INT));

            if (context.isRestored()) {
                Set<Integer> expected = new HashSet<>();
                expected.add(1);
                expected.add(2);
                expected.add(3);

                for (Integer number : state.get()) {
                    Assert.assertTrue("Duplicate state", expected.contains(number));
                    expected.remove(number);
                }

                Assert.assertTrue(
                        "Failed to bootstrap all state elements: "
                                + Arrays.toString(expected.toArray()),
                        expected.isEmpty());
            }
        }

        @Override
        public Integer map(Integer value) {
            return null;
        }
    }

    /** A broadcast bootstrap function. */
    public static class CurrencyBootstrapFunction
            extends BroadcastStateBootstrapFunction<CurrencyRate> {

        @Override
        public void processElement(CurrencyRate value, Context ctx) throws Exception {
            ctx.getBroadcastState(descriptor).put(value.currency, value.rate);
        }
    }

    /** Checks the restored broadcast state. */
    public static class CurrencyValidationFunction
            extends BroadcastProcessFunction<CurrencyRate, CurrencyRate, Void> {

        @Override
        public void processElement(CurrencyRate value, ReadOnlyContext ctx, Collector<Void> out)
                throws Exception {
            Assert.assertEquals(
                    "Incorrect currency rate",
                    value.rate,
                    ctx.getBroadcastState(descriptor).get(value.currency),
                    0.0001);
        }

        @Override
        public void processBroadcastElement(CurrencyRate value, Context ctx, Collector<Void> out) {
            // ignore
        }
    }
}
