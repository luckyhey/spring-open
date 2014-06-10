package net.onrc.onos.core.topology.serializers;

import net.onrc.onos.core.topology.Device;
import net.onrc.onos.core.topology.Link;
import net.onrc.onos.core.topology.Switch;
import net.onrc.onos.core.topology.Topology;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.map.SerializerProvider;
import org.codehaus.jackson.map.ser.std.SerializerBase;

import java.io.IOException;

/**
 * JSON serializer for Topology objects.  Used by REST implementation of the
 * topology APIs.
 */
public class TopologySerializer extends SerializerBase<Topology> {

    /**
     * Default constructor. Performs basic initialization of the JSON
     * serializer.
     */
    public TopologySerializer() {
        super(Topology.class);
    }

    /**
     * Serialize a Topology object in JSON.  The resulting JSON contains the
     * switches, links and ports provided by the Topology object.
     *
     * @param topology the Topology that is being converted to JSON
     * @param jsonGenerator generator to place the serialized JSON into
     * @param serializerProvider unused but required for method override
     * @throws IOException if the JSON serialization process fails
     */
    @Override
    public void serialize(Topology topology,
                          JsonGenerator jsonGenerator,
                          SerializerProvider serializerProvider)
            throws IOException {
        // Start the object
        jsonGenerator.writeStartObject();

        // Output the switches array
        jsonGenerator.writeArrayFieldStart("switches");
        for (final Switch swtch : topology.getSwitches()) {
            jsonGenerator.writeObject(swtch);
        }
        jsonGenerator.writeEndArray();

        // Output the links array
        jsonGenerator.writeArrayFieldStart("links");
        for (final Link link : topology.getLinks()) {
            jsonGenerator.writeObject(link);
        }
        jsonGenerator.writeEndArray();

        // Output the devices array
        jsonGenerator.writeArrayFieldStart("devices");
        for (final Device device : topology.getDevices()) {
            jsonGenerator.writeObject(device);
        }
        jsonGenerator.writeEndArray();

        // All done
        jsonGenerator.writeEndObject();
    }
}