package net.onrc.onos.core.util.serializers;

import static org.junit.Assert.*;

import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.floodlightcontroller.util.MACAddress;
import net.onrc.onos.core.topology.HostEvent;
import net.onrc.onos.core.topology.LinkEvent;
import net.onrc.onos.core.topology.PortEvent;
import net.onrc.onos.core.topology.TopologyElement;
import net.onrc.onos.core.topology.TopologyEvent;
import net.onrc.onos.core.topology.SwitchEvent;
import net.onrc.onos.core.util.Dpid;
import net.onrc.onos.core.util.PortNumber;
import net.onrc.onos.core.util.SwitchPort;

import org.junit.Before;
import org.junit.Test;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

/**
 * Tests to capture Kryo serialization characteristics.
 * <p/>
 * <ul>
 *  <li>Serialization/deserialization throughput</li>
 *  <li>Serialized size</li>
 *  <li>Equality of object before and after serializaton,deserialization</li>
 *  <li>TODO bit by bit comparison of serialized bytes</li>
 * </ul>
 */
public class KryoFactoryTest {

    private static final int NUM_ITERATIONS = Integer.parseInt(
                                    System.getProperty("iterations", "100"));

    private static final Dpid DPID_A = new Dpid(0x1234L);
    private static final Dpid DPID_B = new Dpid(Long.MAX_VALUE);
    private static final PortNumber PORT_NO_A = new PortNumber((short) 42);
    private static final PortNumber PORT_NO_B = new PortNumber((short) 65534);

    private static final double SEC_IN_NANO = 1000 * 1000 * 1000.0;

    private KryoFactory kryoFactory;

    @Before
    public void setUp() throws Exception {
        kryoFactory = new KryoFactory(1);
    }

    /**
     * Benchmark result.
     */
    private static final class Result {
        /**
         * Serialized type name.
         */
        String type;
        /**
         * Serialized size.
         */
        int size;
        /**
         * Serialization throughput (ops/sec).
         */
        double ser;
        /**
         * Deserialization throughput (ops/sec).
         */
        double deser;

        public Result(String type, int size, double ser, double deser) {
            this.type = type;
            this.size = size;
            this.ser = ser;
            this.deser = deser;
        }
    }

    private static enum EqualityCheck {
        /**
         * No way to compare equality provided.
         */
        IMPOSSIBLE,
        /**
         * custom equals method is defined.
         */
        EQUALS,
        /**
         * Can be compared using toString() result.
         */
        TO_STRING,
    }

    /**
     * Benchmark serialization of specified object.
     *
     * @param obj the object to benchmark
     * @param equalityCheck how to check equality of deserialized object.
     * @return benchmark {@link Result}
     */
    private Result benchType(Object obj, EqualityCheck equalityCheck) {

        Kryo kryo = kryoFactory.newKryo();
        try {
            byte[] buffer = new byte[1 * 1000 * 1000];
            Output output = new Output(buffer);

            // Measurement: serialization size
            kryo.writeClassAndObject(output, obj);
            int serializedBytes = output.toBytes().length;

            // Measurement: serialization throughput
            byte[] result = null;

            long t1 = System.nanoTime();
            for (int j = 0; j < NUM_ITERATIONS; j++) {
                output.clear();
                kryo.writeClassAndObject(output, obj);
                result = output.toBytes();
            }
            long t2 = System.nanoTime();
            double serTput = NUM_ITERATIONS * SEC_IN_NANO / (t2 - t1);

            // Measurement: deserialization throughput
            Object objOut = null;
            Input input = new Input(result);
            long t3 = System.nanoTime();
            for (int j = 0; j < NUM_ITERATIONS; j++) {
                input.setBuffer(result);
                objOut = kryo.readClassAndObject(input);
            }
            long t4 = System.nanoTime();

            switch (equalityCheck) {
            case IMPOSSIBLE:
                break;
            case EQUALS:
                assertEquals(obj, objOut);
                break;
            case TO_STRING:
                assertEquals(obj.toString(), objOut.toString());
                break;
            default:
                break;
            }
            double deserTput = NUM_ITERATIONS * SEC_IN_NANO / (t4 - t3);

            return new Result(obj.getClass().getSimpleName(),
                    serializedBytes, serTput, deserTput);
        } finally {
            kryoFactory.deleteKryo(kryo);
        }
    }

