/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2000-2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

/*
 * @(#)ClusterBroadcaster.java	1.68 07/23/07
 */ 

package com.sun.messaging.jmq.jmsserver.multibroker;

import java.util.*;
import java.io.*;
import com.sun.messaging.jmq.io.Packet;
import com.sun.messaging.jmq.io.SysMessageID;
import com.sun.messaging.jmq.util.UID;
import com.sun.messaging.jmq.util.ServiceType;
import com.sun.messaging.jmq.jmsserver.core.BrokerAddress;
import com.sun.messaging.jmq.jmsserver.Globals;
import com.sun.messaging.jmq.jmsserver.core.*;
import com.sun.messaging.jmq.jmsserver.data.TransactionUID;
import com.sun.messaging.jmq.jmsserver.cluster.api.*;
import com.sun.messaging.jmq.jmsserver.cluster.router.ClusterRouter; 
import com.sun.messaging.jmq.jmsserver.cluster.router.MultibrokerRouter; 
import com.sun.messaging.jmq.jmsserver.util.*;
import com.sun.messaging.jmq.jmsserver.resources.*;
import com.sun.messaging.jmq.jmsserver.config.*;
import com.sun.messaging.jmq.jmsserver.service.*;
import com.sun.messaging.jmq.jmsserver.persist.api.ChangeRecordInfo;
import com.sun.messaging.jmq.util.log.*;
import com.sun.messaging.jmq.io.Status;
import org.jvnet.hk2.annotations.Service;
import javax.inject.Singleton;

/**
 * this class implements the ClusterBroadcast interface for
 * the broker.
 */
