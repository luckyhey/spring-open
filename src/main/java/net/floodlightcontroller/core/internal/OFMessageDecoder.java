/**
 *    Copyright 2011, Big Switch Networks, Inc.
 *    Originally created by David Erickson, Stanford University
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 **/

package net.floodlightcontroller.core.internal;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.frame.FrameDecoder;
import org.projectfloodlight.openflow.exceptions.OFParseError;
import org.projectfloodlight.openflow.protocol.OFFactories;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFMessageReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decode an openflow message from a Channel, for use in a netty pipeline
 */
public class OFMessageDecoder extends FrameDecoder {
    private static final Logger log = LoggerFactory.getLogger(OFMessageDecoder.class);
    @Override
    protected Object decode(ChannelHandlerContext ctx, Channel channel,
                            ChannelBuffer buffer) throws Exception {
        if (!channel.isConnected()) {
            // In testing, I see decode being called AFTER decode last.
            // This check avoids that from reading corrupted frames
            return null;
        }

        // Note that a single call to decode results in reading a single
        // OFMessage from the channel buffer, which is passed on to, and processed
        // by, the controller (in OFChannelHandler).
        // This is different from earlier behavior (with the original openflowj),
        // where we parsed all the messages in the buffer, before passing on
        // a list of the parsed messages to the controller.
        // The performance *may or may not* not be as good as before.
        OFMessageReader<OFMessage> reader = OFFactories.getGenericReader();
        OFMessage message = null;
        try {
            message = reader.readFrom(buffer);
        } catch (OFParseError e) {
            OFChannelHandler ofch = (OFChannelHandler) ctx.getPipeline().getLast();
            log.error("Parse failure of incoming message from switch "
                    + ofch.getChannelSwitchInfo() + " Index:Byte ==> {}:{}  {}",
                    buffer.readerIndex(),
                    buffer.getByte(buffer.readerIndex()),
                    buffer.array());

            buffer.clear(); // MUST CLEAR BUFFER or next message will be read
                            // incorrectly
            return null;
        }

        return message;
    }

}