    /**
     * Benchmark serialization of types registered to KryoFactory.
     */
    @Test
    public void benchmark() throws Exception {

        List<Result> results = new ArrayList<>();

        // To be more strict, we should be checking serialized byte[].
        { // CHECKSTYLE IGNORE THIS LINE
            HostEvent obj = new HostEvent(MACAddress.valueOf(0x12345678));
            obj.createStringAttribute(TopologyElement.TYPE, TopologyElement.TYPE_PACKET_LAYER);
            obj.addAttachmentPoint(new SwitchPort(DPID_A, PORT_NO_A));
            // avoid using System.currentTimeMillis() var-int size may change
            obj.setLastSeenTime(392860800000L);
            obj.freeze();
            Result result = benchType(obj, EqualityCheck.EQUALS);
            results.add(result);
            // update me if serialized form is expected to change
            assertEquals(43, result.size);
        }

        { // CHECKSTYLE IGNORE THIS LINE
            LinkEvent obj = new LinkEvent(DPID_A, PORT_NO_A, DPID_B, PORT_NO_B);
            obj.createStringAttribute(TopologyElement.TYPE, TopologyElement.TYPE_PACKET_LAYER);
            obj.freeze();
            Result result = benchType(obj, EqualityCheck.EQUALS);
            results.add(result);
            // update me if serialized form is expected to change
            assertEquals(49, result.size);
        }

        { // CHECKSTYLE IGNORE THIS LINE
            PortEvent obj = new PortEvent(DPID_A, PORT_NO_A);
            obj.createStringAttribute(TopologyElement.TYPE, TopologyElement.TYPE_PACKET_LAYER);
            obj.freeze();
            Result result = benchType(obj, EqualityCheck.EQUALS);
            results.add(result);
            // update me if serialized form is expected to change
            assertEquals(24, result.size);
        }

        { // CHECKSTYLE IGNORE THIS LINE
            SwitchEvent obj = new SwitchEvent(DPID_A);
            obj.createStringAttribute(TopologyElement.TYPE, TopologyElement.TYPE_PACKET_LAYER);
            obj.freeze();
            Result result = benchType(obj, EqualityCheck.EQUALS);
            results.add(result);
            // update me if serialized form is expected to change
            assertEquals(21, result.size);
        }

        { // CHECKSTYLE IGNORE THIS LINE
            SwitchEvent evt = new SwitchEvent(DPID_A);
            evt.createStringAttribute(TopologyElement.TYPE, TopologyElement.TYPE_PACKET_LAYER);
            evt.freeze();

            // using the back door to access package-scoped constructor
            Constructor<TopologyEvent> swConst
                = TopologyEvent.class.getDeclaredConstructor(SwitchEvent.class);
            swConst.setAccessible(true);
            TopologyEvent obj = swConst.newInstance(evt);

            Result result = benchType(obj, EqualityCheck.TO_STRING);
            results.add(result);
            // update me if serialized form is expected to change
            assertEquals(26, result.size);
        }

        // TODO Add registered classes we still use.


        // Output for plot plugin
        List<String> slabels = new ArrayList<>();
        List<Number> svalues = new ArrayList<>();
        List<String> tlabels = new ArrayList<>();
        List<Number> tvalues = new ArrayList<>();

        // Type, size, serialize T-put, deserialize T-put, N
        System.out.println("Type, size, serialize T-put, deserialize T-put, N");

        for (Result result : results) {
            System.out.printf("%s, %d, %f, %f, %d\n",
                    result.type, result.size, result.ser, result.deser,
                    NUM_ITERATIONS);

            // Output for plot plugin
            // <Type>_size, <Type>_ser, <Type>_deser
            slabels.addAll(Arrays.asList(
                    result.type + "_size"
                    ));
            svalues.addAll(Arrays.asList(
                    result.size
                    ));
            tlabels.addAll(Arrays.asList(
                    result.type + "_ser",
                    result.type + "_deser"
                    ));
            tvalues.addAll(Arrays.asList(
                    result.ser,
                    result.deser
                    ));
        }

        // Output for plot plugin
        PrintStream size = new PrintStream("target/KryoFactoryTest_size.csv");
        PrintStream tput = new PrintStream("target/KryoFactoryTest_tput.csv");

        for (String label : slabels) {
                size.print(label);
                size.print(", ");
        }
        size.println();
        for (Number value : svalues) {
            size.print(value);
            size.print(", ");
        }
        size.close();

        for (String label : tlabels) {
            tput.print(label);
            tput.print(", ");
        }
        tput.println();
        for (Number value : tvalues) {
            tput.print(value);
            tput.print(", ");
        }
        tput.close();
    }
}