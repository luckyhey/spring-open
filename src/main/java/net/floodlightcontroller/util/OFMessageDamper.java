/*
 * Copyright Big Switch Networks 2012
 */

package net.floodlightcontroller.util;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IOFSwitch;

import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFType;

/**
 * Dampens OFMessages sent to an OF switch. A message is only written to
 * a switch if the same message (as defined by .equals()) has not been written
 * in the last n milliseconds. Timer granularity is based on TimedCache
 *
 * @author gregor
 */
public class OFMessageDamper {
    /**
     * An entry in the TimedCache. A cache entry consists of the sent message
     * as well as the switch to which the message was sent.
     * <p/>
     * NOTE: We currently use the full OFMessage object. To save space, we
     * could use a cryptographic hash (e.g., SHA-1). However, this would
     * obviously be more time-consuming....
     * <p/>
     * We also store a reference to the actual IOFSwitch object and /not/
     * the switch DPID. This way we are guarnteed to not dampen messages if
     * a switch disconnects and then reconnects.
     *
     * @author gregor
     */
    protected static class DamperEntry {
        OFMessage msg;
        IOFSwitch sw;

        public DamperEntry(OFMessage msg, IOFSwitch sw) {
            super();
            this.msg = msg;
            this.sw = sw;
        }

        /* (non-Javadoc)
         * @see java.lang.Object#hashCode()
         */
        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((msg == null) ? 0 : msg.hashCode());
            result = prime * result + ((sw == null) ? 0 : sw.hashCode());
            return result;
        }

        /* (non-Javadoc)
         * @see java.lang.Object#equals(java.lang.Object)
         */
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null) return false;
            if (getClass() != obj.getClass()) return false;
            DamperEntry other = (DamperEntry) obj;
            if (msg == null) {
                if (other.msg != null) return false;
            } else if (!msg.equals(other.msg)) return false;
            if (sw == null) {
                if (other.sw != null) return false;
            } else if (!sw.equals(other.sw)) return false;
            return true;
        }


    }

    TimedCache<DamperEntry> cache;
    EnumSet<OFType> msgTypesToCache;
    // executor for invalidate task
    private static ExecutorService executor = Executors.newFixedThreadPool(1);

    /**
     * @param capacity      the maximum number of messages that should be
     *                      kept
     * @param typesToDampen The set of OFMessageTypes that should be
     *                      dampened by this instance. Other types will be passed through
     * @param timeout       The dampening timeout. A message will only be
     *                      written if the last write for the an equal message more than
     *                      timeout ms ago.
     */
    public OFMessageDamper(int capacity,
                           Set<OFType> typesToDampen,
                           int timeout) {
        cache = new TimedCache<DamperEntry>(capacity, timeout);
        msgTypesToCache = EnumSet.copyOf(typesToDampen);
    }

    /**
     * write the messag to the switch according to our dampening settings
     *
     * @param sw
     * @param msg
     * @param cntx
     * @return true if the message was written to the switch, false if
     * the message was dampened.
     * @throws IOException
     */
    public boolean write(IOFSwitch sw, OFMessage msg, FloodlightContext cntx)
            throws IOException {
        return write(sw, msg, cntx, false);
    }

    /**
     * write the messag to the switch according to our dampening settings
     *
     * @param sw
     * @param msg
     * @param cntx
     * @param flush true to flush the packet immidiately
     * @return true if the message was written to the switch, false if
     * the message was dampened.
     * @throws IOException
     */
    public boolean write(IOFSwitch sw, OFMessage msg,
                         FloodlightContext cntx, boolean flush)
            throws IOException {
        if (!msgTypesToCache.contains(msg.getType())) {
            // XXX S commenting out old message writes
		//sw.write(msg, cntx);
            if (flush) {
                sw.flush();
            }
            return true;
        }

        DamperEntry entry = new DamperEntry(msg, sw);
        if (cache.update(entry)) {
            // entry exists in cache. Dampening.
            return false;
        } else {
            // XXX S commenting out old message writes
		// sw.write(msg, cntx);
            if (flush) {
                sw.flush();
            }
            return true;
        }
    }

    /**
     * Invalidates all the damper cache entries for the specified switch.
     *
     * @param sw switch connection to invalidate
     */
    public void invalidate(final IOFSwitch sw) {
        executor.submit(new Runnable() {
            @Override
            public void run() {
                Iterator<DamperEntry> it = cache.getCachedEntries().iterator();
                while (it.hasNext()) {
                    DamperEntry entry = it.next();
                    if (entry.sw == sw) {
                        it.remove();
                    }
                }
            }
        });
   }
}