@Service(name = "com.sun.messaging.jmq.jmsserver.multibroker.ClusterBroadcaster")
@Singleton
public  class ClusterBroadcaster implements ClusterBroadcast, 
                MessageBusCallback, ChangeRecordCallback
{

    private static boolean DEBUG_CLUSTER_TXN =
                  Globals.getConfig().getBooleanProperty(
                             Globals.IMQ + ".cluster.debug.txn");

    private static boolean DEBUG = false;

    Logger logger = Globals.getLogger();    
    BrokerConfig config = Globals.getConfig();

    BrokerResources br = Globals.getBrokerResources();
    private int version = 0;
    private com.sun.messaging.jmq.jmsserver.core.BrokerAddress selfAddress = null;
    private String driver = null;
    private Cluster c = null;

    private int connLimit = 0;

    private Protocol protocol = null;

    private ChangeRecordInfo lastSyncedChangeRecord = null;
    private ChangeRecordInfo lastStoredChangeRecord = null;

    private transient Map<BrokerAddress, ChangeRecordInfo> lastReceivedChangeRecord =
                Collections.synchronizedMap(new HashMap<BrokerAddress, ChangeRecordInfo>());
 

    private boolean globalBlockModeOn = false;
    private ClusterRouter clusterRouter = null;
    private DestinationList DL = Globals.getDestinationList();

    public ClusterBroadcaster() { }


    public ClusterBroadcaster(Integer connLimit, Integer version)
    throws BrokerException {
        this(connLimit.intValue(),version.intValue());
    }
    

    public ClusterBroadcaster(int connLimit, int version) 
    throws BrokerException {
        this.init(connLimit, version);
    }

    public void init(int connLimit, int version) 
    throws BrokerException {

        // Create the cluster topology
        this.connLimit = connLimit;
        driver = config.getProperty(ClusterGlobals.TOPOLOGY_PROPERTY);
        if (driver == null)
            driver = "fullyconnected";

        // TBD: JMQ2.1 : Load the topology driver class dynamically...
        if (driver.equals("fullyconnected")) {
            c = (Cluster) new com.sun.messaging.jmq.jmsserver.
                     multibroker.fullyconnected.ClusterImpl(this.connLimit);

            logger.log(Logger.INFO, br.I_CLUSTER_INITIALIZED);
        }
        else {
            driver = "standalone";
        }

        if (driver.equals("standalone")) {
            c = (Cluster) new
                   com.sun.messaging.jmq.jmsserver
                   .multibroker.standalone.ClusterImpl();

            logger.log(Logger.INFO, br.I_STANDALONE_INITIALIZED);
        }
        selfAddress = c.getSelfAddress();

        protocol = new CommonProtocol(this, c, selfAddress);

        if (version != protocol.getHighestSupportedVersion()) {
            throw  new BrokerException("The version "+version+
                              " is not supported by the ClusterBroadcaster");
        }
        this.version = version; 
        c.setCallback(protocol);

        clusterRouter = new MultibrokerRouter(this);
    }

    public Object getProtocol()
    {
        return protocol;
    }

    public int getClusterVersion() throws BrokerException {
        return protocol.getClusterVersion();
    }

    public void startClusterIO() {
        protocol.startClusterIO();
    }

    public void stopClusterIO(boolean requestTakeover, boolean force,
                              BrokerAddress excludedBroker) {
        protocol.stopClusterIO(requestTakeover, force, excludedBroker);
            clusterRouter.shutdown();
    }

	public Hashtable getAllDebugState() {
        Hashtable ht = new Hashtable();
        if (c != null) ht.put("CLUSTER", c.getDebugState());
        if (protocol != null) ht.put("PROTOCOL", protocol.getDebugState());
        ht.put("CLUSTER_ROUTER", clusterRouter.getDebugState());
        return ht;
	}

    /* 
    public void shutdown() {    
        protocol.shutdown();
    }
    */

    /**
     * Handle jmq administration command to reload and update
     * the cluster configuration.
     */
    public void reloadCluster() {
        protocol.reloadCluster();
    }

    public void pauseMessageFlow() throws IOException {
        protocol.stopMessageFlow();
    }

    public void resumeMessageFlow() throws IOException {
        protocol.resumeMessageFlow();
    }

    /**
     * Set the matchProps for the cluster.
     */
    public void setMatchProps(Properties matchProps) {
        protocol.setMatchProps(matchProps);
    }

    /**
     *
     */
    public boolean waitForConfigSync() {
        return protocol.waitForConfigSync();
    }

    /**
     * Returns the address of this broker.
     * @return <code> BrokerAddress </code> object representing this
     * broker.
     */
    public com.sun.messaging.jmq.jmsserver.core.BrokerAddress getMyAddress() {
        return selfAddress;
    }

    public boolean lockSharedResource(String resource, Object owner) {
        if (DEBUG) {
            logger.log(Logger.INFO, "lockSharedResource : " + resource);
        }
        return (protocol.lockSharedResource(resource, owner)
                == ClusterGlobals.MB_LOCK_SUCCESS);
    }

    public boolean lockExclusiveResource(String resource, Object owner)
    {
        if (DEBUG) {
            logger.log(Logger.INFO,"lockExclusiveResource " + resource);
        }
        return (protocol.lockResource(resource, 0, owner)
            == ClusterGlobals.MB_LOCK_SUCCESS);
    }

    public void unlockExclusiveResource(String resource, Object owner) {
        if (DEBUG) {
            logger.log(Logger.INFO,"unlockExclusiveResource " + resource);
        }
        protocol.unlockResource(resource);
    }

    public boolean lockDestination(DestinationUID uid, Object owner) {
        if (DEBUG) {
            logger.log(Logger.INFO,"lockDestination " + uid);
        }
        return (protocol.lockResource(
                    ClusterBroadcast.DESTINATION_EXCLUSIVE_LOCK_PREFIX+
                    uid.toString(), 0, owner)
                 == ClusterGlobals.MB_LOCK_SUCCESS);
    }

    public void unlockDestination(DestinationUID uid, Object owner) {
        if (DEBUG) {
            logger.log(Logger.INFO,"unlockDestination " + uid);
        }
        protocol.unlockResource(
            ClusterBroadcast.DESTINATION_EXCLUSIVE_LOCK_PREFIX+uid.toString());
    }

    public boolean lockClientID(String clientid, Object owner, boolean shared)
    {
        if (DEBUG) {
            logger.log(Logger.INFO,"lockClientID " + clientid);
        }
        if (shared) {
            return (protocol.lockSharedResource(
                        ClusterBroadcast.CLIENTID_EXCLUSIVE_LOCK_PREFIX+
                        clientid, owner) 
                    == ClusterGlobals.MB_LOCK_SUCCESS);
        }
        return (protocol.lockResource(
                   ClusterBroadcast.CLIENTID_EXCLUSIVE_LOCK_PREFIX+clientid,
                   0, owner)
                == ClusterGlobals.MB_LOCK_SUCCESS);
    }

    public void unlockClientID(String clientid, Object owner) {
        if (DEBUG) {
            logger.log(Logger.INFO,"unlockClientID " + clientid);
        }
        protocol.unlockResource(
            ClusterBroadcast.CLIENTID_EXCLUSIVE_LOCK_PREFIX+clientid);
    }

    public boolean getConsumerLock(com.sun.messaging.jmq.jmsserver.core.ConsumerUID uid,
                    DestinationUID duid, int position,
                    int maxActive, Object owner)
            throws BrokerException
    {
        if (DEBUG) {
            logger.log(Logger.INFO,"getConsumerLock " + uid);
        }
        if (maxActive > 1 && 
            ((c.getConfigServer() == null ||
             (c.getConfigServer() != null && !Globals.nowaitForMasterBroker())) && 
                              protocol.getClusterVersion() < VERSION_350)) { 
            throw new BrokerException("Feature not support in this"
               + " cluster protocol");
        }
        return (protocol.lockResource(
                    ClusterBroadcast.Q_CONSUMER_EXCLUSIVE_LOCK_PREFIX+
                    duid.getName() + "_" + position, 0, owner)
                == ClusterGlobals.MB_LOCK_SUCCESS);
    }

    public void unlockConsumer(com.sun.messaging.jmq.jmsserver.core.ConsumerUID uid, 
              DestinationUID duid, int position)
    {
        if (DEBUG) {
            logger.log(Logger.INFO,"unlockConsumer " + uid);
        }
        protocol.unlockResource(
            ClusterBroadcast.Q_CONSUMER_EXCLUSIVE_LOCK_PREFIX+
            duid.getName() + "_" + position);
    }

    private int convertToClusterType(int type) {
        switch (type)
        {
            case MSG_DELIVERED:
                return ClusterGlobals.MB_MSG_DELIVERED;
            case MSG_ACKNOWLEDGED:
                return ClusterGlobals.MB_MSG_CONSUMED;
            case MSG_IGNORED:
                return ClusterGlobals.MB_MSG_IGNORED;
            case MSG_UNDELIVERABLE:
                return ClusterGlobals.MB_MSG_UNDELIVERABLE;
            case MSG_DEAD:
                return ClusterGlobals.MB_MSG_DEAD;
            case MSG_PREPARE:
                return ClusterGlobals.MB_MSG_TXN_PREPARE;
            case MSG_ROLLEDBACK:
                return ClusterGlobals.MB_MSG_TXN_ROLLEDBACK;
            case MSG_TXN_ACKNOWLEDGED_RN:
                return ClusterGlobals.MB_MSG_TXN_ACK_RN;
            case MSG_PREPARE_RN:
                return ClusterGlobals.MB_MSG_TXN_PREPARE_RN;
            case MSG_ROLLEDBACK_RN:
                return ClusterGlobals.MB_MSG_TXN_ROLLEDBACK_RN;
        }
        return ClusterGlobals.MB_MSG_SENT;
    }

    private int convertToLocalType(int type) {
        switch (type)
        {
            case ClusterGlobals.MB_MSG_DELIVERED:
                return MSG_DELIVERED;
            case ClusterGlobals.MB_MSG_CONSUMED:
                return MSG_ACKNOWLEDGED;
            case ClusterGlobals.MB_MSG_IGNORED:
                return MSG_IGNORED;
            case ClusterGlobals.MB_MSG_UNDELIVERABLE:
                return MSG_UNDELIVERABLE;
            case ClusterGlobals.MB_MSG_DEAD:
                return MSG_DEAD;
            case ClusterGlobals.MB_MSG_TXN_PREPARE:
                return MSG_PREPARE;
            case ClusterGlobals.MB_MSG_TXN_ROLLEDBACK:
                return MSG_ROLLEDBACK;
            case ClusterGlobals.MB_MSG_TXN_ACK_RN:
                return MSG_TXN_ACKNOWLEDGED_RN;
            case ClusterGlobals.MB_MSG_TXN_PREPARE_RN:
                return MSG_PREPARE_RN;
            case ClusterGlobals.MB_MSG_TXN_ROLLEDBACK_RN:
                return MSG_ROLLEDBACK_RN;
        }
        return -1;
    }

    public void acknowledgeMessage(BrokerAddress address, SysMessageID sysid, 
                                   com.sun.messaging.jmq.jmsserver.core.ConsumerUID cuid, 
                                   int ackType, Map optionalProps, boolean ackack) 
                                   throws BrokerException {
        if (address == null || address == selfAddress) {
            logger.log(Logger.ERROR,
                BrokerResources.E_INTERNAL_BROKER_ERROR,
                "Invalid broker address " + address + " in acknowledge message " +
                sysid + " [CID=" + cuid + ", ackType=" +
                ClusterGlobals.getAckTypeString(convertToClusterType(ackType)) +
                "]");
            return;
        }
        protocol.sendMessageAck(address, sysid, cuid, convertToClusterType(ackType), 
                                optionalProps, ackack);
    }


    public void acknowledgeMessage2P(BrokerAddress address, SysMessageID[] sysids, 
                                     com.sun.messaging.jmq.jmsserver.core.ConsumerUID[] cuids,
                                     int ackType, Map optionalProps, Long txnID, UID txnStoreSession,
                                     boolean ackack, boolean async)
                                     throws BrokerException {

        if (address == null || (address == selfAddress && 
            (!Globals.getHAEnabled() || txnID == null))) {
            logger.log(Logger.ERROR,
                BrokerResources.E_INTERNAL_BROKER_ERROR,
                "Invalid broker address " + address + " in acknowledge message " +
                sysids + " [CID=" + cuids + ", ackType=" +
                ClusterGlobals.getAckTypeString(convertToClusterType(ackType)) +
                ", TID=" + txnID +"]");
            throw new BrokerException(
               Globals.getBrokerResources().getKString(
               BrokerResources.E_INTERNAL_BROKER_ERROR,
               "Invalid broker address " + address), Status.ERROR);
        }
        if (Globals.getHAEnabled() && address == selfAddress) {
            if (DEBUG_CLUSTER_TXN) {
            logger.log(logger.INFO, "Acknowledge ("+
            ClusterGlobals.getAckTypeString(convertToClusterType(ackType))+
            ") to my address for transaction " +txnID); 
            }
        }
        protocol.sendMessageAck2P(address, sysids, cuids, convertToClusterType(ackType),
                                  optionalProps, txnID, txnStoreSession, ackack, async);
    }


    public void recordUpdateDestination(Destination dest)
        throws BrokerException {
        if (DEBUG) {
            logger.log(Logger.INFO,"recordUpdateDestination : " + dest);
        }
        if (Globals.useSharedConfigRecord()) {
            ChangeRecord.recordUpdateDestination(dest, this);
            return;
        }
        // check compatibility with version of system

        protocol.recordUpdateDestination(dest);
    }

    public void recordRemoveDestination(Destination dest)
        throws BrokerException {
        if (DEBUG) {
            logger.log(Logger.INFO,"recordRemoveDestination : " + dest);
        }
        if (Globals.useSharedConfigRecord()) {
            ChangeRecord.recordRemoveDestination(dest, this);
            return;
        }
        // check compatibility with version of system

        protocol.recordRemoveDestination(dest);
    }

    public void createDestination(Destination dest)
            throws BrokerException
    {
        if (DEBUG) {
            logger.log(Logger.INFO,"createDestination " + dest);
        }
        // check compatibility with version of system

        protocol.sendNewDestination(dest);

    }

    public void recordCreateSubscription(Subscription sub)
        throws BrokerException {
        if (DEBUG) {
            logger.log(Logger.INFO,"recordCreateSubscription " + sub);
        }
        if (Globals.useSharedConfigRecord()) {
            ChangeRecord.recordCreateSubscription(sub, this);
            return;
        }
        protocol.recordCreateSubscription(sub);
    }

    public void recordUnsubscribe(Subscription sub)
        throws BrokerException {
        if (DEBUG) {
            logger.log(Logger.INFO,"recordUnsubscribe " + sub);
        }
        if (Globals.useSharedConfigRecord()) {
            ChangeRecord.recordUnsubscribe(sub, this);
            return;
        }
        protocol.recordUnsubscribe(sub);
    }

    public void createSubscription(Subscription sub, Consumer cons)
            throws BrokerException
    {
        if (DEBUG) {
            logger.log(Logger.INFO,"createSubscription " + sub);
        }
        protocol.sendNewSubscription(sub, cons, false);
    }

    public void createConsumer(Consumer con)
            throws BrokerException
    {
        if (DEBUG) {
            logger.log(Logger.INFO,"createConsumer " + con);
        }
        protocol.sendNewConsumer(con, true /* XXX */);
    }

    public void updateDestination(Destination dest)
            throws BrokerException
    {
        if (DEBUG) {
            logger.log(Logger.INFO,"updateDestination " + dest);
        }
        protocol.sendUpdateDestination(dest);
    }


    public void updateSubscription(Subscription sub)
            throws BrokerException
    {
    }

    public void updateConsumer(Consumer con)
            throws BrokerException
    {
    }


    public void destroyDestination(Destination dest)
            throws BrokerException
    {
        if (DEBUG) {
            logger.log(Logger.INFO,"destroyDestination " + dest);
        }
        protocol.sendRemovedDestination(dest);
    }

    public void destroyConsumer(Consumer con, Map pendingMsgs, boolean cleanup)
            throws BrokerException
    {
        if (DEBUG) {
            logger.log(Logger.INFO,"destroyConsumer " + con+ 
                       ", pendingMsgs="+pendingMsgs+", cleanup="+cleanup);
        }
        protocol.sendRemovedConsumer(con, pendingMsgs, cleanup);
    }

    public void connectionClosed(ConnectionUID uid, boolean admin)
    {
        if (DEBUG) {
            logger.log(Logger.INFO,"connectionClosed " + uid);
        }
       if (!admin)
           protocol.clientClosed(uid, true);
    }

    public void messageDelivered(SysMessageID id, 
         com.sun.messaging.jmq.jmsserver.core.ConsumerUID uid,
         com.sun.messaging.jmq.jmsserver.core.BrokerAddress address)
    {
        if (DEBUG) {
            logger.log(Logger.INFO,"messageDelivered - XXX not implemented");
        }
    }

    public void forwardMessage(PacketReference ref, Collection consumers)
    {
         clusterRouter.forwardMessage(ref, consumers);
    }

    public boolean lockUIDPrefix(short p) {
        if (DEBUG) {
            logger.log(Logger.INFO, "lockUIDPrefix " + p);
        }
        return (protocol.lockResource("uidprefix:" +
            Short.toString(p), 0, new ConnectionUID(0)) ==
            ClusterGlobals.MB_LOCK_SUCCESS);
    }

    public void preTakeover(String brokerID, UID storeSession, 
           String brokerHost, UID brokerSession) throws BrokerException {
        protocol.preTakeover(brokerID, storeSession, brokerHost, brokerSession);
    }
    public void postTakeover(String brokerID, UID storeSession, boolean aborted, boolean notify) {
        protocol.postTakeover(brokerID, storeSession, aborted, notify);
    }
 
    //-----------------------------------------------
    //-      MessageBusCallback                     -
    //-----------------------------------------------

    
    public void configSyncComplete() {
        // Generate a unique short prefix for the UID. This must be
        // done after startClusterIO()..
        Random r = new Random();
        boolean uidInit = false;
        for (int i = 0; i < 5; i++) {
            short p = (short) r.nextInt(Short.MAX_VALUE);
            if (lockUIDPrefix(p)) {
                com.sun.messaging.jmq.util.UID.setPrefix(p);
                uidInit = true;
                break;
            }
        }

        if (!uidInit) {
            logger.log(Logger.WARNING,
                   Globals.getBrokerResources().getKString(
                   BrokerResources.W_CLUSTER_LOCK_UIDPREFIX_FAIL));
        }

        ServiceManager sm = Globals.getServiceManager();
        try {
            if (Globals.nowaitForMasterBroker()) {
                logger.log(logger.INFO, Globals.getBrokerResources().getKString(
                           BrokerResources.I_MBUS_FULLJMS));
                sm.removeServiceRestriction(ServiceType.NORMAL, 
                   ServiceRestriction.NO_SYNC_WITH_MASTERBROKER);
            } else {
                sm.resumeAllActiveServices(ServiceType.NORMAL, true);
            }
        }
        catch (Exception e) {
            logger.logStack(Logger.ERROR,
                BrokerResources.E_INTERNAL_BROKER_ERROR,
                "during broker initialization",
                e);
        }

    }

    /**
     * Interest creation notification. This method is called when
     * any remote interest is created.
     */
    public void interestCreated(Consumer intr) {
        try {
            clusterRouter.addConsumer(intr);
        } catch (Exception ex) {
           logger.log(Logger.INFO, 
           br.getKString(br.W_CLUSTER_ADD_REMOTE_CONSUMER_EXCEPTION, ""+ex, ""+intr));
        }
    }

    public void unsubscribe(Subscription sub) {
        if (DEBUG) {
            logger.log(Logger.DEBUG,"callback unsubscribe : " + sub);
        }

        assert sub != null;

        try {
            Subscription.remoteUnsubscribe(sub.getDurableName(), sub.getClientID());
        } catch (Exception ex) {

           int loglevel = Logger.ERROR;
           if (ex instanceof BrokerException) {
              if (((BrokerException)ex).getStatusCode() == Status.PRECONDITION_FAILED
                  || ((BrokerException)ex).getStatusCode() == Status.NOT_FOUND) {
                 loglevel = Logger.WARNING;
              }
           }
           String args[] = { Subscription.getDSubLogString(
                                 sub.getClientID(), sub.getDurableName()), 
                             ex.getMessage() };
           String emsg = br.getKString(br.E_CLUSTER_UNSUBSCRIBE_EXCEPTION, args);
           if (loglevel == Logger.ERROR || DEBUG) {
              logger.logStack(loglevel, emsg, ex);
           } else {
              logger.log(loglevel, emsg);
           }

        }
    }

    /**
     * Interest removal notification. This method is called when
     * any remote interest is removed.
     */
    public void interestRemoved(Consumer cuid, 
        Map<TransactionUID, LinkedHashMap<SysMessageID, Integer>> pendingMsgs,
        boolean cleanup) {
        if (DEBUG) {
            logger.log(Logger.INFO, "callback interestRemoved " + cuid+
                ", pendingMsgs="+pendingMsgs+", cleanup="+cleanup);
        }
        try {
            clusterRouter.removeConsumer(cuid.getConsumerUID(), pendingMsgs, cleanup);
        } catch (Exception ex) {
           logger.logStack(Logger.ERROR, "Unable to remove remote consumer "+ cuid, ex);
        }
    }



    /**
     * Primary interest change notification. This method is called when
     * a new interest is chosen as primary interest for a failover queue.
     */
    public void activeStateChanged(Consumer intr) {
        // does nothing
        if (DEBUG) {
            logger.log(Logger.INFO,"callback activeStateChanged " + intr );
        }
    }


    /**
     * Client down notification. This method is called when a local
     * or remote client connection is closed.
     */
    public void clientDown(ConnectionUID conid){
        if (DEBUG) {
            logger.log(Logger.INFO,"clientDown " + conid );
        }
        try {
            clusterRouter.removeConsumers(conid);
        } catch (Exception ex) {
           logger.logStack(Logger.INFO,"Internal Error: unable to remove remote consumers "+
                conid, ex);
        }
    }


    /**
     * Broker down notification. This method is called when any broker
     * in this cluster goes down.
     */
    public void brokerDown(com.sun.messaging.jmq.jmsserver.core.BrokerAddress broker){
        if (DEBUG) {
            logger.log(Logger.INFO,"brokerDown " + broker );
        }
        try {
            clusterRouter.brokerDown(broker);
        } catch (Exception ex) {
           logger.logStack(Logger.INFO,"Internal Error: unable to remove remote consumers "+
                broker, ex);
        }
    }


    /**
     * A new destination was created by the administrator on a remote
     * broker.  This broker should also add the destination if it is
     * not already present.
     */
    public void notifyCreateDestination(Destination d){
        try  {
            DL.addDestination(null, d, true);
            d.store();
        } catch (Exception ex) {
            logger.log(Logger.DEBUG,"Received exception adding new destination"
                  + " is caused because the destination " + d 
                  + " is being autocreated on both sides", ex);
 
        }
    }


    /**
     * A destination was removed by the administrator on a remote
     * broker. This broker should also remove the destination, if it
     * is present.
     */
    public void notifyDestroyDestination(DestinationUID uid){
        try {
            DL.removeDestination(null, uid, false, 
               Globals.getBrokerResources().getString(
                   BrokerResources.M_ADMIN_REMOTE));
        } catch (Exception ex) {
            logger.log(Logger.DEBUG,"Internal Error: unable to remove stored destination "
               + uid , ex);
        }
    }


    /**
     * A destination was updated
     */
    public void notifyUpdateDestination(DestinationUID uid, Map changes) {
       Destination[] ds = DL.getDestination(null, uid);
       Destination d = ds[0]; //PART
       if (d != null) {
           try {
               d.setDestinationProperties(changes);
           } catch (Exception ex) {
               logger.log(Logger.INFO,"Internal Error, unable to update destination " +
                    uid.toString(), ex);
           }
       }
    }

    public void processRemoteMessage(Packet msg, 
        Map<ConsumerUID, Integer> consumers, 
        BrokerAddress home,
        boolean sendMsgRedeliver) 
        throws BrokerException {

        clusterRouter.handleJMSMsg(msg, consumers, home, sendMsgRedeliver); 
    }

    public void processRemoteAck(SysMessageID sysid, 
                                 com.sun.messaging.jmq.jmsserver.core.ConsumerUID cuid,
                                 int ackType, Map optionalProps)
                                 throws BrokerException {
        if (DEBUG) {
        logger.log(Logger.INFO, "processRemoteAck: " + sysid + ":" + cuid +
                   ", ackType="+ ClusterGlobals.getAckTypeString(ackType));
        }

        clusterRouter.handleAck(convertToLocalType(ackType), sysid, cuid, 
                                             optionalProps);
    }

    public void processRemoteAck2P(SysMessageID[] sysids, 
                                   com.sun.messaging.jmq.jmsserver.core.ConsumerUID[] cuids,
                                   int ackType, Map optionalProps, Long txnID,
                                   com.sun.messaging.jmq.jmsserver.core.BrokerAddress txnHomeBroker) 
                                   throws BrokerException {
        if (DEBUG) {
        logger.log(Logger.INFO, "processRemoteAck2P: " + sysids[0] + ":" + cuids[0]+
               ", ackType="+ ClusterGlobals.getAckTypeString(ackType)+",from "+txnHomeBroker); 
        }

        clusterRouter.handleAck2P(convertToLocalType(ackType), sysids, cuids, 
                                               optionalProps, txnID, txnHomeBroker);
    }

    /**
     * Switch to HA_ACTIVE state.
     *
     * Falcon HA: Complete the initialization process, start all the
     * ServiceType.NORMAL services and start processing client work.
     */
    public void goHAActive(){
        //XXX  does nothing right now
    }

    public void sendClusterTransactionInfo(long tid,
        com.sun.messaging.jmq.jmsserver.core.BrokerAddress to) {
        protocol.sendClusterTransactionInfo(tid, to);
    }

    public com.sun.messaging.jmq.jmsserver.core.BrokerAddress lookupBrokerAddress(String brokerid) {
        return protocol.lookupBrokerAddress(brokerid);
    }

    public com.sun.messaging.jmq.jmsserver.core.BrokerAddress lookupBrokerAddress(BrokerMQAddress mqaddr) {
        return protocol.lookupBrokerAddress(mqaddr);
    }

    public String lookupStoreSessionOwner(UID session) {
        return protocol.lookupStoreSessionOwner(session);
    }

    public void syncChangeRecordOnStartup() throws BrokerException {
        ChangeRecord.storeResetRecordIfNecessary(this);
        ChangeRecord.syncChangeRecord(this, this,
            ((CommonProtocol)protocol).getRealProtocol(), true);
    }

    public void syncChangeRecordOnJoin(BrokerAddress remote, ChangeRecordInfo cri)
    throws BrokerException {

        String resetUUID = null;
        if (lastSyncedChangeRecord != null) {    
            resetUUID = lastSyncedChangeRecord.getResetUUID(); 
        }
        if (resetUUID == null) {
            resetUUID = (lastStoredChangeRecord == null ? 
                         null:lastStoredChangeRecord.getResetUUID());
        }
        if (resetUUID != null && !resetUUID.equals(cri.getResetUUID())) {
            throw new BrokerException(br.getKString(
                br.X_SHARECC_RESETUID_MISMATCH_ON_JOIN, 
                "["+resetUUID+", "+cri.getResetUUID()+"]", remote.toString()),
                Status.PRECONDITION_FAILED);
        }

        ChangeRecordInfo lastr = lastReceivedChangeRecord.get(remote);
        if (lastr == null || lastr.getSeq().longValue() < cri.getSeq().longValue() ||
            !lastr.getResetUUID().equals(cri.getResetUUID())) {
            if (lastSyncedChangeRecord == null || 
                (lastSyncedChangeRecord != null && 
                lastSyncedChangeRecord.getSeq().longValue() < cri.getSeq().longValue())) {

                logger.log(logger.INFO, br.getKString(br.I_SHARECC_SYNC_ON_JOIN, remote+"["+cri+"]"));
                ChangeRecord.syncChangeRecord(this, this,
                    ((CommonProtocol)protocol).getRealProtocol(), false);
            }
        }
    }

    public void setLastSyncedChangeRecord(ChangeRecordInfo rec) {
        lastSyncedChangeRecord = rec;
    }

    public ChangeRecordInfo getLastSyncedChangeRecord() {
        return lastSyncedChangeRecord;
    }

    public ChangeRecordInfo getLastStoredChangeRecord() {
        return lastStoredChangeRecord;
    }


    public void setLastStoredChangeRecord(ChangeRecordInfo rec) {
        lastStoredChangeRecord = rec;
    }

    public void setLastReceivedChangeRecord(BrokerAddress remote,
                                            ChangeRecordInfo rec) {
        lastReceivedChangeRecord.put(remote, rec);
    }

    public void changeMasterBroker(BrokerMQAddress newmaster, BrokerMQAddress oldmaster)
    throws BrokerException { 
        if (DEBUG) {
            logger.log(Logger.INFO, "changeMasterBroker from " + oldmaster+ 
                       " to "+newmaster);
        }
        protocol.changeMasterBroker(newmaster, oldmaster);
    }

    public String sendTakeoverMEPrepare(String brokerID, byte[] token,
                                        Long syncTimeout, String uuid)
                                        throws BrokerException { 
        if (DEBUG) {
            logger.log(Logger.INFO, "sendTakeoverMEPrepare to " + brokerID);
        }
        return protocol.sendTakeoverMEPrepare(brokerID, token, syncTimeout, uuid);
    }

    public String sendTakeoverME(String brokerID, String uuid)
    throws BrokerException { 
        if (DEBUG) {
            logger.log(Logger.INFO, "sendTakeoverME to " + brokerID);
        }
        return protocol.sendTakeoverME(brokerID, uuid);
    }

    public void sendMigrateStoreRequest(String targetBrokerID, Long syncTimeout,
                                        String uuid, String myBrokerID)
                                        throws BrokerException {
        if (DEBUG) {
            logger.log(Logger.INFO, "sendMigrateStoreRequest to " + targetBrokerID);
        }
        protocol.sendMigrateStoreRequest(targetBrokerID, syncTimeout,
                                         uuid, myBrokerID);
    }

    public void transferFiles(String[] fileNames, String targetBrokerID,
                              Long syncTimeout, String uuid, String myBrokerID,
                              String module, FileTransferCallback callback)
                              throws BrokerException {
        if (DEBUG) {
            logger.log(Logger.INFO, "transferFiles to " + targetBrokerID);
        }
        protocol.transferFiles(fileNames, targetBrokerID, 
                 syncTimeout, uuid, myBrokerID, module, callback);
    }

    public void notifyPartitionArrival(UID partitionID, String brokerID)
    throws BrokerException {
        if (DEBUG) {
            logger.log(Logger.INFO, "notifyPartitionArrival("+partitionID+", "+brokerID+")");
        }
        protocol.notifyPartitionArrival(partitionID,  brokerID);
    }

}

/*
 * EOF
 */
