package net.onrc.onos.ofcontroller.core.internal;

import java.util.Collection;

import net.onrc.onos.ofcontroller.core.ISwitchStorage;
import net.onrc.onos.ofcontroller.core.INetMapTopologyObjects.IPortObject;
import net.onrc.onos.ofcontroller.core.INetMapTopologyObjects.ISwitchObject;
import net.onrc.onos.util.GraphDBOperation;

import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPhysicalPort.OFPortConfig;
import org.openflow.protocol.OFPhysicalPort.OFPortState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SwitchStorageImpl implements ISwitchStorage {
	protected GraphDBOperation op;
	protected static Logger log = LoggerFactory.getLogger(SwitchStorageImpl.class);

	@Override
	public void update(String dpid, SwitchState state, DM_OPERATION op) {
		// TODO Auto-generated method stub
		log.info("SwitchStorage:update dpid:{} state: {} ", dpid, state);
        switch(op) {

        	case UPDATE:
        	case INSERT:
        	case CREATE:
                addSwitch(dpid);
                if (state != SwitchState.ACTIVE) {
                	setStatus(dpid, state);
                }
                break;
        	case DELETE:
                deleteSwitch(dpid);
                break;
        	default:
        }
	}

	private void setStatus(String dpid, SwitchState state) {
		ISwitchObject sw = op.searchSwitch(dpid);
		if (sw != null) {
			sw.setState(state.toString());
			op.commit();
			log.info("SwitchStorage:setStatus dpid:{} state: {} done", dpid, state);
		} 	else {
			op.rollback();
			log.info("SwitchStorage:setStatus dpid:{} state: {} failed: switch not found", dpid, state);
		}
	}

	@Override
	public void addPort(String dpid, OFPhysicalPort port) {
		// TODO Auto-generated method stub
		
        boolean portDown = ((OFPortConfig.OFPPC_PORT_DOWN.getValue() & port.getConfig()) > 0) ||
        		((OFPortState.OFPPS_LINK_DOWN.getValue() & port.getState()) > 0);
       if (portDown) {
             deletePort(dpid, port.getPortNumber());
             return;
       }
             
		try {
			ISwitchObject sw = op.searchSwitch(dpid);

            if (sw != null) {
            	IPortObject p = op.searchPort(dpid, port.getPortNumber());
            	log.info("SwitchStorage:addPort dpid:{} port:{}", dpid, port.getPortNumber());
            	if (p != null) {
            		log.error("SwitchStorage:addPort dpid:{} port:{} exists", dpid, port.getPortNumber());
            	} else {
            		p = op.newPort(port.getPortNumber());
            		p.setState("ACTIVE");
            		p.setPortState(port.getState());
            		p.setDesc(port.getName());
            		sw.addPort(p);
            		op.commit();
            	}
            } else {
        		log.error("SwitchStorage:addPort dpid:{} port:{} : failed switch does not exist", dpid, port.getPortNumber());
            }
		} catch (Exception e) {
             // TODO: handle exceptions
			e.printStackTrace();
			op.rollback();
			log.error("SwitchStorage:addPort dpid:{} port:{} failed", dpid, port.getPortNumber());
		}	

	}

	@Override
	public Collection<OFPhysicalPort> getPorts(long dpid) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public OFPhysicalPort getPort(String dpid, short portnum) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public OFPhysicalPort getPort(String dpid, String portName) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void addSwitch(String dpid) {
		
		log.info("SwitchStorage:addSwitch(): dpid {} ", dpid);
		
		try {
			ISwitchObject sw = op.searchSwitch(dpid);
			if (sw != null) {
				/*
				 *  Do nothing or throw exception?
				 */

				log.info("SwitchStorage:addSwitch dpid:{} already exists", dpid);
				sw.setState(SwitchState.ACTIVE.toString());
				op.commit();
			} else {
				sw = op.newSwitch(dpid);

				if (sw != null) {
					sw.setState(SwitchState.ACTIVE.toString());
					op.commit();
					log.info("SwitchStorage:addSwitch dpid:{} added", dpid);
				} else {
					log.error("switchStorage:addSwitch dpid:{} failed -> newSwitch failed", dpid);
				}
			}
		} catch (Exception e) {
			/*
			 * retry?
			 */
			e.printStackTrace();
			op.rollback();
			log.info("SwitchStorage:addSwitch dpid:{} failed", dpid);
		}


	}

	@Override
	public void deleteSwitch(String dpid) {
		// TODO Setting inactive but we need to eventually remove data

		try {

			ISwitchObject sw = op.searchSwitch(dpid);
            if (sw  != null) {
            	op.removeSwitch(sw);
 
            	op.commit();
            	log.info("SwitchStorage:DeleteSwitch dpid:{} done", dpid);
            }
		} catch (Exception e) {
             // TODO: handle exceptions
			e.printStackTrace();
			op.rollback();			
			log.error("SwitchStorage:deleteSwitch {} failed", dpid);
		}

	}

	@Override
	public void deletePort(String dpid, short port) {
		// TODO Auto-generated method stub
		try {
			ISwitchObject sw = op.searchSwitch(dpid);

            if (sw != null) {
            	IPortObject p = op.searchPort(dpid, port);
                if (p != null) {
            		log.info("SwitchStorage:deletePort dpid:{} port:{} found and deleted", dpid, port);
            		sw.removePort(p);
            		op.removePort(p);
            		op.commit();
            	}
            }
		} catch (Exception e) {
             // TODO: handle exceptions
			e.printStackTrace();
			op.rollback();
			log.info("SwitchStorage:deletePort dpid:{} port:{} failed", dpid, port);
		}	
	}

	@Override
	public void deletePort(String dpid, String portName) {
		// TODO Auto-generated method stub

	}



	@Override
	public void init(String conf) {
		op = new GraphDBOperation(conf);
	}



	public void finalize() {
		close();
	}
	
	@Override
	public void close() {
		op.close();		
	}

	
}