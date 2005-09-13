package net.i2p.router.tunnel;

import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.Payload;
import net.i2p.data.i2np.DataMessage;
import net.i2p.data.i2np.DatabaseStoreMessage;
import net.i2p.data.i2np.DeliveryInstructions;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.data.i2np.GarlicMessage;
import net.i2p.data.i2np.TunnelGatewayMessage;
import net.i2p.router.ClientMessage;
import net.i2p.router.RouterContext;
import net.i2p.router.TunnelInfo;
import net.i2p.router.message.GarlicMessageReceiver;
import net.i2p.util.Log;

/**
 * When a message arrives at the inbound tunnel endpoint, this distributor
 * honors the instructions (safely)
 */
public class InboundMessageDistributor implements GarlicMessageReceiver.CloveReceiver {
    private RouterContext _context;
    private Log _log;
    private Hash _client;
    private GarlicMessageReceiver _receiver;
    
    private static final int MAX_DISTRIBUTE_TIME = 10*1000;
    
    public InboundMessageDistributor(RouterContext ctx, Hash client) {
        _context = ctx;
        _client = client;
        _log = ctx.logManager().getLog(InboundMessageDistributor.class);
        _receiver = new GarlicMessageReceiver(ctx, this, client);
    }
    
    public void distribute(I2NPMessage msg, Hash target) {
        distribute(msg, target, null);
    }
    public void distribute(I2NPMessage msg, Hash target, TunnelId tunnel) {
        // allow messages on client tunnels even after client disconnection, as it may
        // include e.g. test messages, etc.  DataMessages will be dropped anyway
        /*
        if ( (_client != null) && (!_context.clientManager().isLocal(_client)) ) {
            if (_log.shouldLog(Log.INFO))
                _log.info("Not distributing a message, as it came down a client's tunnel (" 
                          + _client.toBase64() + ") after the client disconnected: " + msg);
            return;
        }
        */
        
        if ( (target == null) || ( (tunnel == null) && (_context.routerHash().equals(target) ) ) ) {
            // targetting us either implicitly (no target) or explicitly (no tunnel)
            // make sure we don't honor any remote requests directly (garlic instructions, etc)
            if (msg.getType() == GarlicMessage.MESSAGE_TYPE) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("received garlic message in the tunnel, parse it out");
                _receiver.receive((GarlicMessage)msg);
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("distributing inbound tunnel message into our inNetMessagePool: " + msg);
                _context.inNetMessagePool().add(msg, null, null);
            }
        } else if (_context.routerHash().equals(target)) {
            // the want to send it to a tunnel, except we are also that tunnel's gateway
            // dispatch it directly
            if (_log.shouldLog(Log.INFO))
                _log.info("distributing inbound tunnel message back out, except we are the gateway");
            TunnelGatewayMessage gw = new TunnelGatewayMessage(_context);
            gw.setMessage(msg);
            gw.setTunnelId(tunnel);
            gw.setMessageExpiration(_context.clock().now()+10*1000);
            gw.setUniqueId(_context.random().nextLong(I2NPMessage.MAX_ID_VALUE));
            _context.tunnelDispatcher().dispatch(gw);
        } else {
            // ok, they want us to send it remotely, but that'd bust our anonymity,
            // so we send it out a tunnel first
            TunnelInfo out = _context.tunnelManager().selectOutboundTunnel(_client);
            if (out == null) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("no outbound tunnel to send the client message for " + _client + ": " + msg);
                return;
            }
            if (_log.shouldLog(Log.INFO))
                _log.info("distributing inbound tunnel message back out " + out
                          + " targetting " + target.toBase64().substring(0,4));
            TunnelId outId = out.getSendTunnelId(0);
            if (outId == null) {
                if (_log.shouldLog(Log.ERROR))
                    _log.error("wtf, outbound tunnel has no outboundId? " + out 
                               + " failing to distribute " + msg);
                return;
            }
            if (msg.getMessageExpiration() < _context.clock().now() + 10*1000)
                msg.setMessageExpiration(_context.clock().now() + 10*1000);
            _context.tunnelDispatcher().dispatchOutbound(msg, outId, tunnel, target);
        }
    }

    /**
     * Handle a clove removed from the garlic message
     *
     */
    public void handleClove(DeliveryInstructions instructions, I2NPMessage data) {
        switch (instructions.getDeliveryMode()) {
            case DeliveryInstructions.DELIVERY_MODE_LOCAL:
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("local delivery instructions for clove: " + data.getClass().getName());
                if (data instanceof GarlicMessage) {
                    _receiver.receive((GarlicMessage)data);
                    return;
                } else {
                    if (data instanceof DatabaseStoreMessage) {
                        // treat db store explicitly, since we don't want to republish (or flood)
                        // unnecessarily
                        DatabaseStoreMessage dsm = (DatabaseStoreMessage)data;
                        try {
                            if (dsm.getValueType() == DatabaseStoreMessage.KEY_TYPE_LEASESET)
                                _context.netDb().store(dsm.getKey(), dsm.getLeaseSet());
                            else
                                _context.netDb().store(dsm.getKey(), dsm.getRouterInfo());
                        } catch (IllegalArgumentException iae) {
                            if (_log.shouldLog(Log.WARN))
                                _log.warn("Bad store attempt", iae);
                        }
                    } else {
                        _context.inNetMessagePool().add(data, null, null);
                    }
                    return;
                }
            case DeliveryInstructions.DELIVERY_MODE_DESTINATION:
                if (!(data instanceof DataMessage)) {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("cant send a " + data.getClass().getName() + " to a destination");
                } else if ( (_client != null) && (_client.equals(instructions.getDestination())) ) {
                    if (_log.shouldLog(Log.DEBUG))
                        _log.debug("data message came down a tunnel for " 
                                   + _client.toBase64().substring(0,4));
                    DataMessage dm = (DataMessage)data;
                    Payload payload = new Payload();
                    payload.setEncryptedData(dm.getData());
                    ClientMessage m = new ClientMessage();
                    m.setDestinationHash(_client);
                    m.setPayload(payload);
                    _context.clientManager().messageReceived(m);
                } else {
                    if (_log.shouldLog(Log.ERROR))
                        _log.error("this data message came down a tunnel for " 
                                   + (_client == null ? "no one" : _client.toBase64().substring(0,4))
                                   + " but targetted "
                                   + instructions.getDestination().toBase64().substring(0,4));
                }
                return;
            case DeliveryInstructions.DELIVERY_MODE_ROUTER: // fall through
            case DeliveryInstructions.DELIVERY_MODE_TUNNEL:
                if (_log.shouldLog(Log.INFO))
                    _log.info("clove targetted " + instructions.getRouter() + ":" + instructions.getTunnelId()
                               + ", treat recursively to prevent leakage");
                distribute(data, instructions.getRouter(), instructions.getTunnelId());
                return;
            default:
                if (_log.shouldLog(Log.ERROR))
                    _log.error("Unknown instruction " + instructions.getDeliveryMode() + ": " + instructions);
                return;
        }
    }
}
